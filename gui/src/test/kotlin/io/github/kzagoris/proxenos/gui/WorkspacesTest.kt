package io.github.kzagoris.proxenos.gui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.dp
import io.github.kzagoris.proxenos.coreapi.*
import io.github.kzagoris.proxenos.frontend.Attachment
import java.time.Instant
import kotlin.test.*

@OptIn(ExperimentalTestApi::class)
class WorkspacesTest {
  private val at = Instant.parse("2026-10-02T00:00:00Z")
  private val notes = Workspace(WorkspaceId("notes-id"), "notes", "/home/u/notes", AccessLevel.Read)
  private val scripts = Workspace(WorkspaceId("scripts-id"), "scripts", "/home/u/scripts", AccessLevel.None)
  private val snapshot = RuntimeEvent.Snapshot(
    workspaces = listOf(WorkspaceState(notes, false), WorkspaceState(scripts, false)),
    runtime = RuntimeStatus(RuntimeState.Connected, at), running = emptyList(),
    start = RuntimeStart(RuntimeStartId("first"), at),
  )

  private fun level(text: String) = hasText(text) and SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.RadioButton)

  private class Harness(snapshot: RuntimeEvent.Snapshot) {
    var state by mutableStateOf(GuiState().observed(Attachment.Attached(snapshot)))
    val sent = mutableListOf<GuiIntent>()
    fun send(intent: GuiIntent) {
      sent += intent
      state = state.after(intent)
    }
  }

  private fun ComposeUiTest.shell(snapshot: RuntimeEvent.Snapshot = this@WorkspacesTest.snapshot, width: Int = 1000): Harness {
    val gui = Harness(snapshot)
    setContent {
      ProxenosTheme(false) {
        Box(Modifier.size(width.dp, 700.dp).onPreviewKeyEvent { shortcut(it, gui.state, gui::send) {} }) {
          Shell(gui.state, gui::send)
        }
      }
    }
    return gui
  }

  @Test
  fun `row focus and selection stay with the identity when another Workspace is forgotten`() = runComposeUiTest {
    val gui = shell()
    onNode(hasText("notes") and hasClickAction()).requestFocus()
    onRoot().performKeyInput { pressKey(Key.MoveEnd) }
    assertEquals(scripts.id, gui.state.workspace)
    onNode(hasText("scripts") and hasClickAction()).assertIsFocused()
    runOnIdle {
      gui.state = gui.state.observed(Attachment.Attached(snapshot.after(RuntimeEvent.Change.WorkspaceForgotten(notes.id))))
    }
    onNode(hasText("scripts") and hasClickAction()).assertIsFocused().assertIsSelected()
    onNodeWithText("stops nothing already running", substring = true).assertExists()
    runOnIdle {
      gui.state = gui.state.observed(Attachment.Attached(gui.state.snapshot!!.after(RuntimeEvent.Change.WorkspaceForgotten(scripts.id))))
    }
    onNodeWithText("This Workspace is gone.").assertExists()
    onNodeWithText("Command").assertDoesNotExist()
    assertEquals(scripts.id, gui.state.workspace)
  }

  @Test
  fun `level buttons send an intent and wait for the stream while other acts are disabled`() = runComposeUiTest {
    val gui = shell()
    onNode(hasText("notes") and hasClickAction()).performClick()
    onNode(level("Read")).assertIsSelected()
    onNode(level("Write")).performClick()
    assertEquals(GuiIntent.SetLevel(AccessLevel.Write), gui.sent.last())
    onNode(level("Read")).assertIsSelected()
    onNode(level("Write")).assertIsNotSelected()
    runOnIdle { gui.state = gui.state.performing(ManagementAct.SetLevel(notes.id, AccessLevel.Write)) }
    for (text in listOf("None", "Read", "Write", "Command"))
      onNode(level(text)).assertIsNotEnabled()
    onNode(hasText("scripts") and hasClickAction()).performClick()
    assertEquals(scripts.id, gui.state.workspace, "selection stays live while an act is in flight")
    runOnIdle {
      gui.state = gui.state.observed(Attachment.Attached(snapshot.after(
        RuntimeEvent.Change.WorkspaceChanged(WorkspaceState(notes.copy(accessLevel = AccessLevel.Write), false)))))
        .performed(ManagementAct.SetLevel(notes.id, AccessLevel.Write), null)
    }
    onNode(hasText("notes") and hasClickAction()).performClick()
    onNode(level("Write")).assertIsSelected()
  }

  @Test
  fun `compact keyboard activation opens a Workspace and Escape returns to its list`() = runComposeUiTest {
    val gui = shell(width = 480)
    onNode(hasText("notes") and hasClickAction()).requestFocus()
    onRoot().performKeyInput { pressKey(Key.DirectionDown) }
    onNode(hasText("scripts") and hasClickAction()).assertIsFocused()
    onRoot().performKeyInput { pressKey(Key.Enter) }
    assertEquals(scripts.id, gui.state.workspace)
    onNodeWithText("Back").assertExists()
    onNodeWithText("Back").requestFocus()
    onRoot().performKeyInput { pressKey(Key.Escape) }
    onNodeWithText("Back").assertDoesNotExist()
    onNode(hasText("notes") and hasClickAction()).assertExists()
    assertNull(gui.state.workspace)
  }

  @Test
  fun `Broken is visible in the row and detail alongside the registered Root`() = runComposeUiTest {
    shell(snapshot.copy(workspaces = listOf(WorkspaceState(notes, true))))
    onNode(hasText("notes") and hasText("Broken") and hasClickAction()).performClick()
    onNodeWithText("until you re-confirm it", substring = true).assertExists()
    onAllNodesWithText(notes.root).assertCountEquals(2)
  }

  @Test
  fun `a Promoted command below Command says both consequences and opens that command in Activity`() = runComposeUiTest {
    val running = RunningOperation(ActivityEntryId("command-entry"), Origin.ChatGpt, notes.name, "run_command", "sleep 30", at, promoted = true)
    val gui = shell(snapshot.copy(running = listOf(running)))
    onNode(hasText("notes") and hasClickAction()).performClick()
    onNodeWithText("did not stop", substring = true).assertExists()
    onNodeWithText("result can no longer be collected", substring = true).assertExists()
    onNodeWithText("Show running command").performClick()
    assertEquals(Destination.Activity, gui.state.destination)
    assertEquals(running.entry, gui.state.activity)
  }

  @Test
  fun `Command confirms on Cancel and Enter cannot submit even on the confirm button`() = runComposeUiTest {
    val gui = shell()
    onNode(hasText("notes") and hasClickAction()).performClick()
    onNodeWithText("~/.ssh", substring = true).assertExists()
    onNode(level("Command")).performClick()
    onNodeWithText("Cancel").assertIsFocused()
    onNodeWithText("Raise to Command").requestFocus()
    onAllNodes(isRoot()).onLast().performKeyInput { pressKey(Key.Enter); pressKey(Key.NumPadEnter) }
    assertFalse(GuiIntent.ConfirmCommand in gui.sent)
    onNodeWithText("Raise to Command").performClick()
    assertEquals(GuiIntent.ConfirmCommand, gui.sent.last())
    onAllNodes(isRoot()).onLast().performKeyInput { pressKey(Key.Escape) }
    onNodeWithText("Raise 'notes' to Command?").assertDoesNotExist()
    onNodeWithText("~/.ssh", substring = true).assertExists()
  }
  @Test
  fun `Rename opens the name field and Escape cancels without changing the Workspace`() = runComposeUiTest {
    val gui = shell()
    onNode(hasText("notes") and hasClickAction()).performClick()
    onNodeWithText("Rename…").performClick()
    onNode(hasSetTextAction() and hasText("notes")).assertExists()
    onAllNodes(isRoot()).onLast().performKeyInput { pressKey(Key.Escape) }
    onNodeWithText("Rename Workspace").assertDoesNotExist()
    assertEquals("notes", gui.state.selectedWorkspace!!.workspace.name)
  }

  @Test
  fun `Move confirms on Cancel and explains Read before submitting a new Root`() = runComposeUiTest {
    val gui = shell(snapshot.copy(workspaces = listOf(WorkspaceState(notes.copy(accessLevel = AccessLevel.Write), false))))
    onNode(hasText("notes") and hasClickAction()).performClick()
    onNodeWithText("Move to another folder…").performClick()
    onNodeWithText("Cancel").assertIsFocused()
    onNodeWithText("lands at Read", substring = true).assertExists()
    onNode(hasSetTextAction() and hasText(notes.root)).performTextReplacement("/home/u/moved")
    waitUntil(timeoutMillis = 5000) { onAllNodes(hasText("Move at Read") and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
    onNodeWithText("Move at Read").performClick()
    assertEquals(GuiIntent.Move("/home/u/moved"), gui.sent.last())
    assertEquals(notes.root, gui.state.selectedWorkspace!!.workspace.root)
  }

  @Test
  fun `Broken offers re-confirm and another folder and explains the identity change`() = runComposeUiTest {
    val gui = shell(snapshot.copy(workspaces = listOf(WorkspaceState(notes.copy(accessLevel = AccessLevel.Write), true))))
    onNode(hasText("notes") and hasClickAction()).performClick()
    onNodeWithText("Choose another folder…").assertExists()
    onNodeWithText("Re-confirm this folder…").performClick()
    onNodeWithText("Cancel").assertIsFocused()
    onNodeWithText("It was at Write.", substring = true).assertExists()
    onNodeWithText("directory there is no longer the one", substring = true).assertExists()
    onNodeWithText("Re-confirm at Read").performClick()
    assertEquals(GuiIntent.ConfirmReconfirm, gui.sent.last())
  }

  @Test
  fun `Forget names running work and preserves Activity and Enter cannot submit`() = runComposeUiTest {
    val running = RunningOperation(ActivityEntryId("command-entry"), Origin.ChatGpt, notes.name, "run_command", "command=sleep 30 cwd=.", at, promoted = true)
    val gui = shell(snapshot.copy(workspaces = listOf(WorkspaceState(notes.copy(accessLevel = AccessLevel.Command), false)), running = listOf(running)))
    onNode(hasText("notes") and hasClickAction()).performClick()
    onNodeWithText("Forget…").performClick()
    onNodeWithText("Cancel").assertIsFocused()
    onNodeWithText("Activity stays", substring = true).assertExists()
    onNodeWithText("sleep 30", substring = true).assertExists()
    onNodeWithText("still running", substring = true).assertExists()
    onNodeWithText("not stopped", substring = true).assertExists()
    onNodeWithText("Forget Workspace").requestFocus()
    onAllNodes(isRoot()).onLast().performKeyInput { pressKey(Key.Enter); pressKey(Key.NumPadEnter) }
    assertFalse(GuiIntent.ConfirmForget in gui.sent)
    onNodeWithText("Forget Workspace").performClick()
    assertEquals(GuiIntent.ConfirmForget, gui.sent.last())
  }

  @Test
  fun `Forget still names running work started before the Workspace was renamed`() = runComposeUiTest {
    val running = RunningOperation(ActivityEntryId("command-entry"), Origin.ChatGpt, "notes", "run_command", "command=sleep 30 cwd=.", at)
    shell(snapshot.copy(workspaces = listOf(WorkspaceState(notes.copy(name = "renamed", accessLevel = AccessLevel.Command), false)), running = listOf(running)))
    onNode(hasText("renamed") and hasClickAction()).performClick()
    onNodeWithText("Forget…").performClick()
    onNodeWithText("sleep 30", substring = true).assertExists()
    onNodeWithText("Workspace at start: notes", substring = true).assertExists()
    onNodeWithText("not stopped by Forget", substring = true).assertExists()
  }

}
