package io.github.kzagoris.proxenos.gui

import io.github.kzagoris.proxenos.coreapi.*
import io.github.kzagoris.proxenos.frontend.Attachment
import java.time.Instant
import kotlin.test.*
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/** GUI-SPEC §3.4, §3.5 and G15 at the level of `GuiState`'s transitions. */
class ActivityStateTest {
  private val at = Instant.parse("2026-10-01T00:00:00Z")
  private val first = RuntimeStart(RuntimeStartId("first"), at)
  private val second = RuntimeStart(RuntimeStartId("second"), at.plusSeconds(60))

  private fun entry(id: String, start: RuntimeStart = first, tool: String = "read_file", arguments: String = "path=a",
    outcome: ActivityOutcome = ActivityOutcome.Ok("1 line")) =
    ActivityEntry(ActivityEntryId(id), at, start.id, Origin.ChatGpt, "notes", tool, arguments, 5.milliseconds, outcome, 0, null)

  private fun command(id: String) = RunningOperation(ActivityEntryId(id), Origin.ChatGpt, "scripts", "run_command",
    "command=make test cwd=.", at, promoted = true)

  private fun snapshot(start: RuntimeStart = first, activity: List<ActivityEntry> = emptyList(), running: List<RunningOperation> = emptyList()) =
    RuntimeEvent.Snapshot(emptyList(), RuntimeStatus(RuntimeState.Connected, at), running, activity, start)

  private fun attached(snapshot: RuntimeEvent.Snapshot) = GuiState().observed(Attachment.Attached(snapshot))

  @Test
  fun `the feed is this start's only, newest first, with consecutive polls of one Handle folded into one counted row`() {
    val lost = entry("earlier", outcome = ActivityOutcome.Lost)
    val poll = { id: String -> entry(id, second, tool = "get_result", arguments = "handle=h1") }
    val state = attached(snapshot(first, listOf(lost))).observed(Attachment.Attached(snapshot(second,
      listOf(lost, entry("a", second), poll("p1"), poll("p2"), poll("p3"), entry("b", second)))))
    assertEquals(listOf(listOf("b"), listOf("p1", "p2", "p3"), listOf("a")), state.feedRows.map { row -> row.entries.map { it.id.value } })
    assertTrue(state.feedRows.none { row -> row.entries.any { it.outcome == ActivityOutcome.Lost } })
  }

  @Test
  fun `a new Runtime start clears the Activity selection, its outputs and an open Stop confirmation`() {
    val running = command("c1")
    val state = attached(snapshot(running = listOf(running))).copy(destination = Destination.Activity)
      .after(GuiIntent.ShowActivity(running.entry)).read(running.entry, RunningCommand("make test", ".", 1.seconds, "ok\n", 0))
      .after(GuiIntent.AskStopCommand)
    assertEquals(running.entry, state.stopCommandConfirmation)
    val restarted = state.observed(Attachment.Attached(snapshot(second)))
    assertNull(restarted.activity)
    assertNull(restarted.stopCommandConfirmation)
    assertTrue(restarted.outputs.isEmpty())
    assertEquals(Destination.Activity, restarted.destination)
  }

  @Test
  fun `a running command that ends turns into its entry under the same selection`() {
    val running = command("c1")
    val opened = entry("c1", tool = "run_command", outcome = ActivityOutcome.InFlight)
    val selected = attached(snapshot(activity = listOf(opened), running = listOf(running))).after(GuiIntent.ShowActivity(running.entry))
    assertEquals(running, selected.selectedRunning)
    val ended = selected.observed(Attachment.Attached(snapshot(
      activity = listOf(opened.copy(outcome = ActivityOutcome.Unclaimed(ActivityOutcome.Ok("exit 0")))))))
    assertNull(ended.selectedRunning)
    assertEquals(running.entry, ended.selectedEntry?.id)
    assertTrue(ended.canAcknowledge)
  }

  @Test
  fun `a command that ends under its Stop confirmation closes it and says so, and is no longer polled`() {
    val running = command("c1")
    val confirming = attached(snapshot(running = listOf(running))).copy(destination = Destination.Activity)
      .after(GuiIntent.ShowActivity(running.entry)).after(GuiIntent.AskStopCommand)
    assertEquals(listOf(running.entry), confirming.polled)
    val ended = confirming.observed(Attachment.Attached(snapshot()))
    assertNull(ended.stopCommandConfirmation)
    assertEquals("That command has already ended; there is nothing to stop.", ended.notice)
    assertTrue(ended.polled.isEmpty())
  }

  @Test
  fun `a command already stopping offers no second stop`() {
    val stopping = command("c1").copy(stopping = StopPhase.Terminating)
    val state = attached(snapshot(running = listOf(stopping))).after(GuiIntent.ShowActivity(stopping.entry))
    assertFalse(state.canStopCommand)
    assertNull(state.after(GuiIntent.AskStopCommand).stopCommandConfirmation)
  }
}
