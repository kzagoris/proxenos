package io.github.kzagoris.proxenos.gui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
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
class ToolsTest {
  private val at = Instant.parse("2026-10-02T00:00:00Z")
  private val notes = Workspace(WorkspaceId("notes-id"), "notes", "/home/u/notes", AccessLevel.Read)
  private val workspace = ArgumentSpec(Operation.WORKSPACE_ARGUMENT, ArgumentType.Text, true, "The Workspace.")
  private val catalog = listOf(
    OperationSpec("read_file", "Read a file.", AccessLevel.Read, listOf(
      workspace,
      ArgumentSpec("path", ArgumentType.Text, true, "Relative to the Root."),
      ArgumentSpec("offset", ArgumentType.Integer, false, "First line."),
    )),
    OperationSpec("git_diff", "Show the diff.", AccessLevel.Read, listOf(
      workspace, ArgumentSpec("staged", ArgumentType.Flag, false, "The staged diff."),
    )),
    OperationSpec("write_file", "Write a file whole.", AccessLevel.Write, listOf(
      workspace,
      ArgumentSpec("path", ArgumentType.Text, true, "Relative to the Root."),
      ArgumentSpec(Operation.KEY_ARGUMENT, ArgumentType.Text, true, "Fresh per operation."),
    )),
  )
  private val snapshot = RuntimeEvent.Snapshot(
    workspaces = listOf(WorkspaceState(notes, false)),
    runtime = RuntimeStatus(RuntimeState.Connected, at), running = emptyList(),
    start = RuntimeStart(RuntimeStartId("first"), at), catalog = catalog,
  )

  private class Harness(snapshot: RuntimeEvent.Snapshot) {
    var state by mutableStateOf(GuiState().observed(Attachment.Attached(snapshot)))
    val sent = mutableListOf<GuiIntent>()
    fun send(intent: GuiIntent) {
      sent += intent
      state = state.after(intent)
    }
  }

  private fun ComposeUiTest.tools(): Harness {
    val gui = Harness(snapshot)
    setContent {
      ProxenosTheme(false) {
        Box(Modifier.size(1000.dp, 900.dp)) { Shell(gui.state, gui::send) }
      }
    }
    onNode(hasText("notes") and hasClickAction()).performClick()
    onNode(hasText("Tools") and SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Tab)).performClick()
    return gui
  }

  private fun ComposeUiTest.row(tool: String) = onNode(hasTestTag("tool:$tool"), useUnmergedTree = true)

  @Test
  fun `admission follows the level the stream carries`() = runComposeUiTest {
    val gui = tools()
    row("read_file").assert(hasAnyDescendant(hasText("Allowed")))
    row("read_file").assert(hasAnyDescendant(hasText("needs Read; 'notes' is at Read")))
    row("write_file").assert(hasAnyDescendant(hasText("Refused")))
    row("write_file").assert(hasAnyDescendant(hasText("so a call is refused and changes nothing", substring = true)))
    row("write_file").assert(hasAnyDescendant(hasText("fresh request_id", substring = true)))
    runOnIdle {
      gui.state = gui.state.observed(Attachment.Attached(snapshot.after(
        RuntimeEvent.Change.WorkspaceChanged(WorkspaceState(notes.copy(accessLevel = AccessLevel.None), false)))))
    }
    row("read_file").assert(hasAnyDescendant(hasText("Refused")))
    row("read_file").assert(hasAnyDescendant(hasText("at None, withheld", substring = true)))
    onNode(hasText("Try…") and hasAnyAncestor(hasTestTag("tool:read_file"))).performClick()
    onNode(hasText("at None, withheld", substring = true) and hasAnyAncestor(isDialog())).assertExists()
    onAllNodes(isRoot()).onLast().performKeyInput { pressKey(Key.Escape) }
    runOnIdle {
      gui.state = gui.state.observed(Attachment.Attached(snapshot.after(
        RuntimeEvent.Change.WorkspaceChanged(WorkspaceState(notes.copy(accessLevel = AccessLevel.Write), false)))))
    }
    row("write_file").assert(hasAnyDescendant(hasText("Allowed")))
  }

  @Test
  fun `Try asks the schema's arguments but the Workspace and request_id, and Enter submits what was given`() = runComposeUiTest {
    val gui = tools()
    onNode(hasText("Try…") and hasAnyAncestor(hasTestTag("tool:read_file"))).performClick()
    onNode(hasSetTextAction() and (hasText("path", substring = true))).assertIsFocused()
    onNode(hasSetTextAction() and (hasText("offset", substring = true))).assertExists()
    onNode(hasSetTextAction() and (hasText(Operation.WORKSPACE_ARGUMENT, substring = true))).assertDoesNotExist()
    onNode(hasText("Try") and hasClickAction()).assertIsNotEnabled()
    onNode(hasSetTextAction() and (hasText("path", substring = true))).performTextInput("a.txt")
    onAllNodes(isRoot()).onLast().performKeyInput { pressKey(Key.Enter) }
    assertEquals(GuiIntent.Try(mapOf("path" to "a.txt")), gui.sent.last())

    val words = "Tried read_file against 'notes': ok. What it returned is in Activity."
    runOnIdle {
      gui.state = gui.state.performing(ManagementAct.TryOperation(Operation.ReadFile("notes", "a.txt")))
        .tried(Tried(words, Tone.Ok, "notes", null, emptySet()))
    }
    onNodeWithText(words).assertExists()
    onNodeWithText("Show in Activity").assertIsNotEnabled()
    val entry = ActivityEntry(ActivityEntryId("tried"), at, RuntimeStartId("first"), Origin.Frontend, "notes", "read_file",
      "path=a.txt", null, ActivityOutcome.InFlight, 0, null)
    runOnIdle {
      gui.state = gui.state.observed(Attachment.Attached(gui.state.snapshot!!.after(RuntimeEvent.Change.EntryRecorded(entry))))
    }
    onNodeWithText("Show in Activity").performClick()
    assertEquals(GuiIntent.ShowTried, gui.sent.last())
    assertEquals(Destination.Activity, gui.state.destination)
    assertEquals(entry.id, gui.state.activity)
  }

  @Test
  fun `a flag is a checkbox and a key-bearing tool asks no request_id`() = runComposeUiTest {
    val gui = tools()
    onNode(hasText("Try…") and hasAnyAncestor(hasTestTag("tool:git_diff"))).performClick()
    onNode(hasText("staged", substring = true) and hasClickAction()).performClick()
    onNode(hasText("Try") and hasClickAction()).performClick()
    assertEquals(GuiIntent.Try(mapOf("staged" to "yes")), gui.sent.last())
    onAllNodes(isRoot()).onLast().performKeyInput { pressKey(Key.Escape) }
    assertNull(gui.state.trying)

    onNode(hasText("Try…") and hasAnyAncestor(hasTestTag("tool:write_file"))).performClick()
    onNode(hasSetTextAction() and (hasText(Operation.KEY_ARGUMENT, substring = true))).assertDoesNotExist()
    onNode(hasText("fresh request_id", substring = true) and hasAnyAncestor(isDialog())).assertExists()
  }
}
