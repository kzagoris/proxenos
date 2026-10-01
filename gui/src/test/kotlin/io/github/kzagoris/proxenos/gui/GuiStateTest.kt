package io.github.kzagoris.proxenos.gui

import io.github.kzagoris.proxenos.coreapi.RuntimeEvent
import io.github.kzagoris.proxenos.coreapi.ManagementAct
import io.github.kzagoris.proxenos.coreapi.RuntimeState
import io.github.kzagoris.proxenos.coreapi.RuntimeStatus
import io.github.kzagoris.proxenos.coreapi.RuntimeStart
import io.github.kzagoris.proxenos.coreapi.RuntimeStartId
import io.github.kzagoris.proxenos.frontend.Attachment
import io.github.kzagoris.proxenos.frontend.Reason
import kotlin.test.*
import java.time.Instant

class GuiStateTest {
  @Test
  fun `a stream ending removes the Runtime's state and the open confirmation`() {
    val snapshot = RuntimeEvent.Snapshot(emptyList(), RuntimeStatus(RuntimeState.Disconnected, Instant.parse("2026-10-01T00:00:00Z")), emptyList())
    val attached = GuiState().observed(Attachment.Attached(snapshot)).confirmStop()
    assertNotNull(attached.snapshot)
    assertTrue(attached.stopConfirmation)
    val absent = attached.observed(Attachment.Absent(Reason.NotAnswering))
    assertNull(absent.snapshot)
    assertFalse(absent.stopConfirmation)
    assertEquals("Not running", absent.runtimeWords)
  }

  @Test
  fun `only a successful local Stop names an absent Runtime Stopped and Start clears it`() {
    val stopped = GuiState(attachment = Attachment.Absent(Reason.NotAnswering), inFlight = ManagementAct.Stop)
      .finishedStop(null)
    assertEquals("Stopped", stopped.runtimeWords)
    assertNull(stopped.inFlight)
    val starting = stopped.starting()
    assertEquals("Starting", starting.runtimeWords)
    assertFalse(starting.stopped)
  }

  @Test
  fun `a refused Stop keeps the Runtime's words and never claims it stopped`() {
    val refused = GuiState(inFlight = ManagementAct.Stop).finishedStop("Stop refused")
    assertEquals("Stop refused", refused.notice)
    assertFalse(refused.stopped)
    assertNull(refused.inFlight)
  }

  @Test
  fun `a new Runtime start clears confirmation and notices`() {
    val at = Instant.parse("2026-10-01T00:00:00Z")
    val first = RuntimeEvent.Snapshot(emptyList(), RuntimeStatus(RuntimeState.Disconnected, at), emptyList(),
      start = RuntimeStart(RuntimeStartId("first"), at))
    val state = GuiState(attachment = Attachment.Attached(first), notice = "An earlier refusal").confirmStop()
    val restarted = state.observed(Attachment.Attached(first.copy(start = RuntimeStart(RuntimeStartId("second"), at))))
    assertFalse(restarted.stopConfirmation)
    assertNull(restarted.notice)
    assertFalse(restarted.stopped)
  }
}
