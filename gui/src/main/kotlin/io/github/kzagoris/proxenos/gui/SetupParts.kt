package io.github.kzagoris.proxenos.gui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.kzagoris.proxenos.gui.res.*
import org.jetbrains.compose.resources.DrawableResource

// The drawn parts of the Guided flow. The connection diagram is an illustration only: Runtime,
// Tunnel and Connector keep their own measured readings in the stage strip (ADRs 0009, 0012).

internal enum class Mark { Done, Current, Ahead }

private val TUNNEL_PARTS = listOf("Create tunnel", "Associate workspace", "Connect")

private fun SetupProgress.reached() = maxOf(furthest, step).ordinal

private fun SetupProgress.mark(index: Int) = when {
  index == step.ordinal -> Mark.Current
  completed || index < reached() -> Mark.Done
  else -> Mark.Ahead
}

private fun SetupProgress.partMark(part: Int) = when {
  step == SetupStep.Tunnel && part == tunnelPart -> Mark.Current
  completed || reached() > SetupStep.Tunnel.ordinal -> Mark.Done
  reached() == SetupStep.Tunnel.ordinal && part < tunnelPart -> Mark.Done
  else -> Mark.Ahead
}

/** A step's number, or a check once done; the current one is filled with the accent. */
@Composable
private fun StepCircle(mark: Mark, number: Int, size: Dp = 24.dp) {
  val colours = MaterialTheme.colorScheme
  val fill by animateColorAsState(when (mark) {
    Mark.Done -> colours.primaryContainer
    Mark.Current -> colours.primary
    Mark.Ahead -> Color.Transparent
  }, motion(200))
  Box(Modifier.size(size).clip(CircleShape).background(fill)
    .border(1.dp, if (mark == Mark.Ahead) colours.outline else Color.Transparent, CircleShape),
    contentAlignment = Alignment.Center) {
    if (mark == Mark.Done) Ic(Res.drawable.check, colours.primary, size * 0.7f)
    else Text(number.toString(), style = MaterialTheme.typography.labelSmall,
      color = if (mark == Mark.Current) colours.onPrimary else colours.onSurfaceVariant)
  }
}

// The rail's geometry: the line runs through the circles' centres, from under one circle to the next.
private val CIRCLE = 24.dp
private val ROW = 34.dp
private val INSET = 6.dp
private val GAP = 10.dp

/**
 * The sidebar's steps: a line joins them and fills in as setup advances. Tunnel's three parts sit
 * under it as markers only; its page moves between them.
 */
@Composable
internal fun StepRail(progress: SetupProgress, enabled: Boolean, navigate: (SetupStep) -> Unit) {
  val colours = MaterialTheme.colorScheme
  Column {
    SetupStep.entries.forEachIndexed { index, step ->
      val mark = progress.mark(index)
      val filled = animateFloatAsState(if (index < progress.reached()) 1f else 0f, motion(300))
      val last = index == SetupStep.entries.lastIndex
      Column(Modifier.drawBehind {
        if (last) return@drawBehind
        val x = (INSET + CIRCLE / 2).toPx()
        val top = ((ROW + CIRCLE) / 2).toPx()
        val bottom = size.height + ((ROW - CIRCLE) / 2).toPx()
        val width = 2.dp.toPx()
        drawLine(colours.outlineVariant, Offset(x, top), Offset(x, bottom), width)
        drawLine(colours.primary, Offset(x, top), Offset(x, top + (bottom - top) * filled.value), width)
      }) {
        val reachable = enabled && index <= progress.reached()
        Row(Modifier.fillMaxWidth().height(ROW).clip(RoundedCornerShape(Look.corner))
          .pressable(reachable) { navigate(step) }.padding(horizontal = INSET),
          verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
          StepCircle(mark, index + 1, CIRCLE)
          StepLabel(step.label, mark, MaterialTheme.typography.bodyMedium, dim = !reachable)
        }
        if (step == SetupStep.Tunnel) Column(Modifier.padding(start = INSET + CIRCLE + 10.dp, top = 2.dp, bottom = 2.dp)) {
          TUNNEL_PARTS.forEachIndexed { part, label ->
            val partMark = progress.partMark(part)
            Row(Modifier.height(24.dp), verticalAlignment = Alignment.CenterVertically,
              horizontalArrangement = Arrangement.spacedBy(8.dp)) {
              PartDot(partMark)
              StepLabel(label, partMark, MaterialTheme.typography.bodySmall, bold = false)
            }
          }
        }
        if (!last) Spacer(Modifier.height(GAP))
      }
    }
  }
}

/** A step's name: emphasised while current, dimmed while it cannot be opened. */
@Composable
private fun StepLabel(label: String, mark: Mark, style: TextStyle, dim: Boolean = false, bold: Boolean = true) {
  val colours = MaterialTheme.colorScheme
  Text(label, style = style, maxLines = 1,
    fontWeight = if (bold && mark == Mark.Current) FontWeight.SemiBold else FontWeight.Normal,
    color = when {
      mark == Mark.Current -> colours.onSurface
      dim -> colours.onSurfaceVariant.copy(alpha = 0.6f)
      else -> colours.onSurfaceVariant
    })
}

@Composable
private fun PartDot(mark: Mark) {
  val colours = MaterialTheme.colorScheme
  val fill by animateColorAsState(if (mark == Mark.Done) colours.primary else Color.Transparent, motion(200))
  Box(Modifier.size(8.dp).clip(CircleShape).background(fill).border(
    if (mark == Mark.Current) 2.dp else 1.dp,
    if (mark == Mark.Ahead) colours.outline else colours.primary, CircleShape))
}

/** The steps in one line, for a narrow window: circles joined by short lines. */
@Composable
internal fun StepTrack(progress: SetupProgress, enabled: Boolean, navigate: (SetupStep) -> Unit) {
  Track(SetupStep.entries.mapIndexed { index, step -> step.label to progress.mark(index) },
    reachable = { enabled && it <= progress.reached() }, select = { navigate(SetupStep.entries[it]) },
    modifier = Modifier.horizontalScroll(rememberScrollState()))
}

/** Where the Tunnel page is among its three parts. */
@Composable
internal fun PartTrack(progress: SetupProgress) {
  Track(TUNNEL_PARTS.mapIndexed { part, label -> label to progress.partMark(part) })
}

@Composable
private fun Track(items: List<Pair<String, Mark>>, modifier: Modifier = Modifier,
  reachable: (Int) -> Boolean = { false }, select: ((Int) -> Unit)? = null) {
  val colours = MaterialTheme.colorScheme
  Row(modifier, verticalAlignment = Alignment.CenterVertically) {
    items.forEachIndexed { index, (label, mark) ->
      if (index > 0) {
        val line by animateColorAsState(if (mark == Mark.Ahead) colours.outlineVariant else colours.primary, motion(250))
        Box(Modifier.padding(horizontal = 6.dp).width(20.dp).height(2.dp).background(line))
      }
      Row(Modifier.clip(RoundedCornerShape(Look.corner))
        .then(if (select != null) Modifier.pressable(reachable(index)) { select(index) } else Modifier)
        .padding(horizontal = 4.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        StepCircle(mark, index + 1, 20.dp)
        StepLabel(label, mark, MaterialTheme.typography.bodySmall)
      }
    }
  }
}

/**
 * Fades in content that appears in place, as a status changes on a page. A page's own entrance is
 * the page transition's; only a fade here, so the scrolling page is not re-measured every frame.
 */
@Composable
internal fun Appear(content: @Composable () -> Unit) {
  val shown = remember { MutableTransitionState(false).apply { targetState = true } }
  AnimatedVisibility(shown, enter = fadeIn(motion(220))) { content() }
}

/** The shape of the setup's larger surfaces: notes, value cards and the diagram. */
private val card = RoundedCornerShape(Look.corner * 1.5f)

private fun Tone.icon(): DrawableResource = when (this) {
  Tone.Ok -> Res.drawable.check_circle
  Tone.Warn -> Res.drawable.warning
  Tone.Bad -> Res.drawable.error
  Tone.Info, Tone.None -> Res.drawable.info
}

/**
 * A status-tinted note: the tone's icon (or a spinner while [busy]), an optional [title] and the
 * [text]. Its colour never stands alone (GUI-SPEC §6): the words say what the tint means.
 */
@Composable
internal fun SetupNote(
  text: String? = null,
  tone: Tone = Tone.Info,
  title: String? = null,
  busy: Boolean = false,
  icon: DrawableResource? = null,
  more: @Composable ColumnScope.() -> Unit = {},
) {
  val colour = LocalStatus.current.of(tone)
  Row(Modifier.fillMaxWidth().clip(card).background(colour.copy(alpha = 0.10f))
    .border(1.dp, colour.copy(alpha = 0.35f), card).padding(14.dp),
    horizontalArrangement = Arrangement.spacedBy(12.dp)) {
    Box(Modifier.padding(top = 1.dp)) {
      if (busy) CircularProgressIndicator(Modifier.size(18.dp), color = colour, strokeWidth = 2.dp)
      else Ic(icon ?: tone.icon(), colour, 18.dp)
    }
    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
      if (title != null) Text(title, style = MaterialTheme.typography.titleSmall)
      if (text != null) Text(text, style = MaterialTheme.typography.bodyMedium)
      more()
    }
  }
}

/** One local requirement: an icon in its status colour beside the words. */
@Composable
internal fun Requirement(text: String, tone: Tone?) {
  Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
    if (tone == null) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
    else Ic(tone.icon(), LocalStatus.current.of(tone), 18.dp)
    Text(text, Modifier.weight(1f, fill = false))
  }
}

/** A shaded block for values and pictures, set apart from the page's text. */
@Composable
internal fun Shaded(content: @Composable ColumnScope.() -> Unit) =
  Column(Modifier.fillMaxWidth().clip(card).background(MaterialTheme.colorScheme.surfaceContainer).padding(14.dp),
    verticalArrangement = Arrangement.spacedBy(8.dp), content = content)

/** Values to enter elsewhere, labelled, in the Mono font and selectable. */
@Composable
internal fun ValueCard(values: List<Pair<String, String>>) = SelectionContainer {
  Shaded {
    values.forEach { (label, value) ->
      Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(label, Modifier.width(120.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, Modifier.weight(1f), fontFamily = LocalMono.current)
      }
    }
  }
}

/** This machine — tunnel — ChatGPT, as a picture of the route; it measures nothing. */
@Composable
internal fun ConnectionDiagram() = Shaded {
  Row(Modifier.padding(vertical = 6.dp)) {
    DiagramNode(Res.drawable.computer, "This machine", "Proxenos + your Workspaces")
    DiagramLink()
    DiagramNode(Res.drawable.cloud, "Secure tunnel", "Outbound connection to OpenAI")
    DiagramLink()
    DiagramNode(Res.drawable.extension, "ChatGPT", "Your Proxenos plugin")
  }
}

@Composable
private fun RowScope.DiagramNode(icon: DrawableResource, title: String, caption: String) {
  val colours = MaterialTheme.colorScheme
  Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally,
    verticalArrangement = Arrangement.spacedBy(6.dp)) {
    Box(Modifier.size(44.dp).clip(CircleShape).background(colours.primaryContainer), contentAlignment = Alignment.Center) {
      Ic(icon, colours.primary, 22.dp)
    }
    Text(title, style = MaterialTheme.typography.titleSmall, textAlign = TextAlign.Center)
    Text(caption, style = MaterialTheme.typography.bodySmall, color = colours.onSurfaceVariant, textAlign = TextAlign.Center)
  }
}

@Composable
private fun DiagramLink() {
  val colour = MaterialTheme.colorScheme.outline
  Box(Modifier.padding(top = 21.dp).width(36.dp).height(2.dp).drawBehind {
    val dash = 4.dp.toPx()
    drawLine(colour, Offset(0f, size.height / 2), Offset(size.width, size.height / 2), size.height,
      pathEffect = PathEffect.dashPathEffect(floatArrayOf(dash, dash)))
  })
}
