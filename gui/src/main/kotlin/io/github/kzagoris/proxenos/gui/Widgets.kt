package io.github.kzagoris.proxenos.gui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.indication
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.*
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.jetbrains.compose.resources.DrawableResource
import org.jetbrains.compose.resources.painterResource
import io.github.kzagoris.proxenos.gui.res.Res
import io.github.kzagoris.proxenos.gui.res.sync

// The look's own parts (GUI-SPEC §6), drawn with foundation as the prototype drew them. Material
// supplies the complex widgets only.

@Composable
fun Ic(icon: DrawableResource, tint: Color = LocalContentColor.current, size: Dp = Look.icon) =
  Icon(painterResource(icon), null, Modifier.size(size), tint = tint)

/** A status colour never alone: the icon and the word go with it. */
@Composable
fun StatusWord(reading: Reading, modifier: Modifier = Modifier) {
  val colour = LocalStatus.current.of(reading.tone)
  Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
    if (reading.icon == Res.drawable.sync) CircularProgressIndicator(Modifier.size(15.dp), color = colour, strokeWidth = 1.5.dp)
    else Ic(reading.icon, colour, 15.dp)
    Text(reading.word, style = MaterialTheme.typography.bodySmall, color = colour, maxLines = 1)
  }
}

/**
 * A button. [primary] is the filled one; there is one per screen. Disabled buttons stay in the
 * Tab order's place but cannot be pressed, so focus does not jump while an act is in flight.
 * Hover draws the outline in the accent and a press sinks the button slightly; [Marks] adds the
 * wash and the focus ring. A disabled primary turns grey, so it never reads as a selected one.
 * The animated colours are read only when drawn, so hovering repaints rather than recomposes.
 */
@Composable
fun Btn(
  text: String,
  onClick: () -> Unit,
  modifier: Modifier = Modifier,
  enabled: Boolean = true,
  primary: Boolean = false,
  icon: DrawableResource? = null,
) {
  val colours = MaterialTheme.colorScheme
  val interactions = remember { MutableInteractionSource() }
  val hovered by interactions.collectIsHoveredAsState()
  val pressed by interactions.collectIsPressedAsState()
  val fill = animateColorAsState(when {
    !primary -> Color.Transparent
    enabled -> colours.primary
    else -> colours.surfaceContainerHighest
  }, motion(120))
  val edge = animateColorAsState(when {
    primary -> Color.Transparent
    !enabled -> colours.outlineVariant
    hovered -> colours.primary
    else -> colours.outline
  }, motion(120))
  val scale = animateFloatAsState(if (pressed && enabled) 0.97f else 1f, motion(70))
  val content = when {
    !enabled -> colours.onSurfaceVariant.copy(alpha = 0.6f)
    primary -> colours.onPrimary
    else -> colours.onSurface
  }
  Row(
    modifier.height(28.dp).graphicsLayer { scaleX = scale.value; scaleY = scale.value }
      .drawBehind {
        val corner = CornerRadius(Look.corner.toPx())
        val line = 1.dp.toPx()
        drawRoundRect(fill.value, cornerRadius = corner)
        drawRoundRect(edge.value, Offset(line / 2, line / 2), Size(size.width - line, size.height - line), corner, Stroke(line))
      }
      .clip(RoundedCornerShape(Look.corner)).pressable(enabled, interactions, onClick)
      .padding(horizontal = 10.dp),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
  ) {
    CompositionLocalProvider(LocalContentColor provides content) {
      if (icon != null) Ic(icon)
      Text(text, style = MaterialTheme.typography.labelLarge, color = content, maxLines = 1)
    }
  }
}

/** Pressed as a button: [Marks] for feedback, and the hand cursor only while it can be pressed. */
@Composable
fun Modifier.pressable(enabled: Boolean, interactions: MutableInteractionSource? = null, onClick: () -> Unit) =
  clickable(interactions, LocalIndication.current, enabled = enabled, role = Role.Button, onClick = onClick)
    .pointerHoverIcon(if (enabled) PointerIcon.Hand else PointerIcon.Default)

/** Material text actions use the same feedback as the foundation controls, without a ripple. */
@Composable
internal fun TextBtn(
  onClick: () -> Unit,
  modifier: Modifier = Modifier,
  enabled: Boolean = true,
  content: @Composable RowScope.() -> Unit,
) {
  val interactions = remember { MutableInteractionSource() }
  TextButton(onClick, modifier.clip(MaterialTheme.shapes.small)
    .indication(interactions, LocalIndication.current)
    .pointerHoverIcon(if (enabled) PointerIcon.Hand else PointerIcon.Default),
    enabled = enabled, shape = MaterialTheme.shapes.small,
    interactionSource = interactions, content = content)
}

/** A status-tinted one-line banner, optionally with the act that reviews it. */
@Composable
fun Banner(text: String, tone: Tone, icon: DrawableResource, action: String? = null, maxLines: Int = 2, onAction: () -> Unit = {}) {
  val colour = LocalStatus.current.of(tone)
  Row(
    Modifier.fillMaxWidth().clip(RoundedCornerShape(Look.corner)).background(colour.copy(alpha = 0.14f))
      .padding(horizontal = Look.pad, vertical = Look.gap),
    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Look.gap),
  ) {
    Ic(icon, colour)
    Text(text, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, maxLines = maxLines, overflow = TextOverflow.Ellipsis)
    if (action != null) Btn(action, onAction)
  }
}

/** A section heading: small caps over a hairline, not a card. */
@Composable
fun Section(title: String) {
  Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
    Text(title.uppercase(), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
  }
}

/** A count or `!` beside a destination. `!` is trouble; a count is only a count. */
@Composable
fun DestinationBadge(text: String) {
  val colour = if (text == "!") LocalStatus.current.bad else MaterialTheme.colorScheme.primary
  Box(Modifier.clip(CircleShape).background(colour.copy(alpha = 0.18f)).padding(horizontal = 7.dp, vertical = 1.dp)) {
    Text(text, style = MaterialTheme.typography.labelSmall, color = colour)
  }
}

fun Modifier.hairlineEnd(colour: Color) = drawBehind {
  drawLine(colour, Offset(size.width - 0.5f, 0f), Offset(size.width - 0.5f, size.height), 1.dp.toPx())
}

fun Modifier.hairlineBottom(colour: Color) = drawBehind {
  drawLine(colour, Offset(0f, size.height - 0.5f), Offset(size.width, size.height - 0.5f), 1.dp.toPx())
}

fun Modifier.hairlineTop(colour: Color) = drawBehind {
  drawLine(colour, Offset(0f, 0.5f), Offset(size.width, 0.5f), 1.dp.toPx())
}

/**
 * ↑/↓/Home/End over [items] from the one [focused] (GUI-SPEC §5); [move] takes focus there. Held
 * at either end.
 */
fun <T> Modifier.listKeys(items: List<T>, focused: () -> T?, move: (T) -> Unit) = onPreviewKeyEvent { event ->
  val from = items.indexOf(focused())
  if (from < 0 || event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
  val to = when (event.key) {
    Key.DirectionUp -> from - 1
    Key.DirectionDown -> from + 1
    Key.MoveHome -> 0
    Key.MoveEnd -> items.lastIndex
    else -> return@onPreviewKeyEvent false
  }
  items.getOrNull(to)?.let(move)
  true
}

/** A list row that is selected by identity: tinted while selected, and pressed to select it. */
@Composable
fun Modifier.selectableRow(selected: Boolean, select: () -> Unit) = clip(RoundedCornerShape(Look.corner))
  .background(if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent)
  .selectable(selected, role = Role.Button, onClick = select)
