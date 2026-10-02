package io.github.kzagoris.proxenos.gui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.io.TempDir
import kotlin.test.*

@OptIn(ExperimentalTestApi::class)
class GuiScaleTest {
  @TempDir lateinit var home: Path
  private val environment get() = mapOf("XDG_CONFIG_HOME" to home.toString())

  private fun config(text: String) {
    val directory = Files.createDirectories(home.resolve("proxenos"))
    Files.writeString(directory.resolve("config.toml"), text)
  }

  @Test
  fun `unset follows the native density and a numeric setting is absolute`() {
    val words = mutableListOf<String>()
    assertNull(sourceGuiScale(environment, words::add))
    for ((value, expected) in listOf("1.5" to 1.5f, "2" to 2f, "1.5e0" to 1.5f)) {
      config("gui_scale = $value # px per dp\ncommand_budget_seconds = true")
      assertEquals(expected, sourceGuiScale(environment, words::add))
    }
    assertEquals(emptyList(), words)
  }

  @Test
  fun `an invalid density falls back with one sentence`() {
    for (value in listOf("0", "-1.5", "nan", "inf", "1e100", "1e-100", "true", "\"1.5\"", "1.5.2", "")) {
      config("gui_scale = $value")
      val words = mutableListOf<String>()
      assertNull(sourceGuiScale(environment, words::add), value)
      assertEquals(1, words.size, value)
      assertContains(words.single(), "gui_scale")
      assertContains(words.single(), "JVM density")
      assertFalse('\n' in words.single())
    }
  }

  @Test
  fun `a narrower scaled window provides Back to leave its single stage pane`() {
    for (scale in listOf(1.5f, 2f)) runComposeUiTest {
      setContent {
        CompositionLocalProvider(LocalDensity provides Density(1f)) {
          Box(Modifier.size(960.dp, 700.dp)) {
            GuiDensity(scale) {
              ProxenosTheme(false) {
                Shell(GuiState().after(GuiIntent.ShowStage(Stage.Runtime)), {})
              }
            }
          }
        }
      }
      if (scale == 1.5f) onNodeWithText("Back").assertDoesNotExist()
      else onNodeWithText("Back").assertIsDisplayed().assertHasClickAction()
    }
  }

  @Test
  fun `density scales the drawn box and its hit target while keeping fontScale`() {
    for ((scale, pixels) in listOf(null to 200, 1.5f to 150, 2f to 200)) runComposeUiTest {
      var clicks = 0
      var fontScale = 0f
      setContent {
        CompositionLocalProvider(LocalDensity provides Density(2f, 1.25f)) {
          GuiDensity(scale) {
            fontScale = LocalDensity.current.fontScale
            Box(Modifier.size(100.dp).testTag("target").clickable { clicks++ })
          }
        }
      }
      val target = onNodeWithTag("target")
      val image = target.captureToImage()
      assertEquals(pixels, image.width)
      assertEquals(pixels, image.height)
      assertEquals(1.25f, fontScale)
      target.performClick()
      assertEquals(1, clicks)
    }
  }
}
