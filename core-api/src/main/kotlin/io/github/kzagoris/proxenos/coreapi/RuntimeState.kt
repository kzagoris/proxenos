@file:UseSerializers(InstantSerializer::class)

package io.github.kzagoris.proxenos.coreapi

import kotlinx.serialization.Serializable
import kotlinx.serialization.UseSerializers

import java.time.Instant

/**
 * The Runtime's link to the tunnel, derived from one measured number — when the
 * tunnel child last polled successfully — plus the Runtime's own start. [Disconnected] alone is
 * not measured: it is the user's intent.
 *
 * **What Connected does not say.** It is the strongest claim this machine can measure: the link
 * is up. A connector deleted in the ChatGPT UI leaves the link up and the Runtime Connected, and
 * nothing on this machine can see it. It says nothing about what any Workspace permits either.
 */
@Serializable
sealed interface RuntimeState {
  /**
   * No poll has succeeded since this Start, or since the user last Connected. One-way: a link
   * that worked and was lost is [Failed], never this — never having had a link is a different
   * fact from losing one.
   */
  @Serializable
  data object Connecting : RuntimeState

  /** The last successful poll is newer than one poll cycle plus a margin. */
  @Serializable
  data object Connected : RuntimeState

  /**
   * A link that worked has gone stale. It is the Runtime's judgement, not an action: the tunnel
   * child keeps retrying on its own, and the state returns to [Connected] when a poll succeeds.
   * It carries the tunnel's own last complaint, quoted, and never a diagnosis — a deleted tunnel
   * and a rejected key look the same from here. `null` when the tunnel said nothing.
   */
  @Serializable
  data class Failed(val complaint: TunnelComplaint?) : RuntimeState

  /** The user took the transport down. Never the Runtime's judgement. */
  @Serializable
  data object Disconnected : RuntimeState
}

/** A [RuntimeState] and the instant it was entered, so a frontend can show for how long. */
@Serializable
data class RuntimeStatus(val state: RuntimeState, val enteredAt: Instant)

/**
 * Words about a link that has never worked: present while the Runtime has been
 * [RuntimeState.Connecting] for one long-poll wait with no success since this Start or the user's
 * last Connect, and absent otherwise. It is what the first-run credential panel quotes.
 *
 * **Words, never a state** (ADR 0005). Connecting stays Connecting however long this is present:
 * a rejected key and a deleted tunnel are the same 401, so nothing here says which it is.
 */
@Serializable
data class ConnectingWords(
  /** The tunnel's last complaint since then, verbatim; `null` when it has said nothing. */
  val complaint: TunnelComplaint?,
  /**
   * The `failure_category` the tunnel's `/health/control-plane` reports, verbatim — the client's
   * own category, never ours. `null` where it has no such route, as v0.0.14 has not, or named none.
   */
  val failureCategory: String?,
)

/**
 * The tunnel child's last complaint about its poll, field by field and **verbatim**: each value
 * is what the tunnel wrote, or `null` where it wrote nothing. No field is derived here, so
 * nothing in it is a classification of ours.
 */
@Serializable
data class TunnelComplaint(
  val statusCode: Int?,
  val errorCode: String?,
  val mitigation: String?,
  val message: String?,
)
