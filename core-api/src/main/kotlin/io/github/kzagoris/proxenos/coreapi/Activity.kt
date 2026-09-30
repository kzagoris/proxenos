@file:UseSerializers(InstantSerializer::class)

package io.github.kzagoris.proxenos.coreapi

import kotlinx.serialization.Serializable
import kotlinx.serialization.UseSerializers

import java.time.Instant
import kotlin.time.Duration

/**
 * Which surface an Operation arrived from. **Bound per surface, never passed per call**:
 * `operationsFor(origin)` is stamped once at startup wiring, so a caller cannot
 * claim an Origin that is not its own and a bug in the `mcp` adapter cannot write "Frontend"
 * against a ChatGPT command — the one record relied on after an unattended `run_command`.
 *
 * It binds no Workspace and carries no conversation identity; the Runtime holds none.
 */
@Serializable
enum class Origin { ChatGpt, Frontend }

/** One Runtime start. Entries name the start they belong to; a restart is not a boundary. */
@Serializable
data class RuntimeStartId(val value: String)

/** A Runtime start and the instant it happened: what the feed's one boundary is drawn from. */
@Serializable
data class RuntimeStart(val id: RuntimeStartId, val at: Instant)

/** Names an entry in Activity. A Handle names one of these rather than a record of its own. */
@Serializable
data class ActivityEntryId(val value: String)

/**
 * The reference by which a Promoted Operation's result is claimed once the call that started it
 * has ended.
 *
 * It **is** an [ActivityEntryId] rather than carrying one, because a Handle names an entry in
 * Activity and nothing else: Activity is already append-only, already persists, already keeps
 * 30 days and already exempts an unresolved entry from expiry, so a second store would only be
 * a second account of what happened. A Handle whose Runtime has Stopped therefore resolves to
 * the [ActivityOutcome.Lost] Operation it points at, with nobody having to arrange that.
 */
@Serializable
data class Handle(val entry: ActivityEntryId) {
  /** What travels on the wire: the entry it names, with nothing added to it. */
  val value: String get() = entry.value

  companion object {
    /** A Handle as it arrived back from the model. Whether it names anything is the core's to say. */
    fun of(value: String): Handle = Handle(ActivityEntryId(value))
  }
}

/**
 * One Operation's line in the account, folded out of the appended records that
 * mention it. An Operation is recorded **once**; a repeat Delivery and an acknowledgement each
 * append a fact against it rather than a second entry, so what the user sees is one thing they
 * asked for once, and what is on disk is never rewritten.
 */
@Serializable
data class ActivityEntry(
  val id: ActivityEntryId,
  /** When the entry was opened — before the work began, which is what makes [ActivityOutcome.Lost] honest. */
  val at: Instant,
  val runtimeStart: RuntimeStartId,
  val origin: Origin,
  /** The name the call gave, whether or not it resolved: a model naming a withheld Workspace is the point. */
  val workspace: String?,
  val tool: String,
  val arguments: String,
  /** Null while the work is still in flight, and for an Operation the Runtime was taken from. */
  val elapsed: Duration?,
  val outcome: ActivityOutcome,
  /** Arrivals of this Operation beyond the first. Zero for an Operation that arrived once. */
  val deliveries: Int,
  /** When the user saw an unresolved outcome. Appended, never a flag set on this entry. */
  val acknowledgedAt: Instant?,
) {
  /**
   * What raises the gutter flag and what the `!` counts: an unresolved outcome nobody has
   * settled. A refused call is an ordinary [ActivityOutcome.Failed] and so is absent from the
   * count — nothing about it is unresolved, and that is what keeps the count meaningful.
   */
  val needsAttention: Boolean get() = outcome.unresolved && acknowledgedAt == null
}

/**
 * Where an Operation got to. Four of these are **unresolved** — Uncertain, Lost, Undelivered
 * and Unclaimed — and they are what the record exists for: they outlive retention until they
 * are settled, by an acknowledgement or, for the two whose answer a later arrival can carry
 * through, by that arrival.
 */
@Serializable
sealed interface ActivityOutcome {
  val unresolved: Boolean get() = false

  /**
   * What this outcome is called wherever one is named — on the frontend's screen, and where
   * `get_result` quotes the account back to the model. Here rather than in each of them,
   * because two spellings of **Unclaimed** is two different facts about this machine as far as
   * anybody reading them can tell.
   */
  val said: String

  /**
   * What was recorded beside it: the Operation's own output, or the sentence a failure was
   * given. Null for the two that carry none of their own — nothing was recorded for an
   * Operation still in flight, and a Lost one produced no result to record.
   */
  val detail: String? get() = null

  /** Opened by the Runtime that is reading it, and still running. Nothing is unresolved about it yet. */
  @Serializable
  data object InFlight : ActivityOutcome {
    override val said: String get() = "in flight"
  }

  @Serializable
  data class Ok(override val detail: String) : ActivityOutcome {
    override val said: String get() = "ok"
  }

  /** Nothing on disk changed. A refused call lands here, like any other failure. */
  @Serializable
  data class Failed(override val detail: String) : ActivityOutcome {
    override val said: String get() = "failed"
  }

  /** Effects on disk unknown: cancelled, timed out, or its Root went Broken partway. */
  @Serializable
  data class Uncertain(override val detail: String) : ActivityOutcome {
    override val said: String get() = "uncertain"
    override val unresolved: Boolean get() = true
  }

  /**
   * Opened and never completed, because the Runtime died while it was in flight. Never
   * written: it is what an entry with no completion beside it *means* once a later Start
   * reads it back. Distinct from [Uncertain] — that produced a result nobody can trust, this
   * produced no result at all.
   */
  @Serializable
  data object Lost : ActivityOutcome {
    override val said: String get() = "Lost"
    override val unresolved: Boolean get() = true
  }

  /** It completed and the answer never reached ChatGPT. A later Delivery carrying it through settles it. */
  @Serializable
  data class Undelivered(val completed: ActivityOutcome) : ActivityOutcome {
    init { requireSettledOutcome(completed) }
    override val said: String get() = "Undelivered"
    /** The answer that went astray is still an answer, and this is what it said. */
    override val detail: String? get() = completed.detail
    override val unresolved: Boolean get() = true
  }

  /** A Promoted Operation completed and nobody collected it. A later `get_result` settles it. */
  @Serializable
  data class Unclaimed(val completed: ActivityOutcome) : ActivityOutcome {
    init { requireSettledOutcome(completed) }
    override val said: String get() = "Unclaimed"
    override val detail: String? get() = completed.detail
    override val unresolved: Boolean get() = true
  }
}

/** Both wrappers say what happened to an answer, so what they wrap has to be one. */
private fun requireSettledOutcome(completed: ActivityOutcome) {
  require(
    completed is ActivityOutcome.Ok ||
      completed is ActivityOutcome.Failed ||
      completed is ActivityOutcome.Uncertain,
  ) { "An answer that went astray is an answer: $completed is not one" }
}
