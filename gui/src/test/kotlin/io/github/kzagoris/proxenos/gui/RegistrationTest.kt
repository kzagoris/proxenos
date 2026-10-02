package io.github.kzagoris.proxenos.gui

import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.v2.runComposeUiTest
import io.github.kzagoris.proxenos.coreapi.*
import io.github.kzagoris.proxenos.frontend.Attachment
import kotlinx.coroutines.CompletableDeferred
import java.time.Instant
import kotlin.test.*

@OptIn(ExperimentalTestApi::class)
class RegistrationTest {
  private val at = Instant.parse("2026-10-02T00:00:00Z")
  private val snapshot = RuntimeEvent.Snapshot(emptyList(), RuntimeStatus(RuntimeState.Connected, at), emptyList(),
    start = RuntimeStart(RuntimeStartId("first"), at))

  @Test
  fun `Choose disables a second chooser and the picked folder lands in the editable Root`() = runComposeUiTest {
    val answer = CompletableDeferred<String?>()
    val chooser = object : FolderChooser {
      override suspend fun available() = true
      override suspend fun choose(root: String): String? = answer.await()
    }
    val sent = mutableListOf<GuiIntent>()
    setContent { ProxenosTheme(false) { AddDialog(GuiState().observed(Attachment.Attached(snapshot)).copy(adding = true), sent::add, chooser) } }
    waitUntil(timeoutMillis = 5000) { onAllNodes(hasText("Choose…") and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
    onNodeWithText("Choose…").performClick().assertIsNotEnabled()
    runOnIdle { answer.complete("/tmp/selected") }
    waitUntil(timeoutMillis = 5000) { onAllNodes(hasSetTextAction() and hasText("/tmp/selected")).fetchSemanticsNodes().isNotEmpty() }
    onNode(hasSetTextAction() and hasText("/tmp/selected")).assertExists()
    waitUntil(timeoutMillis = 5000) { onAllNodes(hasText("Add at Read") and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
    onNodeWithText("Add at Read").performClick()
    assertEquals(GuiIntent.Register("/tmp/selected", null), sent.last())
  }
  @Test
  fun `cancelling the chooser leaves the typed Root unchanged`() = runComposeUiTest {
    val chooser = object : FolderChooser {
      override suspend fun available() = true
      override suspend fun choose(root: String): String? = null
    }
    setContent { ProxenosTheme(false) { AddDialog(GuiState().observed(Attachment.Attached(snapshot)).copy(adding = true), {}, chooser) } }
    onNode(hasSetTextAction() and hasText("Root")).performTextInput("/tmp/typed")
    waitUntil(timeoutMillis = 5000) { onAllNodes(hasText("Choose…") and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
    onNodeWithText("Choose…").performClick()
    onNode(hasSetTextAction() and hasText("/tmp/typed")).assertExists()
  }

  @Test
  fun `closing the dialog cancels its outstanding chooser without displaying an error`() = runComposeUiTest {
    val entered = CompletableDeferred<Unit>()
    val cancelled = CompletableDeferred<Unit>()
    var open by mutableStateOf(true)
    val chooser = object : FolderChooser {
      override suspend fun available() = true
      override suspend fun choose(root: String): String? {
        entered.complete(Unit)
        try { kotlinx.coroutines.awaitCancellation() } finally { cancelled.complete(Unit) }
      }
    }
    setContent { ProxenosTheme(false) { if (open) AddDialog(GuiState().observed(Attachment.Attached(snapshot)).copy(adding = true), {}, chooser) } }
    waitUntil(timeoutMillis = 5000) { onAllNodes(hasText("Choose…") and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
    onNodeWithText("Choose…").performClick()
    waitUntil(timeoutMillis = 5000) { entered.isCompleted }
    runOnIdle { open = false }
    waitUntil(timeoutMillis = 5000) { cancelled.isCompleted }
    onNodeWithText("Could not choose a Root", substring = true).assertDoesNotExist()
  }

  @Test
  fun `without a portal the Root field still registers a folder at Read`() = runComposeUiTest {
    val chooser = object : FolderChooser {
      override suspend fun available() = false
      override suspend fun choose(root: String): String? = error("no portal must not open a chooser")
    }
    val sent = mutableListOf<GuiIntent>()
    setContent { ProxenosTheme(false) { AddDialog(GuiState().observed(Attachment.Attached(snapshot)).copy(adding = true), sent::add, chooser) } }
    onNode(hasSetTextAction() and hasText("Root")).performTextInput("/tmp/typed")
    onNodeWithText("Choose…").assertIsNotEnabled()
    waitUntil(timeoutMillis = 5000) { onAllNodes(hasText("Add at Read") and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
    onNodeWithText("Add at Read").performClick()
    assertEquals(GuiIntent.Register("/tmp/typed", null), sent.last())
  }

  @Test
  fun `overlap warning names every overlapping Workspace before Add anyway at Read`() = runComposeUiTest {
    val parent = Workspace(WorkspaceId("parent"), "parent", "/tmp/overlap", AccessLevel.Write)
    val inner = Workspace(WorkspaceId("inner"), "inner", "/tmp/overlap/inner", AccessLevel.None)
    val state = GuiState().observed(Attachment.Attached(snapshot.copy(workspaces = listOf(WorkspaceState(parent, false), WorkspaceState(inner, false))))).copy(adding = true)
    val sent = mutableListOf<GuiIntent>()
    setContent { ProxenosTheme(false) { AddDialog(state, sent::add) } }
    onNode(hasSetTextAction() and hasText("Root")).performTextInput("/tmp/overlap")
    waitUntil(timeoutMillis = 5000) { onAllNodes(hasText("Add anyway, at Read") and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
    onNodeWithText("Root of 'parent'", substring = true).assertExists()
    onNodeWithText("contains 'inner'", substring = true).assertExists()
    onNodeWithText("no union and no intersection", substring = true).assertExists()
    onNodeWithText("Add anyway, at Read").performClick()
    assertEquals(GuiIntent.Register("/tmp/overlap", null), sent.last())
  }

}
