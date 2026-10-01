package io.github.kzagoris.proxenos.gui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.*
import androidx.compose.ui.test.v2.runComposeUiTest
import kotlin.test.*

@OptIn(ExperimentalTestApi::class)
class RuntimeWindowTest {
  @Test
  fun `Stop confirmation focuses Cancel and Escape dismisses without stopping`() = runComposeUiTest {
    var state by mutableStateOf(GuiState(stopConfirmation = true))
    val intents = mutableListOf<GuiIntent>()
    setContent {
      MaterialTheme {
        RuntimeWindow(state) { intent ->
          intents += intent
          if (intent == GuiIntent.CancelStop) state = state.copy(stopConfirmation = false)
        }
      }
    }
    onNodeWithText("Cancel").assertIsFocused()
    onNodeWithText("Cancel").performKeyInput { pressKey(Key.Enter) }
    onNodeWithText("Stop the Runtime?").assertDoesNotExist()
    assertEquals(listOf<GuiIntent>(GuiIntent.CancelStop), intents)
    runOnIdle { state = state.copy(stopConfirmation = true); intents.clear() }
    onNodeWithText("Cancel").performKeyInput { pressKey(Key.Escape) }
    onNodeWithText("Stop the Runtime?").assertDoesNotExist()
    assertEquals(listOf<GuiIntent>(GuiIntent.CancelStop), intents)
  }
}
