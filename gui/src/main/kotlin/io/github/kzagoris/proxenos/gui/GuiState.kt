package io.github.kzagoris.proxenos.gui

import io.github.kzagoris.proxenos.coreapi.RuntimeEvent
import io.github.kzagoris.proxenos.frontend.Attachment
import io.github.kzagoris.proxenos.frontend.Reason

data class GuiState(
  val attachment: Attachment = Attachment.Attaching,
  val stopConfirmation: Boolean = false,
  val inFlight: String? = null,
  val notice: String? = null,
  val stopped: Boolean = false,
) {
  val snapshot: RuntimeEvent.Snapshot? get() = (attachment as? Attachment.Attached)?.snapshot

  val runtimeWords: String get() = when (val current = attachment) {
    Attachment.Starting -> "Starting"
    Attachment.Attaching -> "Attaching"
    is Attachment.Attached -> "Attached"
    is Attachment.Absent -> when (val reason = current.reason) {
      Reason.NotAnswering -> if (stopped) "Stopped" else "Not running"
      is Reason.StartFailed -> reason.words
    }
  }

  fun observed(next: Attachment): GuiState {
    val newStart = next is Attachment.Attached && snapshot?.start?.id != next.snapshot.start?.id
    return if (newStart) GuiState(attachment = next, inFlight = inFlight)
    else copy(attachment = next, stopConfirmation = stopConfirmation && next is Attachment.Attached)
  }

  fun confirmStop(): GuiState = if (snapshot != null && inFlight == null) copy(stopConfirmation = true) else this

  fun starting(): GuiState = GuiState(attachment = Attachment.Starting)

  fun stopping(): GuiState = copy(stopConfirmation = false, inFlight = "Stop Runtime", notice = null)

  fun finishedStop(words: String?): GuiState = copy(inFlight = null, notice = words, stopped = words == null)
}
