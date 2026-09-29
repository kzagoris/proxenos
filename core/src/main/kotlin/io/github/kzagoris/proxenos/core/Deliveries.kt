package io.github.kzagoris.proxenos.core

import io.github.kzagoris.proxenos.coreapi.*
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.TimeMark
import kotlin.time.TimeSource
import kotlinx.coroutines.CompletableDeferred

/**
 * What tells one arrival of a mutation from a repeat of it (SPEC §6.4, ADR 0004): the
 * caller-supplied key, the Operation it arrived with, and the reply that was sent.
 *
 * **Not a second account of what happened.** It holds a key, the Activity entry it names and
 * the reply, and re-executes nothing; Activity remains the account. And **nothing here
 * persists**: `tunnel-client` is the Runtime's child, so a restart kills the transport and no
 * repeat survives to arrive.
 *
 * A key whose first Delivery ended `failed` is released rather than kept. `failed` means
 * nothing changed, so there is nothing a second execution could do twice — and a key kept
 * against a refusal would answer "at capacity" to the very repeat that refusal invites. A
 * repeat already waiting on it then claims the key afresh, so it is answered as a Delivery of
 * its own and never with a `failed` dressed as the recorded result of an operation performed.
 */
internal class Deliveries(
  /** §6.4: 10 minutes past the **reply**, not past the outcome. Configuration so a test need not wait them out. */
  private val retention: Duration = RETENTION,
  private val recordCap: Int = RECORD_CAP,
  private val keyCap: Int = KEY_CAP,
) {
  private val lock = Any()

  /** Insertion order is age, which is what "evicted oldest-first" means. */
  private val records = LinkedHashMap<String, Record>()

  /** Keys whose record has gone. Outlives it so that reuse is refused rather than re-run. */
  private val expired = LinkedHashSet<String>()

  /** One key's first Delivery: what it asked for, where it is recorded, and what it was answered. */
  class Record(val key: String, val op: Operation<*>) {
    /** Completed as soon as the entry is opened, which is microseconds after the claim. */
    val entry: CompletableDeferred<ActivityEntryId> = CompletableDeferred()
    val reply: CompletableDeferred<Outcome<*>> = CompletableDeferred()

    /** When the reply was given. Null while the first Delivery is in flight, which never expires. */
    @Volatile
    var repliedAt: TimeMark? = null
  }

  /** What a key's arrival turns out to be. */
  sealed interface Claim {
    /** The first Delivery of this key: it does the work, and answers [Record.reply] when it has. */
    data class First(val record: Record) : Claim

    /** The key arrived before with these same arguments: answer the first's reply. */
    data class Repeat(val record: Record) : Claim

    /** The key arrived before with different arguments. */
    data class Conflict(val key: String) : Claim

    /** The key's record has expired, and reusing it is refused. */
    data class Expired(val key: String) : Claim
  }

  /** Atomic, so two Deliveries of one key cannot both be the first. */
  fun claim(key: String, op: Operation<*>): Claim = synchronized(lock) {
    expire()
    val existing = records[key]
    when {
      existing != null -> if (existing.op == op) Claim.Repeat(existing) else Claim.Conflict(key)
      key in expired -> Claim.Expired(key)
      else -> Claim.First(Record(key, op).also { records[key] = it })
    }
  }

  /**
   * The first Delivery's reply. A `failed` releases the key — nothing changed, so there is
   * nothing to protect — and every other reply is kept for [retention] from now.
   */
  fun replied(record: Record, reply: Outcome<*>) {
    synchronized(lock) {
      if (reply is Outcome.Failed) {
        if (records[record.key] === record) records.remove(record.key)
      } else {
        record.repliedAt = TimeSource.Monotonic.markNow()
        evictOverCap()
      }
    }
    // Outside the lock: whoever is waiting on it resumes, and need not wait for this.
    record.reply.complete(reply)
  }

  private fun expire() {
    val gone = records.values.filter { it.repliedAt?.let { at -> at.elapsedNow() >= retention } == true }
    gone.forEach { forget(it) }
  }

  /** Oldest-first, and only records that have a reply: one in flight is protecting a mutation. */
  private fun evictOverCap() {
    val excess = records.size - recordCap
    if (excess <= 0) return
    records.values.filter { it.repliedAt != null }.take(excess).forEach { forget(it) }
  }

  private fun forget(record: Record) {
    records.remove(record.key)
    expired.remove(record.key)
    expired.add(record.key)
    while (expired.size > keyCap) expired.remove(expired.first())
  }

  companion object {
    val RETENTION: Duration = 10.minutes
    const val RECORD_CAP: Int = 1024
    const val KEY_CAP: Int = 4096
  }
}
