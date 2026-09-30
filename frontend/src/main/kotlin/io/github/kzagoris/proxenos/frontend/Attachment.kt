package io.github.kzagoris.proxenos.frontend

import io.github.kzagoris.proxenos.coreapi.RuntimeEvent

/**
 * Where a frontend stands with the Runtime. Only [Attached] carries anything the Runtime said;
 * every other value is this frontend's own side of the link, and never a reading of the tunnel.
 */
sealed interface Attachment {
  /** A start was asked for, and this frontend is waiting for the Runtime to answer. */
  data object Starting : Attachment

  /** Dialling the control socket for the stream. */
  data object Attaching : Attachment

  /** The Runtime's state as of its latest event, every change already folded in. */
  data class Attached(val snapshot: RuntimeEvent.Snapshot) : Attachment

  /** No stream, and why. What was shown before is a claim about a Runtime that is gone. */
  data class Absent(val reason: Reason) : Attachment
}

/**
 * Why a frontend is [Attachment.Absent]: structured, so each frontend words it. A Runtime stopped
 * from elsewhere and one that crashed look the same on the stream, so neither is claimed.
 */
sealed interface Reason {
  /** Nothing answered on the control socket, or the stream it held ended. */
  data object NotAnswering : Reason

  /** A start was asked for and did not come to answer; [words] are what came back, verbatim where the Runtime said them. */
  data class StartFailed(val words: String) : Reason
}
