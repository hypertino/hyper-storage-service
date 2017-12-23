/*
 * Copyright (c) 2017 Magomed Abdurakhmanov, Hypertino
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 *
 */

package com.hypertino.mock.hyperstorage

import com.hypertino.binders.value.{Obj, Text, Value}
import com.hypertino.hyperbus.Hyperbus
import com.hypertino.hyperbus.model.{Created, DynamicBody, ErrorBody, Headers, MessagingContext, NotFound, Ok, PreconditionFailed, RequestBase, ResponseBase}
import com.hypertino.hyperbus.subscribe.Subscribable
import com.hypertino.hyperbus.util.IdGenerator
import com.hypertino.hyperstorage.ContentLogic
import com.hypertino.hyperstorage.api._
import monix.eval.Task
import monix.execution.Scheduler
import monix.execution.atomic.AtomicInt

import scala.collection.concurrent.TrieMap

class HyperStorageMock(protected val hyperbus: Hyperbus, protected implicit val scheduler: Scheduler) extends Subscribable {
  final val hyperStorageContent = TrieMap[String, (Value, Long)]()
  final val failPreconditions = TrieMap[String, (Int, AtomicInt)]()

  final val handlers = hyperbus.subscribe(this)

  def stop(): Unit = {
    handlers.foreach(_.cancel)
  }

  def reset(): Unit = {
    hyperStorageContent.clear()
    failPreconditions.clear()
  }

  def onContentPut(implicit request: ContentPut): Task[ResponseBase] = {
    hbpc(request).map { rev ⇒
      val newRev = rev + 1
      if (hyperStorageContent.put(request.path, (request.body.content, newRev)).isDefined) {
        Ok(HyperStorageTransaction(IdGenerator.create(),request.path, newRev))
      }
      else {
        Created(HyperStorageTransactionCreated(IdGenerator.create(), request.path, newRev, target = Obj.empty))
      }
    }
  }

  def onContentPatch(implicit request: ContentPatch): Task[ResponseBase] = {
    hbpc(request).map { rev ⇒
      val newRev = rev + 1
      hyperStorageContent.get(request.path) match {
        case Some(v) ⇒

          val newContent = ContentLogic.applyPatch(v._1, request)
          hyperStorageContent.put(request.path, (newContent, newRev))
          Ok(HyperStorageTransaction(IdGenerator.create(),request.path, newRev))

        case None ⇒
          NotFound()
      }
    }
  }

  def onContentDelete(implicit request: ContentDelete): Task[ResponseBase] = {
    hbpc(request).map { rev ⇒
      val newRev = rev + 1
      if (hyperStorageContent.remove(request.path).isDefined) {
        Ok(HyperStorageTransaction(IdGenerator.create(),request.path, newRev))
      }
      else {
        NotFound()
      }
    }
  }

  def onContentGet(implicit request: ContentGet): Task[ResponseBase] = {
    hbpc(request).map { _ ⇒
      hyperStorageContent.get(request.path) match {
        case Some(v) ⇒ Ok(DynamicBody(v._1), headers = Headers(HyperStorageHeader.ETAG → Text("\"" + v._2 + "\"")))
        case None ⇒ NotFound()
      }
    }
  }

  private def hbpc(implicit request: RequestBase): Task[Long] = {
    val path = request.headers.hrl.query.dynamic.path.toString
    val existingRev = hyperStorageContent.get(path).map { case (_, v) ⇒
      v
    }.getOrElse {
      0l
    }
    val existingTag = "\"" + existingRev + "\""

    {
      request.headers.get("if-match").map { etag ⇒ {
        checkFailPreconditions(path)
      }.flatMap { _ ⇒
        if ((existingTag != etag.toString) && (!(etag.toString == "*" && existingRev != 0))) {
          Task.raiseError(PreconditionFailed(ErrorBody("revision")))
        } else {
          Task.now(existingRev)
        }
      }
      } getOrElse {
        Task.now(0l)
      }
    }.flatMap { r ⇒
      request.headers.get("if-none-match").map { etag ⇒ {
        checkFailPreconditions(path)
      }.flatMap { _ ⇒
        if ((existingTag == etag.toString) || (etag.toString == "*" && existingRev != 0)) {
          Task.raiseError(PreconditionFailed(ErrorBody("revision")))
        } else {
          Task.now(existingRev)
        }
      }
      } getOrElse {
        Task.now(r)
      }
    }
  }

  private def checkFailPreconditions(path: String)(implicit mcx: MessagingContext): Task[Unit] = {
    failPreconditions.get(path).map { fp ⇒
      if (fp._2.incrementAndGet() <= fp._1) {
        Task.raiseError(PreconditionFailed(ErrorBody("fake")))
      }
      else {
        Task.unit
      }
    }.getOrElse {
      Task.unit
    }
  }

}