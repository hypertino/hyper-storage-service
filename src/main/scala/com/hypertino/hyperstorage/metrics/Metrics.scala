package com.hypertino.hyperstorage.metrics

object Metrics {
  // time of processing message by primary worker
  val PRIMARY_PROCESS_TIME = "hyperstorage.primary.process-time"

  // time of processing message by secondary worker
  val SECONDARY_PROCESS_TIME = "hyperstorage.secondary.process-time"

  // time of retrieving data (get request)
  val RETRIEVE_TIME = "hyperstorage.retrieve-time"

  val SHARD_PROCESSOR_STASH_METER = "hyperstorage.shard-stash-meter"
  val SHARD_PROCESSOR_TASK_METER = "hyperstorage.shard-message-meter"
  val SHARD_PROCESSOR_FORWARD_METER = "hyperstorage.shard-forward-meter"

  val HOT_QUANTUM_TIMER = "hyperstorage.recovery.hot-quantum-timer"
  val HOT_INCOMPLETE_METER = "hyperstorage.recovery.hot-incomplete-meter"

  val STALE_QUANTUM_TIMER = "hyperstorage.recovery.stale-quantum-timer"
  val STALE_INCOMPLETE_METER = "hyperstorage.recovery.stale-incomplete-meter"
}
