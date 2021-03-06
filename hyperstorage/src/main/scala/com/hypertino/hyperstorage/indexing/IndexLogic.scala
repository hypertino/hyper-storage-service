/*
 * Copyright (c) 2017 Magomed Abdurakhmanov, Hypertino
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 *
 */

package com.hypertino.hyperstorage.indexing

import com.hypertino.binders.value.{Null, Obj, Value}
import com.hypertino.hyperstorage.api.HyperStorageIndexSortOrder.StringEnum
import com.hypertino.parser.ast.{Expression, Identifier}
import com.hypertino.parser.eval.{EvalIdentifierNotFound, ValueContext}
import com.hypertino.parser.{HEval, HParser}
import com.hypertino.hyperstorage.api._
import com.hypertino.hyperstorage.db._
import com.hypertino.hyperstorage.utils.SortBy

import scala.util.{Success, Try}

object IndexLogic {
  def tableName(sortBy: Seq[HyperStorageIndexSortItem]): String = {
    if (sortBy.isEmpty)
      "index_content"
    else {
      sortBy.zipWithIndex.foldLeft(new StringBuilder("index_content_")) { case (tableName, (sortItem, index)) ⇒
        HParser(sortItem.fieldName) match {
          case Identifier(seq) ⇒
          case _ ⇒ throw new IllegalArgumentException(s"Index field name is invalid: ${sortItem.fieldName}")
        }

        tableName
          .append(tableFieldType(sortItem))
          .append(sortItem.order match {
            case Some("desc") ⇒ "d"
            case _ ⇒ "a"
          })
          .append(index)
      }.toString
    }
  }

  private def tableFieldType(sortItem: HyperStorageIndexSortItem): String = {
    sortItem.fieldType match {
      case Some("decimal") ⇒ "d"
      case _ ⇒ "t"
    }
  }

  def serializeSortByFields(sortBy: Seq[HyperStorageIndexSortItem]): Option[String] = {
    import com.hypertino.binders.json.JsonBinders._
    if (sortBy.nonEmpty) Some(sortBy.toJson) else None
  }

  def extractSortFieldValues(idFieldName: String, sortBy: Seq[HyperStorageIndexSortItem], value: Value): Seq[(String, Value)] = {
    val valueContext = value match {
      case obj: Obj ⇒ ValueContext(obj)
      case _ ⇒ ValueContext(Obj.empty)
    }
    val size = sortBy.size
    sortBy.zipWithIndex.map { case (sortItem, index) ⇒
      val fieldName = tableFieldName(idFieldName, sortItem, size, index)
      val fieldValue = HParser(sortItem.fieldName) match {
        case identifier: Identifier if valueContext.identifier.isDefinedAt(identifier) ⇒
          valueContext.identifier(identifier)
        case _ ⇒ Null
      }
      (fieldName, fieldValue)
    }
  }

  def tableFieldName(idFieldName: String, sortItem: HyperStorageIndexSortItem, sortItemSize: Int, index: Int) = {
    if(index == (sortItemSize-1) && sortItem.fieldName == idFieldName)
      "item_id"
    else
      tableFieldType(sortItem) + index.toString
  }

  def validateFilterExpression(expression: String): Try[Boolean] = {
    Try {
      HEval(expression) // we evaluate with empty context, to check everything except EvalIdentifierNotFound
      true
    } recover {
      case e: EvalIdentifierNotFound ⇒
        true
    }
  }

  def evaluateFilterExpression(expression: String, value: Value): Boolean = {
    val v = value match {
      case o: Obj ⇒ o
      case _ ⇒ Obj.empty
    }
    HEval(expression, v).toBoolean
  }

  def sortOrderMatches(indexFieldSortOrder: Option[StringEnum], queryOp: FilterOperator, reversed: Boolean): Boolean = {
    val asc = !indexFieldSortOrder.contains(HyperStorageIndexSortOrder.DESC)
    queryOp match {
      case FilterEq => true
      case FilterGt | FilterGtEq => (asc && !reversed) || (!asc && reversed)
      case FilterLt | FilterLtEq => (!asc && !reversed) || (asc && reversed)
    }
  }

  def weighIndex(idFieldName: String, queryExpression: Option[Expression], querySortOrder: Seq[SortBy], queryFilterFields: Seq[FieldFilter],
                 indexFilterExpression: Option[Expression], indexSortOrder: Seq[HyperStorageIndexSortItem]): Int = {

    val queryFilterFieldsWeight = if (queryExpression.isDefined && indexSortOrder.nonEmpty) {
      val sortSize = indexSortOrder.size
      queryFilterFields.foldLeft((0, 0, indexSortOrder, false)){ case ((index, w, indexSortOrderTail, reversed), f) =>
        if (indexSortOrderTail.isEmpty) {
          (index + 1, w, indexSortOrderTail, reversed)
        } else {
          val h = indexSortOrderTail.head
          val fieldName = tableFieldName(idFieldName, h, sortSize, index)
          if (fieldName == f.name) {
            val (matched, newReversed) = if (sortOrderMatches(h.order, f.op, reversed)) {
              (true, reversed)
            } else {
              if (w == 0 && sortOrderMatches(h.order, f.op, true)) {
                (true, true)
              }
              else {
                (false, reversed)
              }
            }
            if (matched) {
              (index + 1, 10 + w, indexSortOrderTail.tail, newReversed)
            }
            else {
              (index + 1, w, Seq.empty, reversed)
            }
          }
          else {
            (index + 1, -1000000, Seq.empty, false)
          }
        }
      }._2
    } else {
      -30
    }

    val filterWeight = (queryExpression, indexFilterExpression) match {
      case (None, Some(_)) ⇒ -1000000
      case (Some(_), None) ⇒ queryFilterFieldsWeight
      case (None, None) ⇒ 0
      case (Some(q), Some(i)) ⇒
        AstComparator.compare(i,q) match {
          case AstComparation.Equal ⇒ 20 + queryFilterFieldsWeight
          case AstComparation.Wider ⇒ 10 + queryFilterFieldsWeight
          case AstComparation.NotEqual ⇒ -1000001
        }
    }

    val orderWeight = OrderFieldsLogic.weighOrdering(querySortOrder, indexSortOrder)
    orderWeight + filterWeight
  }

  def leastRowsFilterFields(idFieldName: String,
                            indexSortedBy: Seq[HyperStorageIndexSortItem],
                            queryFilterFields: Seq[FieldFilter],
                            prevFilterFieldsSize: Int,
                            prevFilterReachedEnd: Boolean,
                            value: Obj,
                            reversed: Boolean): Seq[FieldFilter] = {

    val valueContext = ValueContext(value)
    val size = indexSortedBy.size
    val isbIdx = indexSortedBy.zipWithIndex.map {
      case (sortItem, index) ⇒
        val fieldName = tableFieldName(idFieldName, sortItem, size, index)
        val fieldValue = HParser(sortItem.fieldName) match {
          case identifier: Identifier if valueContext.identifier.isDefinedAt(identifier) ⇒
            valueContext.identifier(identifier)
          case _ ⇒ Null
        }
        (fieldName, fieldValue, sortItem.order.forall(_ == HyperStorageIndexSortOrder.ASC), index, sortItem.fieldType.getOrElse(HyperStorageIndexSortFieldType.TEXT))
    }

    val reachedEnd = !queryFilterFields.forall { q ⇒
      if (q.op != FilterEq) {
        isbIdx.find(_._1 == q.name).map { i ⇒
          //val op = if (reversed) swapOp(q.op) else q.op
          valueRangeMatches(i._2, q.value, q.op, i._5)
        } getOrElse {
          true
        }
      } else {
        true
      }
    }

    if (reachedEnd) Seq.empty else {
      val startIndex = isbIdx.lastIndexWhere(isb ⇒ queryFilterFields.exists(qf ⇒ qf.name == isb._1 && qf.op == FilterEq)) + 1
      val lastIndex = if (prevFilterFieldsSize == 0 || !prevFilterReachedEnd) {
        size - 1
      } else {
        prevFilterFieldsSize - 2
      }

      isbIdx.flatMap {
        case (fieldName, fieldValue, fieldAscending, index, _) if index >= startIndex ⇒
          if (index == lastIndex) {
            val op = if (reversed ^ fieldAscending)
              FilterGt
            else
              FilterLt
            Some(FieldFilter(fieldName, fieldValue, op))
          } else if (index <= lastIndex) {
            Some(FieldFilter(fieldName, fieldValue, FilterEq))
          } else {
            None
          }
        case _ ⇒ None
      }
    }
  }

  def valueRangeMatches(a: Value, b: Value, op: FilterOperator, sortFieldType: String): Boolean = {
    op match {
      case FilterGt ⇒ greater(a,b,sortFieldType)
      case FilterGtEq ⇒ a == b || greater(a,b,sortFieldType)
      case FilterLt ⇒ greater(b,a,sortFieldType)
      case FilterLtEq ⇒ a == b || greater(b,a,sortFieldType)
      case FilterEq ⇒ a == b
    }
  }

  def greater(a: Value, b: Value, sortFieldType: String): Boolean = {
    sortFieldType match {
      case HyperStorageIndexSortFieldType.DECIMAL ⇒ a.toBigDecimal > b.toBigDecimal
      case _ => a.toString > b.toString
    }
  }

  def mergeLeastQueryFilterFields(queryFilterFields: Seq[FieldFilter],leastFilterFields: Seq[FieldFilter]): Seq[FieldFilter] = {
    if (leastFilterFields.isEmpty) {
      queryFilterFields
    }
    else {
      queryFilterFields.filter(_.op == FilterEq) ++ leastFilterFields
    }
  }

  private def swapOp(op: FilterOperator) = {
    op match {
      case FilterGt ⇒ FilterLt
      case FilterGtEq ⇒ FilterLtEq
      case FilterLt ⇒ FilterGt
      case FilterLtEq ⇒ FilterGtEq
      case FilterEq ⇒ FilterEq
    }
  }
}
