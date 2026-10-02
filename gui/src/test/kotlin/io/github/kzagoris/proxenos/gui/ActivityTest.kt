package io.github.kzagoris.proxenos.gui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.*
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.dp
import io.github.kzagoris.proxenos.coreapi.*
import io.github.kzagoris.proxenos.frontend.Attachment
import io.github.kzagoris.proxenos.frontend.Wording
import java.time.Instant
import kotlin.test.*
import kotlin.time.Duration.Companion.milliseconds

/** The Activity destination through the composed window: GUI-SPEC §3.4, §4.2 and §5. */
@OptIn(ExperimentalTestApi::class)
class ActivityTest {
  private val at = Instant.parse("2026-10-01T00:00:00Z")
  private val start = RuntimeStart(RuntimeStartId("s1"), at)

  private fun entry(n: Int, tool: String = "read_file", arguments: String = "path=file-$n",
    outcome: ActivityOutcome = ActivityOutcome.Ok("1 line")) =
    ActivityEntry(ActivityEntryId("e$n"), at.plusSeconds(n.toLong()), start.id, Origin.ChatGpt, "notes", tool, arguments,
      5.milliseconds, outcome, 0, null)

  private fun snapshot(activity: List<ActivityEntry>, running: List<RunningOperation> = emptyList()) =
    RuntimeEvent.Snapshot(emptyList(), RuntimeStatus(RuntimeState.Connected, at), running, activity, start)

  private class Harness(initial: GuiState) {
    var state by mutableStateOf(initial)
    val sent = mutableListOf<GuiIntent>()
    fun send(intent: GuiIntent) {
      sent += intent
      state = state.after(intent)
    }
    fun stream(snapshot: RuntimeEvent.Snapshot) {
      state = state.observed(Attachment.Attached(snapshot))
    }
  }

  private fun ComposeUiTest.activity(snapshot: RuntimeEvent.Snapshot): Harness {
    val harness = Harness(GuiState().observed(Attachment.Attached(snapshot)).after(GuiIntent.Show(Destination.Activity)))
    setContent {
      ProxenosTheme(dark = false) {
        Box(Modifier.size(1100.dp, 700.dp)) { Shell(harness.state, harness::send) }
      }
    }
    return harness
  }

  @Test
  fun `the feed keeps the reading position as newer rows arrive, offers n newer, and follows the newest only from the top`() = runComposeUiTest {
    val entries = (1..60).map { entry(it) }
    val gui = activity(snapshot(entries))
    onNodeWithText("file-60", substring = true).assertIsDisplayed()
    // At the newest row, a newer one comes into view and nothing is offered.
    runOnIdle { gui.stream(snapshot(entries + entry(61))) }
    onNodeWithText("file-61", substring = true).assertIsDisplayed()
    onNodeWithText("newer ↑", substring = true).assertDoesNotExist()

    onNode(hasScrollToIndexAction()).performScrollToIndex(30)
    onNodeWithText("file-31", substring = true).assertIsDisplayed()
    runOnIdle { gui.stream(snapshot(entries + (61..63).map { entry(it) })) }
    onNodeWithText("file-31", substring = true).assertIsDisplayed()
    onNodeWithText("32 newer ↑").assertIsDisplayed().performClick()
    onNodeWithText("file-63", substring = true).assertIsDisplayed()
    onNodeWithText("newer ↑", substring = true).assertDoesNotExist()
  }

  @Test
  fun `polls of one Handle are one counted row, and an Uncertain entry is explained and Acknowledged from its detail`() = runComposeUiTest {
    val poll = { n: Int -> entry(n, tool = "get_result", arguments = "handle=h1") }
    val stopped = entry(1, tool = "run_command", arguments = "command=make cwd=.",
      outcome = ActivityOutcome.Uncertain("Still running after the stop: pid 4242 (never signalled: …)"))
    val gui = activity(snapshot(listOf(stopped, poll(2), poll(3), poll(4))))
    onNodeWithText("get_result ×3", substring = true).assertIsDisplayed()
    onNodeWithText("run_command", substring = true).performClick()
    onNodeWithText(Wording.UNCERTAIN).assertIsDisplayed()
    onNodeWithText("Still running after the stop: pid 4242", substring = true).assertIsDisplayed()
    onNode(hasText("Acknowledge") and hasClickAction()).performClick()
    assertTrue(GuiIntent.Acknowledge in gui.sent)
  }

  @Test
  fun `Stop command confirms on Cancel, Enter does not submit, and a stopping command offers no second stop`() = runComposeUiTest {
    val running = RunningOperation(ActivityEntryId("c1"), Origin.ChatGpt, "scripts", "run_command", "command=make test cwd=.", at, promoted = true)
    val gui = activity(snapshot(emptyList(), listOf(running)))
    onNodeWithText("Running now · 1 of 4", ignoreCase = true).assertIsDisplayed()
    onNode(hasText("make test") and hasClickAction()).performClick()
    onNode(hasText("Stop command…") and hasClickAction()).performClick()
    onNodeWithText("Cancel").assertIsFocused()
    onNodeWithText("full authority", substring = true).assertIsDisplayed()
    onAllNodes(isRoot()).onLast().performKeyInput { pressKey(Key.Enter) }
    assertFalse(GuiIntent.ConfirmStopCommand in gui.sent)
    onNodeWithText("Stop command?").assertIsDisplayed()
    onAllNodes(isRoot()).onLast().performKeyInput { pressKey(Key.Escape) }
    onNodeWithText("Stop command?").assertDoesNotExist()

    runOnIdle { gui.stream(snapshot(emptyList(), listOf(running.copy(stopping = StopPhase.Killing)))) }
    onNodeWithText(Wording.stopping(StopPhase.Killing)).assertIsDisplayed()
    onNode(hasText("Stop command…") and hasClickAction()).assertIsNotEnabled()
  }
}
