package io.github.kzagoris.proxenos.gui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.dp
import io.github.kzagoris.proxenos.frontend.Attachment
import io.github.kzagoris.proxenos.frontend.Reason
import kotlin.test.*

@OptIn(ExperimentalTestApi::class)
class SetupPaneTest {
  @Test
  fun `Workspace registration remains usable inside the scrolling setup page`() = runComposeUiTest {
    val at = java.time.Instant.now()
    val snapshot = io.github.kzagoris.proxenos.coreapi.RuntimeEvent.Snapshot(emptyList(),
      io.github.kzagoris.proxenos.coreapi.RuntimeStatus(io.github.kzagoris.proxenos.coreapi.RuntimeState.Connected, at), emptyList())
    setContent { ProxenosTheme(false) { Box(Modifier.size(700.dp, 760.dp)) {
      Shell(GuiState(attachment = Attachment.Attached(snapshot), setup = SetupProgress(step = SetupStep.Workspace), setupVisible = true), {})
    } } }
    onNodeWithText("Root").performTextInput("/tmp")
    onNodeWithText("Add at Read").performScrollTo().assertIsEnabled()
  }

  @Test
  fun `the welcome offers both tunnel routes and Finish later without starting or stopping anything`() = runComposeUiTest {
    val sent = mutableListOf<GuiIntent>()
    setContent { ProxenosTheme(false) { Box(Modifier.size(1000.dp, 760.dp)) {
      Shell(GuiState(attachment = Attachment.Absent(Reason.NotAnswering), setup = SetupProgress(), setupVisible = true), sent::add)
    } } }
    onNodeWithText("Set up a new tunnel").performClick()
    assertEquals(GuiIntent.SetupNavigate(SetupStep.Tunnel), sent.last())
    onNodeWithText("Use an existing tunnel").performClick()
    assertEquals(GuiIntent.SetupNavigate(SetupStep.Tunnel, 1), sent.last())
    onNodeWithText("Finish later").performClick()
    assertEquals(GuiIntent.FinishLater, sent.last())
    assertTrue(sent.none { it == GuiIntent.StartRuntime || it == GuiIntent.ConfirmStop })
  }

  @Test
  fun `credential entry hides the runtime key and saving needs both values`() = runComposeUiTest {
    val sent = mutableListOf<GuiIntent>()
    setContent { ProxenosTheme(true) { Box(Modifier.size(700.dp, 760.dp)) {
      Shell(GuiState(attachment = Attachment.Absent(Reason.NotAnswering),
        setup = SetupProgress(step = SetupStep.Tunnel, tunnelPart = 2), setupVisible = true), sent::add)
    } } }
    onNodeWithText("Save & connect").assertIsNotEnabled()
    onNodeWithText("Tunnel ID").performTextInput("tunnel_fixture")
    onNodeWithText("Runtime key").performTextInput("fixture-key")
    onNodeWithText("Save & connect").assertIsEnabled().performClick()
    val save = sent.last() as GuiIntent.SaveCredentials
    assertEquals("fixture-key", save.runtimeKey)
    assertFalse(save.toString().contains("fixture-key"))
    onNodeWithText("Show").performClick()
    onNodeWithText("Hide").assertExists()
  }
}
