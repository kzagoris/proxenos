package io.github.kzagoris.proxenos.gui

import io.github.kzagoris.proxenos.coreapi.ManagementAct
import io.github.kzagoris.proxenos.coreapi.RuntimeEvent
import io.github.kzagoris.proxenos.coreapi.RuntimeStart
import io.github.kzagoris.proxenos.coreapi.RuntimeStartId
import io.github.kzagoris.proxenos.coreapi.RuntimeState
import io.github.kzagoris.proxenos.coreapi.RuntimeStatus
import io.github.kzagoris.proxenos.frontend.Attachment
import io.github.kzagoris.proxenos.frontend.Reason
import java.time.Instant
import kotlin.test.*

class GuiStateTest {
  private val at = Instant.parse("2026-10-01T00:00:00Z")
  private val snapshot = RuntimeEvent.Snapshot(emptyList(), RuntimeStatus(RuntimeState.Disconnected, at), emptyList(),
    start = RuntimeStart(RuntimeStartId("first"), at))
  private val attached = GuiState().observed(Attachment.Attached(snapshot))

  @Test
  fun `a stream ending removes the Runtime's state and every open dialog`() {
    val confirming = attached.after(GuiIntent.AskStop)
    assertTrue(confirming.stopConfirmation)
    val absent = confirming.observed(Attachment.Absent(Reason.NotAnswering))
    assertNull(absent.snapshot)
    assertFalse(absent.stopConfirmation)
    assertEquals("Not running", absent.runtimeWords)
    assertFalse(attached.after(GuiIntent.AddWorkspace).observed(Attachment.Absent(Reason.NotAnswering)).adding)
  }

  @Test
  fun `only a successful local Stop names an absent Runtime Stopped and Start clears it`() {
    val stopped = attached.after(GuiIntent.AskStop).performing(ManagementAct.Stop)
      .observed(Attachment.Absent(Reason.NotAnswering)).performed(ManagementAct.Stop, null)
    assertEquals("Stopped", stopped.runtimeWords)
    assertNull(stopped.inFlight)
    val starting = stopped.starting()
    assertEquals("Starting", starting.runtimeWords)
    assertFalse(starting.stopped)
  }

  @Test
  fun `a refused Stop keeps the Runtime's words and never claims it stopped`() {
    val refused = attached.performing(ManagementAct.Stop).performed(ManagementAct.Stop, "Stop refused")
    assertEquals("Stop refused", refused.notice)
    assertFalse(refused.stopped)
    assertNull(refused.inFlight)
  }

  @Test
  fun `a new Runtime start clears what belonged to the old one and keeps where the user is looking`() {
    val state = attached.after(GuiIntent.ShowStage(Stage.Tunnel)).copy(notice = "An earlier refusal").after(GuiIntent.AskStop)
    val restarted = state.observed(Attachment.Attached(snapshot.copy(start = RuntimeStart(RuntimeStartId("second"), at))))
    assertFalse(restarted.stopConfirmation)
    assertNull(restarted.notice)
    assertFalse(restarted.stopped)
    assertEquals(Destination.Connection, restarted.destination)
    assertEquals(Stage.Tunnel, restarted.stage)
  }

  @Test
  fun `a Register refusal stays in the open dialog and success closes it`() {
    val act = ManagementAct.Register("/home/u/notes")
    val adding = attached.after(GuiIntent.AddWorkspace).performing(act)
    val refused = adding.performed(act, "That Root is already registered.")
    assertTrue(refused.adding)
    assertEquals("That Root is already registered.", refused.refusal)
    assertNull(refused.notice)
    assertFalse(adding.performed(act, null).adding)
  }

  @Test
  fun `an open dialog holds navigation still, and Back leaves only the compact stage detail`() {
    val confirming = attached.after(GuiIntent.AskStop)
    assertEquals(confirming, confirming.after(GuiIntent.Show(Destination.Activity)))
    assertEquals(confirming, confirming.after(GuiIntent.AddWorkspace))
    val detail = attached.after(GuiIntent.ShowStage(Stage.Connector))
    assertEquals(Destination.Connection, detail.destination)
    assertNull(detail.after(GuiIntent.Back).stage)
    assertEquals(attached, attached.after(GuiIntent.Back))
  }

  @Test
  fun `nothing can be added or stopped without a Runtime to ask`() {
    val absent = GuiState(attachment = Attachment.Absent(Reason.NotAnswering))
    assertFalse(absent.after(GuiIntent.AddWorkspace).adding)
    assertFalse(absent.after(GuiIntent.AskStop).stopConfirmation)
  }
}
