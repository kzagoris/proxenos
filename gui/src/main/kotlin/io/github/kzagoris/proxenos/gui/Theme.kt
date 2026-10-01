package io.github.kzagoris.proxenos.gui

import androidx.compose.foundation.IndicationNodeFactory
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.interaction.FocusInteraction
import androidx.compose.foundation.interaction.HoverInteraction
import androidx.compose.foundation.interaction.Interaction
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalRippleConfiguration
import androidx.compose.material3.LocalTonalElevationEnabled
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.node.DelegatableNode
import androidx.compose.ui.node.CompositionLocalConsumerModifierNode
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.node.ObserverModifierNode
import androidx.compose.ui.node.currentValueOf
import androidx.compose.ui.node.observeReads
import androidx.compose.ui.node.invalidateDraw
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.platform.SystemFont
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.launch

// GUI-SPEC §6, "C+1 · Sidebar": neutral zinc greys and one blue accent. Not the Omarchy theme,
// whose "red" can be a green.
private val light = lightColorScheme(
  primary = Color(0xFF2563EB), onPrimary = Color.White, primaryContainer = Color(0xFFDBEAFE), onPrimaryContainer = Color(0xFF1E3A8A),
  secondary = Color(0xFF52525B), secondaryContainer = Color(0xFFE4E4E7), onSecondaryContainer = Color(0xFF18181B),
  background = Color(0xFFFAFAFA), onBackground = Color(0xFF18181B), surface = Color(0xFFFFFFFF), onSurface = Color(0xFF18181B),
  surfaceVariant = Color(0xFFF4F4F5), onSurfaceVariant = Color(0xFF52525B), outline = Color(0xFFD4D4D8), outlineVariant = Color(0xFFE4E4E7),
  surfaceContainerLowest = Color.White, surfaceContainerLow = Color(0xFFFAFAFA), surfaceContainer = Color(0xFFF4F4F5),
  surfaceContainerHigh = Color(0xFFEDEDEF), surfaceContainerHighest = Color(0xFFE4E4E7),
  error = Color(0xFFDC2626), errorContainer = Color(0xFFFEE2E2), onErrorContainer = Color(0xFF7F1D1D),
)
private val dark = darkColorScheme(
  primary = Color(0xFF60A5FA), onPrimary = Color(0xFF0B1B33), primaryContainer = Color(0xFF1E3A5F), onPrimaryContainer = Color(0xFFDBEAFE),
  secondary = Color(0xFFA1A1AA), secondaryContainer = Color(0xFF2F2F33), onSecondaryContainer = Color(0xFFE4E4E7),
  background = Color(0xFF18181B), onBackground = Color(0xFFE4E4E7), surface = Color(0xFF1C1C1F), onSurface = Color(0xFFE4E4E7),
  surfaceVariant = Color(0xFF27272A), onSurfaceVariant = Color(0xFFA1A1AA), outline = Color(0xFF3F3F46), outlineVariant = Color(0xFF2E2E33),
  surfaceContainerLowest = Color(0xFF141416), surfaceContainerLow = Color(0xFF18181B), surfaceContainer = Color(0xFF202023),
  surfaceContainerHigh = Color(0xFF27272A), surfaceContainerHighest = Color(0xFF2F2F33),
  error = Color(0xFFF87171), errorContainer = Color(0xFF450A0A), onErrorContainer = Color(0xFFFECACA),
)

/**
 * What Material's roles do not carry: a status colour that means the same whatever the theme.
 * Only light and dark shift the shade. A colour never stands alone — every use carries a word and
 * an icon too.
 */
@Immutable
class Status(val ok: Color, val warn: Color, val bad: Color, val info: Color, val none: Color)

private val statusLight = Status(Color(0xFF16A34A), Color(0xFFD97706), Color(0xFFDC2626), Color(0xFF2563EB), Color(0xFF71717A))
private val statusDark = Status(Color(0xFF4ADE80), Color(0xFFFBBF24), Color(0xFFF87171), Color(0xFF60A5FA), Color(0xFFA1A1AA))

val LocalStatus = staticCompositionLocalOf<Status> { error("ProxenosTheme is not in the composition") }

/** Which status colour a reading takes: ok green, warn amber, bad red, info blue, none grey. */
enum class Tone { Ok, Warn, Bad, Info, None }

fun Status.of(tone: Tone): Color = when (tone) {
  Tone.Ok -> ok
  Tone.Warn -> warn
  Tone.Bad -> bad
  Tone.Info -> info
  Tone.None -> none
}

/** GUI-SPEC §6's density, in one place. */
object Look {
  val row = 32.dp
  val icon = 17.dp
  val pad = 12.dp
  val gap = 6.dp
  val corner = 6.dp
  val sidebar = 228.dp
  val header = 44.dp
  /** Under this scaled width the window is one pane with a bottom bar (GUI-SPEC §4.1). */
  val compact = 600.dp
}

/** The desktop's UI font and its Mono sibling, by the names the desktop uses for them. */
class Fonts(val sans: FontFamily, val mono: FontFamily) {
  companion object {
    val Default = Fonts(FontFamily.Default, FontFamily.Monospace)

    /**
     * [name] through `SystemFont`. A family that is not installed is not an error: Skia falls
     * back silently, which is why the caller logs the name asked for.
     */
    @OptIn(ExperimentalTextApi::class)
    fun named(name: String?): Fonts {
      if (name.isNullOrBlank()) return Default
      fun family(of: String) = FontFamily(
        SystemFont(of, FontWeight.Normal), SystemFont(of, FontWeight.Medium), SystemFont(of, FontWeight.SemiBold),
      )
      val mono = if ("Sans" in name) family(name.replace("Sans", "Mono")) else FontFamily.Monospace
      return Fonts(family(name), mono)
    }

    /**
     * The desktop's UI font name: `gsettings` prints `'Adwaita Sans 11'`, and the trailing number
     * is the size, not part of the name. Null where there is no answer within a second.
     */
    fun desktopName(): String? = try {
      val process = ProcessBuilder("gsettings", "get", "org.gnome.desktop.interface", "font-name")
        .redirectErrorStream(true).start()
      if (!process.waitFor(1, TimeUnit.SECONDS)) {
        process.destroyForcibly()
        null
      } else if (process.exitValue() != 0) null
      else process.inputStream.bufferedReader().readText().trim().trim('\'')
        .replace(Regex("\\s+\\d+(\\.\\d+)?$"), "").ifBlank { null }
    } catch (_: java.io.IOException) {
      null
    }
  }
}

private fun typography(family: FontFamily): Typography {
  fun style(size: Int, weight: FontWeight = FontWeight.Normal) =
    TextStyle(fontFamily = family, fontSize = size.sp, lineHeight = (size * 1.4).sp, fontWeight = weight)
  val body = style(14)
  val title = style(15, FontWeight.Medium)
  val headline = style(18, FontWeight.SemiBold)
  val label = style(13, FontWeight.Medium)
  return Typography(
    headlineLarge = headline, headlineMedium = headline, headlineSmall = headline,
    titleLarge = title, titleMedium = title, titleSmall = title,
    bodyLarge = body, bodyMedium = body, bodySmall = style(13),
    labelLarge = label, labelMedium = label, labelSmall = style(12, FontWeight.Medium),
  )
}

/** The colours [Marks] draws with: the theme's, read when drawn rather than captured. */
@Immutable
private class MarkColours(val ring: Color, val inner: Color, val wash: Color)

private val LocalMarkColours = staticCompositionLocalOf { MarkColours(Color.Blue, Color.White, Color.Black) }

/**
 * Focus, hover and press drawn without a ripple. Material's ripple is switched off (GUI-SPEC §6),
 * and with it went the only focus cue Material's own buttons draw — so every clickable this GUI
 * draws takes this through [LocalIndication], and focus stays visible (§5).
 *
 * One factory for every theme: a factory that changed with the scheme would replace each node on a
 * light/dark flip, and the new node never hears the Focus that came before it, so the element
 * that has focus would lose its ring. The node reads the colours instead, and redraws when they change.
 */
private object Marks : IndicationNodeFactory {
  override fun create(interactionSource: InteractionSource): DelegatableNode = MarksNode(interactionSource)
  override fun equals(other: Any?) = other === this
  override fun hashCode() = 0
}

private class MarksNode(private val source: InteractionSource) :
  Modifier.Node(), DrawModifierNode, CompositionLocalConsumerModifierNode, ObserverModifierNode {
  private var focused = 0
  private var hovered = 0
  private var pressed = 0
  private lateinit var colours: MarkColours

  override fun onAttach() {
    onObservedReadsChanged()
    coroutineScope.launch {
      source.interactions.collect { interaction: Interaction ->
        when (interaction) {
          is FocusInteraction.Focus -> focused++
          is FocusInteraction.Unfocus -> focused--
          is HoverInteraction.Enter -> hovered++
          is HoverInteraction.Exit -> hovered--
          is PressInteraction.Press -> pressed++
          is PressInteraction.Release, is PressInteraction.Cancel -> pressed--
        }
        invalidateDraw()
      }
    }
  }

  override fun onObservedReadsChanged() {
    observeReads { colours = currentValueOf(LocalMarkColours) }
    invalidateDraw()
  }

  override fun ContentDrawScope.draw() {
    val corner = CornerRadius(Look.corner.toPx())
    if (pressed > 0 || hovered > 0) drawRoundRect(colours.wash.copy(alpha = if (pressed > 0) 0.12f else 0.06f), cornerRadius = corner)
    drawContent()
    if (focused > 0) {
      // Two strokes, so the ring shows on a filled button whose fill is the ring's own colour.
      val ring = 2.dp.toPx()
      val inner = 1.5.dp.toPx()
      drawRoundRect(colours.ring, Offset(ring / 2, ring / 2), Size(size.width - ring, size.height - ring), corner, Stroke(ring))
      drawRoundRect(colours.inner, Offset(ring + inner / 2, ring + inner / 2),
        Size(size.width - 2 * ring - inner, size.height - 2 * ring - inner), corner, Stroke(inner))
    }
  }
}

@Composable
fun ProxenosTheme(dark: Boolean, fonts: Fonts = Fonts.Default, content: @Composable () -> Unit) {
  val scheme = if (dark) io.github.kzagoris.proxenos.gui.dark else light
  MaterialTheme(
    colorScheme = scheme,
    typography = typography(fonts.sans),
    shapes = RoundedCornerShape(Look.corner).let { corner -> Shapes(corner, corner, corner, corner, corner) },
  ) {
    CompositionLocalProvider(
      LocalStatus provides if (dark) statusDark else statusLight,
      LocalMono provides fonts.mono,
      LocalRippleConfiguration provides null,
      LocalTonalElevationEnabled provides false,
      LocalMarkColours provides MarkColours(scheme.primary, scheme.surface, scheme.onSurface),
      LocalIndication provides Marks,
      content = content,
    )
  }
}

/** The Mono sibling, for Roots, commands and arguments. */
val LocalMono = staticCompositionLocalOf<FontFamily> { FontFamily.Monospace }
