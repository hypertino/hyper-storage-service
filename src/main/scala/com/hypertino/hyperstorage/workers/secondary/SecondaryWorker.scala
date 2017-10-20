package com.hypertino.hyperstorage.workers.secondary

import akka.actor.{Actor, ActorLogging, ActorRef, Props}
import akka.pattern.pipe
import com.hypertino.hyperbus.Hyperbus
import com.hypertino.hyperbus.model._
import com.hypertino.hyperstorage.db._
import com.hypertino.hyperstorage.sharding.{ShardTask, ShardTaskComplete}
import com.hypertino.metrics.MetricsTracker
import monix.execution.Scheduler

import scala.concurrent.ExecutionContext
import scala.util.control.NonFatal

// todo: do we really need a ShardTaskComplete ?

trait SecondaryTaskTrait extends ShardTask {
  def ttl: Long

  def isExpired = ttl < System.currentTimeMillis()

  def group = "hyperstorage-secondary-worker"
}

trait SecondaryTaskError

@SerialVersionUID(1L) case class SecondaryTaskFailed(key: String, reason: String) extends RuntimeException(s"Secondary task for '$key' is failed with reason $reason") with SecondaryTaskError

class SecondaryWorker(val hyperbus: Hyperbus, val db: Db, val tracker: MetricsTracker, val indexManager: ActorRef, implicit val scheduler: Scheduler) extends Actor with ActorLogging
  with BackgroundContentTaskCompleter
  with IndexDefTaskWorker
  with IndexContentTaskWorker {

  override def executionContext: ExecutionContext = context.dispatcher // todo: use other instead of this?

  override def receive: Receive = {
    case task: BackgroundContentTask ⇒
      val owner = sender()
      executeBackgroundTask(owner, task) recover withSecondaryTaskFailed(task) pipeTo owner

    case task: IndexDefTask ⇒
      val owner = sender()
      executeIndexDefTask(task) recover withSecondaryTaskFailed(task) pipeTo owner

    case task: IndexContentTask ⇒
      val owner = sender()
      indexNextBucket(task) recover withSecondaryTaskFailed(task) pipeTo owner
  }


  private def withSecondaryTaskFailed(task: SecondaryTaskTrait): PartialFunction[Throwable, ShardTaskComplete] = {
    case e: SecondaryTaskError ⇒
      log.error(e, s"Can't execute $task")
      ShardTaskComplete(task, e)

    case NonFatal(e) ⇒
      log.error(e, s"Can't execute $task")
      ShardTaskComplete(task, SecondaryTaskFailed(task.key, e.toString))
  }
}

object SecondaryWorker {
  def props(hyperbus: Hyperbus, db: Db, tracker: MetricsTracker, indexManager: ActorRef, scheduler: Scheduler) = Props(classOf[SecondaryWorker],
    hyperbus, db, tracker, indexManager, scheduler
  )
}

