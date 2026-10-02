package io.github.kzagoris.proxenos.gui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import io.github.kzagoris.proxenos.control.ConfigRefused
import io.github.kzagoris.proxenos.control.ConfigToml

fun sourceGuiScale(environment: Map<String, String>, say: (String) -> Unit): Float? {
  val scale = try {
    ConfigToml.read(environment, only = "gui_scale").number("gui_scale")
  } catch (_: ConfigRefused) {
    Float.NaN
  }
  if (scale == null || (scale.isFinite() && scale > 0)) return scale
  say("gui_scale in config.toml must be a finite positive number; using the JVM density.")
  return null
}

@Composable
fun GuiDensity(scale: Float?, content: @Composable () -> Unit) {
  val native = LocalDensity.current
  CompositionLocalProvider(LocalDensity provides Density(scale ?: native.density, native.fontScale), content = content)
}
