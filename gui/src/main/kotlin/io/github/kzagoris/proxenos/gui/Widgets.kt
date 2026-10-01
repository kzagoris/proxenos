package io.github.kzagoris.proxenos.gui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.jetbrains.compose.resources.DrawableResource
import org.jetbrains.compose.resources.painterResource

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
    Ic(reading.icon, colour, 15.dp)
    Text(reading.word, style = MaterialTheme.typography.bodySmall, color = colour, maxLines = 1)
  }
}

/**
 * A button. [primary] is the filled one; there is one per screen. Disabled buttons stay in the
 * Tab order's place but cannot be pressed, so focus does not jump while an act is in flight.
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
  val shape = RoundedCornerShape(Look.corner)
  val content = if (primary) colours.onPrimary else colours.onSurface
  Row(
    modifier.height(28.dp).alpha(if (enabled) 1f else 0.45f).clip(shape)
      .then(if (primary) Modifier.background(colours.primary) else Modifier.border(1.dp, colours.outline, shape))
      .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
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

/** A status-tinted one-line banner, optionally with the act that reviews it. */
@Composable
fun Banner(text: String, tone: Tone, icon: DrawableResource, action: String? = null, onAction: () -> Unit = {}) {
  val colour = LocalStatus.current.of(tone)
  Row(
    Modifier.fillMaxWidth().clip(RoundedCornerShape(Look.corner)).background(colour.copy(alpha = 0.14f))
      .padding(horizontal = Look.pad, vertical = Look.gap),
    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Look.gap),
  ) {
    Ic(icon, colour)
    Text(text, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
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
