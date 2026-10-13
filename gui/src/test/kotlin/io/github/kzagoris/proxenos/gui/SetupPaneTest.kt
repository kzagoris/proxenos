package io.github.kzagoris.proxenos.gui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.text.AnnotatedString
import io.github.kzagoris.proxenos.frontend.Attachment
import io.github.kzagoris.proxenos.frontend.Reason
import io.github.kzagoris.proxenos.coreapi.*
import kotlin.test.*

@OptIn(ExperimentalTestApi::class)
class SetupPaneTest {
  @Test
  fun `saved credential action shows hover and keyboard focus in both themes`() = runComposeUiTest {
    val dark = mutableStateOf(false)
    setContent { ProxenosTheme(dark.value) { Box(Modifier.size(700.dp, 760.dp)) {
      Shell(GuiState(attachment = Attachment.Absent(Reason.NotAnswering), setupVisible = true,
        setup = SetupProgress(step = SetupStep.Tunnel, tunnelPart = 2),
        setupSavedTunnelId = "tunnel_saved", setupTunnelId = "tunnel_saved"), {})
    } } }
    val action = onNodeWithText("Change credentials").performScrollTo()
    fun pixels() = action.captureToImage().toPixelMap().let { map ->
      (0 until map.width).map { map[it, map.height / 2] }
    }
    for (scheme in listOf(false, true)) {
      runOnIdle { dark.value = scheme }
      val resting = pixels()
      action.performMouseInput { enter(center) }
      assertNotEquals(resting, pixels(), "hover must visibly identify the action (dark=$scheme)")
      action.performMouseInput { exit() }
      assertEquals(resting, pixels(), "leaving must remove the hover highlight (dark=$scheme)")
    }
    val resting = pixels()
    action.requestFocus().assertIsFocused()
    assertNotEquals(resting, pixels(), "keyboard focus must be visible on the text action")
    val focused = pixels()
    runOnIdle { dark.value = false }
    action.assertIsFocused()
    assertNotEquals(focused, pixels(), "the focused action must repaint with the theme")
  }

  @Test
  fun `selecting a different tunnel requires saving its key before showing it as saved`() = runComposeUiTest {
    val at = java.time.Instant.now()
    val snapshot = RuntimeEvent.Snapshot(emptyList(), RuntimeStatus(RuntimeState.Connected, at), emptyList())
    setContent { ProxenosTheme(false) { Box(Modifier.size(700.dp, 760.dp)) {
      Shell(GuiState(attachment = Attachment.Attached(snapshot), setupVisible = true,
        setup = SetupProgress(step = SetupStep.Tunnel, tunnelPart = 2),
        setupSavedTunnelId = "tunnel_original", setupTunnelId = "tunnel_draft"), {})
    } } }
    onNodeWithText("Credentials saved on this machine").assertDoesNotExist()
    onNodeWithText("Choose a Workspace").assertDoesNotExist()
    onNodeWithText("Tunnel ID").performScrollTo().assertTextContains("tunnel_draft")
    onNodeWithText("Runtime key").performScrollTo().assertExists()
    onNodeWithText("Start with saved credentials").assertDoesNotExist()
  }

  @Test
  fun `saving replaces even an edited saved-key form with a summary and the measured next action`() = runComposeUiTest {
    val shown = mutableStateOf(GuiState(attachment = Attachment.Absent(Reason.NotAnswering),
      setup = SetupProgress(step = SetupStep.Tunnel, tunnelPart = 2), setupVisible = true))
    val saves = mutableListOf<GuiIntent.SaveCredentials>()
    val at = java.time.Instant.now()
    val connecting = RuntimeEvent.Snapshot(emptyList(), RuntimeStatus(RuntimeState.Connecting, at), emptyList(),
      start = RuntimeStart(RuntimeStartId("first"), at))
    setContent { ProxenosTheme(false) { Box(Modifier.size(700.dp, 760.dp)) {
      Shell(shown.value, { intent ->
        if (intent is GuiIntent.SaveCredentials) {
          saves += intent
          shown.value = shown.value.copy(setupSavedTunnelId = intent.tunnelId, setupTunnelId = intent.tunnelId,
            setupCredentialRevision = shown.value.setupCredentialRevision + 1, attachment = Attachment.Starting)
          if (saves.size == 2) shown.value = shown.value.observed(Attachment.Attached(connecting.copy(
            start = RuntimeStart(RuntimeStartId("second"), at))))
        }
      })
    } } }
    onNodeWithText("Tunnel ID").performScrollTo().performTextInput("tunnel_saved")
    onNodeWithText("Runtime key").performScrollTo().performTextInput("first-fixture-key")
    onNodeWithText("Save & connect").performScrollTo().performClick()
    onNodeWithText("Credentials saved on this machine").assertIsDisplayed()
    onNodeWithText("Runtime key").assertDoesNotExist()
    onNodeWithText("Choose a Workspace").assertDoesNotExist()
    onNodeWithText("Change credentials").assertIsNotEnabled()
    runOnIdle {
      shown.value = shown.value.observed(Attachment.Attached(connecting))
        .observed(Attachment.Absent(Reason.StartFailed("fixture startup refusal")))
    }
    onNodeWithText("Change credentials").performScrollTo().performClick()
    onNodeWithText("Runtime key").performScrollTo().assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString("")))
    onNodeWithText("Runtime key").performTextInput("replacement-fixture-key")
    onNodeWithText("Save & connect").performScrollTo().performClick()
    onNodeWithText("Runtime key").assertDoesNotExist()
    assertEquals(listOf("first-fixture-key", "replacement-fixture-key"), saves.map { it.runtimeKey })
    runOnIdle {
      val at = java.time.Instant.now()
      val snapshot = io.github.kzagoris.proxenos.coreapi.RuntimeEvent.Snapshot(emptyList(),
        io.github.kzagoris.proxenos.coreapi.RuntimeStatus(io.github.kzagoris.proxenos.coreapi.RuntimeState.Connected, at), emptyList())
      shown.value = shown.value.copy(attachment = Attachment.Attached(snapshot))
    }
    onNodeWithText("Choose a Workspace").performScrollTo().assertIsEnabled()
    onNodeWithText("Save & connect").assertDoesNotExist()
  }

  @Test
  fun `a refused credential save leaves the typed key available for correction`() = runComposeUiTest {
    val shown = mutableStateOf(GuiState(attachment = Attachment.Absent(Reason.NotAnswering),
      setup = SetupProgress(step = SetupStep.Tunnel, tunnelPart = 2), setupVisible = true))
    val saves = mutableListOf<GuiIntent.SaveCredentials>()
    setContent { ProxenosTheme(false) { Box(Modifier.size(700.dp, 760.dp)) {
      Shell(shown.value, { intent ->
        if (intent is GuiIntent.SaveCredentials) {
          saves += intent
          shown.value = shown.value.copy(setupError = "fixture save refusal")
        }
      })
    } } }
    onNodeWithText("Tunnel ID").performScrollTo().performTextInput("tunnel_fixture")
    onNodeWithText("Runtime key").performScrollTo().performTextInput("fixture-key")
    onNodeWithText("Save & connect").performScrollTo().performClick()
    onNodeWithText("Credentials saved on this machine").assertDoesNotExist()
    onNodeWithText("Save & connect").performScrollTo().assertIsEnabled().performClick()
    assertEquals(listOf("fixture-key", "fixture-key"), saves.map { it.runtimeKey })
  }

  @Test
  fun `saved credentials stay visibly saved after startup refusal until Change credentials is chosen`() = runComposeUiTest {
    val sent = mutableListOf<GuiIntent>()
    setContent { ProxenosTheme(false) { Box(Modifier.size(700.dp, 760.dp)) {
      Shell(GuiState(attachment = Attachment.Absent(Reason.StartFailed("fixture startup refusal")),
        setup = SetupProgress(step = SetupStep.Tunnel, tunnelPart = 2), setupVisible = true,
        setupSavedTunnelId = "tunnel_saved", setupTunnelId = "tunnel_saved"), sent::add)
    } } }
    onNodeWithText("Credentials saved on this machine").assertExists()
    onNodeWithText("Runtime key").assertDoesNotExist()
    onNodeWithText("Save & connect").assertDoesNotExist()
    onNodeWithText("Start with saved credentials").performScrollTo().performClick()
    assertEquals(GuiIntent.StartRuntime, sent.last())
    onNodeWithText("Change credentials").performScrollTo().performClick()
    onNodeWithText("Tunnel ID").performScrollTo().assertExists()
    onNodeWithText("Runtime key").performScrollTo().assertExists()
    onNodeWithText("Save & connect").performScrollTo().assertIsNotEnabled()
  }

  @Test
  fun `an existing tunnel ID is carried into connection after association confirmation`() = runComposeUiTest {
    val sent = mutableListOf<GuiIntent>()
    setContent { ProxenosTheme(false) { Box(Modifier.size(700.dp, 760.dp)) {
      Shell(GuiState(attachment = Attachment.Absent(Reason.NotAnswering),
        setup = SetupProgress(step = SetupStep.Tunnel, tunnelPart = 1, associated = true), setupVisible = true), sent::add)
    } } }
    onNodeWithText("Continue").performScrollTo().assertIsNotEnabled()
    onNodeWithText("Tunnel ID").performScrollTo().performTextInput("tunnel_existing")
    onNodeWithText("Continue").performScrollTo().assertIsEnabled().performClick()
    assertEquals(GuiIntent.SetupNavigate(SetupStep.Tunnel, 2, "tunnel_existing"), sent.last())
  }

  @Test
  fun `key guidance opens Runtime API keys and preserves unsaved credentials`() = runComposeUiTest {
    val opened = mutableListOf<String>()
    val sent = mutableListOf<GuiIntent>()
    val handler = object : UriHandler { override fun openUri(uri: String) { opened += uri } }
    setContent { CompositionLocalProvider(LocalUriHandler provides handler) {
      ProxenosTheme(true) { Box(Modifier.size(700.dp, 760.dp)) {
        Shell(GuiState(attachment = Attachment.Absent(Reason.NotAnswering),
          setup = SetupProgress(step = SetupStep.Tunnel, tunnelPart = 2), setupVisible = true), sent::add)
      } }
    } }
    onNodeWithText("Tunnel ID").performScrollTo().performTextInput("tunnel_existing")
    onNodeWithText("Runtime key").performScrollTo().performTextInput("fixture-key")
    onNodeWithText("Show runtime key guide").performScrollTo().performClick()
    onNodeWithText("Open Runtime API keys ↗").performScrollTo().performClick()
    assertEquals(listOf("https://platform.openai.com/settings/organization/api-keys"), opened)
    onNodeWithText("Hide runtime key guide").performScrollTo().performClick()
    onNodeWithText("Runtime key").assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Password))
    onNodeWithText("Save & connect").performScrollTo().performClick()
    val save = sent.last() as GuiIntent.SaveCredentials
    assertEquals("tunnel_existing", save.tunnelId)
    assertEquals("fixture-key", save.runtimeKey)
  }

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
  fun `a reached step the owner would refuse is disabled with its reason while an open one navigates`() = runComposeUiTest {
    val sent = mutableListOf<GuiIntent>()
    setContent { ProxenosTheme(false) { Box(Modifier.size(1000.dp, 760.dp)) {
      Shell(GuiState(attachment = Attachment.Absent(Reason.NotAnswering), setupVisible = true,
        setup = SetupProgress(step = SetupStep.TryIt, furthest = SetupStep.TryIt, tunnelPart = 2)), sent::add)
    } } }
    onNodeWithText("ChatGPT").assertIsNotEnabled()
    onNodeWithText("Workspace").assertIsNotEnabled()
    onNodeWithText("Needs the Runtime running").assertExists()
    onNodeWithText("Needs Tunnel · Connected").assertExists()
    onNodeWithText("Back").performScrollTo().assertIsNotEnabled()
    // The stage strip has a Tunnel button too; the rail's step is the one whose whole text is the name.
    onNode(SemanticsMatcher.expectValue(SemanticsProperties.Text, listOf(AnnotatedString("Tunnel")))).performClick()
    assertEquals(GuiIntent.SetupNavigate(SetupStep.Tunnel, 2), sent.last())
  }

  @Test
  fun `credential entry hides the runtime key and saving needs both values`() = runComposeUiTest {
    val sent = mutableListOf<GuiIntent>()
    setContent { ProxenosTheme(true) { Box(Modifier.size(700.dp, 760.dp)) {
      Shell(GuiState(attachment = Attachment.Absent(Reason.NotAnswering),
        setup = SetupProgress(step = SetupStep.Tunnel, tunnelPart = 2), setupVisible = true), sent::add)
    } } }
    onNodeWithText("Save & connect").performScrollTo().assertIsNotEnabled()
    onNodeWithText("Tunnel ID").performScrollTo().performTextInput("tunnel_fixture")
    onNodeWithText("Runtime key").performScrollTo().performTextInput("fixture-key")
    onNodeWithText("Save & connect").performScrollTo().assertIsEnabled().performClick()
    val save = sent.last() as GuiIntent.SaveCredentials
    assertEquals("fixture-key", save.runtimeKey)
    assertFalse(save.toString().contains("fixture-key"))
    onNodeWithText("Show").performScrollTo().performClick()
    onNodeWithText("Hide").assertExists()
  }
}
