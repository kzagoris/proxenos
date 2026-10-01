package io.github.kzagoris.proxenos.gui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.dp
import io.github.kzagoris.proxenos.coreapi.*
import io.github.kzagoris.proxenos.frontend.Attachment
import io.github.kzagoris.proxenos.frontend.Reason
import java.time.Instant
import kotlin.test.*

/**
 * GUI-SPEC §5 for the shell, through the composed window. The shortcut handler is the one the
 * Window installs; here it sits on the content's root because the test scene has no Window.
 */
@OptIn(ExperimentalTestApi::class)
class ShellTest {
  private val at = Instant.parse("2026-10-01T00:00:00Z")

  private fun snapshot(tunnel: RuntimeState = RuntimeState.Connecting, unconfirmed: Boolean = false) = RuntimeEvent.Snapshot(
    workspaces = listOf(WorkspaceState(Workspace(WorkspaceId("w1"), "notes", "/home/u/notes", AccessLevel.Read), broken = false)),
    runtime = RuntimeStatus(tunnel, at), running = emptyList(), start = RuntimeStart(RuntimeStartId("s1"), at),
    connectorUnconfirmed = unconfirmed,
  )

  private class Harness(initial: GuiState) {
    var state by mutableStateOf(initial)
    val sent = mutableListOf<GuiIntent>()
    var closed = 0
    fun send(intent: GuiIntent) {
      sent += intent
      state = state.after(intent)
    }
  }

  private fun ComposeUiTest.shell(initial: GuiState, width: Int = 1000): Harness {
    val harness = Harness(initial)
    setContent {
      ProxenosTheme(dark = false) {
        Box(Modifier.size(width.dp, 700.dp).onPreviewKeyEvent { shortcut(it, harness.state, harness::send) { harness.closed++ } }) {
          Shell(harness.state, harness::send)
        }
      }
    }
    return harness
  }

  private fun attached(tunnel: RuntimeState = RuntimeState.Connecting, unconfirmed: Boolean = false) =
    GuiState().observed(Attachment.Attached(snapshot(tunnel, unconfirmed)))

  private fun ComposeUiTest.chord(modifier: Key, key: Key) = onRoot().performKeyInput {
    keyDown(modifier)
    keyDown(key)
    keyUp(key)
    keyUp(modifier)
  }

  @Test
  fun `Ctrl+1, 2, 3, N and W each act once per press`() = runComposeUiTest {
    val gui = shell(attached())
    onNode(hasText("Workspaces") and hasClickAction()).requestFocus()
    chord(Key.CtrlLeft, Key.Two)
    assertEquals(Destination.Activity, gui.state.destination)
    chord(Key.CtrlLeft, Key.Three)
    assertEquals(Destination.Connection, gui.state.destination)
    chord(Key.CtrlLeft, Key.One)
    assertEquals(Destination.Workspaces, gui.state.destination)
    gui.sent.clear()
    chord(Key.CtrlLeft, Key.N)
    assertEquals(listOf<GuiIntent>(GuiIntent.AddWorkspace), gui.sent)
    onNodeWithText("Root").assertIsDisplayed()
    onAllNodes(isRoot()).onLast().performKeyInput { pressKey(Key.Escape) }
    onNode(hasText("Workspaces") and hasClickAction()).requestFocus()
    chord(Key.CtrlLeft, Key.W)
    assertEquals(1, gui.closed)
  }

  @Test
  fun `Tab moves focus in order and focus is drawn`() = runComposeUiTest {
    shell(attached())
    val add = onNode(hasText("Add Workspace") and hasClickAction())
    val unfocused = add.captureToImage().toPixelMap().let { map -> (0 until map.width).map { map[it, 0] } }
    add.requestFocus()
    add.assertIsFocused()
    val focused = add.captureToImage().toPixelMap().let { map -> (0 until map.width).map { map[it, 0] } }
    assertNotEquals(unfocused, focused, "a focused button must look different from an unfocused one")
    onRoot().performKeyInput { pressKey(Key.Tab) }
    onNode(hasText("Workspaces") and hasClickAction()).assertIsFocused()
    onRoot().performKeyInput { pressKey(Key.Tab) }
    onNode(hasText("Activity") and hasClickAction()).assertIsFocused()
    onRoot().performKeyInput { keyDown(Key.ShiftLeft); pressKey(Key.Tab); keyUp(Key.ShiftLeft) }
    onNode(hasText("Workspaces") and hasClickAction()).assertIsFocused()
  }

  @Test
  fun `the Stop confirmation starts on Cancel, Enter there cancels, and it handles its own Escape`() = runComposeUiTest {
    val gui = shell(attached().after(GuiIntent.AskStop))
    onNodeWithText("Cancel").assertIsFocused()
    onAllNodes(isRoot()).onLast().performKeyInput { pressKey(Key.Enter) }
    onNodeWithText("Stop the Runtime?").assertDoesNotExist()
    assertFalse(GuiIntent.ConfirmStop in gui.sent)
    runOnIdle { gui.state = gui.state.after(GuiIntent.AskStop) }
    onAllNodes(isRoot()).onLast().performKeyInput { pressKey(Key.Escape) }
    onNodeWithText("Stop the Runtime?").assertDoesNotExist()
    assertEquals(Destination.Workspaces, gui.state.destination)
    assertFalse(GuiIntent.ConfirmStop in gui.sent)
  }

  @Test
  fun `a dialog traps focus and leaves the window's shortcuts alone`() = runComposeUiTest {
    val gui = shell(attached().after(GuiIntent.AddWorkspace))
    val inside = listOf(hasText("Root"), hasText("Name"), hasText("Cancel"), hasText("Add at Read"))
    val dialog = onAllNodes(isRoot()).onLast()
    repeat(9) {
      dialog.performKeyInput { pressKey(Key.Tab) }
      val focused = onAllNodes(isFocused()).fetchSemanticsNodes()
      assertTrue(focused.isNotEmpty(), "focus left the dialog after ${it + 1} Tabs")
      assertTrue(focused.all { node -> inside.any { matcher -> matcher.matches(node) } },
        "focus left the dialog after ${it + 1} Tabs")
    }
    dialog.performKeyInput { keyDown(Key.CtrlLeft); pressKey(Key.Two); keyUp(Key.CtrlLeft) }
    assertEquals(Destination.Workspaces, gui.state.destination)
    dialog.performKeyInput { pressKey(Key.Escape) }
    assertFalse(gui.state.adding)
    onNodeWithText("Root").assertDoesNotExist()
  }

  @Test
  fun `Enter in the Root field submits the Workspace typed there`() = runComposeUiTest {
    val gui = shell(attached().after(GuiIntent.AddWorkspace))
    onNodeWithText("Root").performTextInput("/tmp/plans")
    onAllNodes(isRoot()).onLast().performKeyInput { pressKey(Key.Enter) }
    assertEquals(GuiIntent.Register("/tmp/plans", null), gui.sent.last())
  }

  @Test
  fun `the compact window is one pane with a bottom bar, and Esc backs out of a stage`() = runComposeUiTest {
    val gui = shell(attached().after(GuiIntent.Show(Destination.Connection)), width = 480)
    // The sidebar is gone: Connection is the bottom bar's one item, and Add Workspace sits in the header.
    onAllNodes(hasText("Connection") and hasClickAction()).assertCountEquals(1)
    onAllNodes(hasText("Tunnel") and hasClickAction()).onLast().performClick()
    assertEquals(Stage.Tunnel, gui.state.stage)
    onNodeWithText("Back").assertExists()
    onNodeWithText("Back").requestFocus()
    onRoot().performKeyInput { pressKey(Key.Escape) }
    assertNull(gui.state.stage)
    onNodeWithText("Back").assertDoesNotExist()
  }

  @Test
  fun `while not Attached the stages below read Can't tell and nothing from before stays`() = runComposeUiTest {
    val gui = shell(attached())
    onNodeWithText("notes").assertExists()
    runOnIdle { gui.state = gui.state.observed(Attachment.Absent(Reason.NotAnswering)) }
    onNodeWithText("notes").assertDoesNotExist()
    onNodeWithText("The Runtime is not running").assertExists()
    onAllNodesWithText("Can't tell").assertCountEquals(2)
    onNodeWithText("Connected", substring = false).assertDoesNotExist()
    onNodeWithText("Start Runtime").assertHasClickAction().performClick()
    assertTrue(GuiIntent.StartRuntime in gui.sent)
  }

  @Test
  fun `Attached is this window's link and never reads as Connected`() = runComposeUiTest {
    shell(attached(tunnel = RuntimeState.Connecting))
    onNodeWithText("Attached").assertExists()
    onNodeWithText("Connecting").assertExists()
    onAllNodesWithText("Connected").assertCountEquals(0)
  }

  @Test
  fun `a Failed tunnel banners the other destinations with Review, and Unconfirmed alone does not`() = runComposeUiTest {
    val gui = shell(attached(tunnel = RuntimeState.Failed(null)))
    onNodeWithText("Review").assertExists()
    onNodeWithText("Review").performClick()
    assertEquals(Destination.Connection, gui.state.destination)
    assertEquals(Stage.Tunnel, gui.state.stage)
    onNodeWithText("Review").assertDoesNotExist()
    runOnIdle { gui.state = attached(tunnel = RuntimeState.Connected, unconfirmed = true) }
    onNodeWithText("Review").assertDoesNotExist()
    onAllNodesWithText("!").assertCountEquals(1)
  }

  @Test
  fun `arrow keys, Home and End move through the stages and selection follows`() = runComposeUiTest {
    val gui = shell(attached().after(GuiIntent.Show(Destination.Connection)))
    onAllNodes(hasText("Runtime") and hasClickAction()).onLast().requestFocus()
    onRoot().performKeyInput { pressKey(Key.DirectionDown) }
    assertEquals(Stage.Tunnel, gui.state.stage)
    onRoot().performKeyInput { pressKey(Key.MoveEnd) }
    assertEquals(Stage.Connector, gui.state.stage)
    onAllNodes(hasText("Connector") and hasClickAction()).onLast().assertIsFocused()
    onRoot().performKeyInput { pressKey(Key.MoveHome) }
    assertEquals(Stage.Runtime, gui.state.stage)
  }

  @Test
  fun `a light-dark flip keeps the focus ring on what has focus`() = runComposeUiTest {
    var dark by mutableStateOf(false)
    val gui = Harness(attached())
    setContent {
      ProxenosTheme(dark) { Box(Modifier.size(1000.dp, 700.dp)) { Shell(gui.state, gui::send) } }
    }
    val row = onNode(hasText("Activity") and hasClickAction())
    fun pixels() = row.captureToImage().toPixelMap().let { map -> (0 until map.width).map { map[it, map.height / 2] } }
    row.requestFocus()
    runOnIdle { dark = true }
    val focused = pixels()
    onNode(hasText("Workspaces") and hasClickAction()).requestFocus()
    assertNotEquals(pixels(), focused, "the focused row lost its ring when the scheme flipped")
  }
}
