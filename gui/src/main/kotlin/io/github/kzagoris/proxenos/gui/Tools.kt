package io.github.kzagoris.proxenos.gui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.*
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import io.github.kzagoris.proxenos.coreapi.ArgumentType
import io.github.kzagoris.proxenos.coreapi.WorkspaceState
import io.github.kzagoris.proxenos.frontend.RUN_COMMAND
import io.github.kzagoris.proxenos.frontend.Wording
import io.github.kzagoris.proxenos.frontend.askedOf
import io.github.kzagoris.proxenos.gui.res.*

/**
 * The catalog ChatGPT is served, against this Workspace as the stream now has it. Admission is
 * read from the snapshot on every draw, so a level changed anywhere is followed here.
 */
@Composable
internal fun ToolsTab(state: GuiState, selected: WorkspaceState, send: (GuiIntent) -> Unit) {
  val colours = MaterialTheme.colorScheme
  val catalog = state.snapshot?.catalog.orEmpty()
  Text("The ${catalog.size} tools ChatGPT is served, against '${selected.workspace.name}' as it stands. " +
    "Try runs one through the same pipeline ChatGPT's calls take, and it is recorded in Activity.",
    style = MaterialTheme.typography.bodySmall, color = colours.onSurfaceVariant)
  Column {
    catalog.forEach { spec ->
      val (admitted, why) = Wording.admission(spec, selected)
      Row(Modifier.fillMaxWidth().testTag("tool:${spec.name}").hairlineBottom(colours.outlineVariant).padding(vertical = Look.gap),
        horizontalArrangement = Arrangement.spacedBy(Look.gap)) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
          Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Look.gap)) {
            Text(spec.name, style = MaterialTheme.typography.bodyMedium.copy(fontFamily = LocalMono.current))
            StatusWord(if (admitted) Reading("Allowed", Tone.Ok, Res.drawable.check_circle) else Reading("Refused", Tone.None, Res.drawable.error))
          }
          Text(why, style = MaterialTheme.typography.bodySmall)
          val tone = if (spec.name == RUN_COMMAND) LocalStatus.current.warn else colours.onSurfaceVariant
          Wording.notes(spec, selected.workspace).forEach { Text(it, style = MaterialTheme.typography.bodySmall, color = tone) }
        }
        Btn("Try…", { send(GuiIntent.AskTry(spec.name)) }, enabled = state.canActOnWorkspace)
      }
    }
  }
}

/**
 * GUI-SPEC §4.2 TryOperation: a form generated from the entry's arguments, less the Workspace
 * (the selected one) and the `request_id` (minted fresh for every try). It stays open with the
 * answer, so trying again is a new execution from the same form.
 */
@Composable
internal fun TryDialog(state: GuiState, send: (GuiIntent) -> Unit) {
  val trying = state.trying ?: return
  val selected = state.selectedWorkspace ?: return
  val spec = state.snapshot?.catalog?.find { it.name == trying.tool } ?: return
  val asked = askedOf(spec)
  val given = remember(spec) { mutableStateMapOf<String, String>() }
  val first = remember { FocusRequester() }
  val ready = asked.none { it.required && given[it.name].isNullOrBlank() } && state.inFlight == null
  val submit = { if (ready) send(GuiIntent.Try(given.filterValues { it.isNotEmpty() })) }
  AlertDialog(
    onDismissRequest = { send(GuiIntent.CancelTry) },
    title = { Text("Try ${spec.name} against '${selected.workspace.name}'") },
    text = {
      Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(Look.gap)) {
        Text(spec.description, style = MaterialTheme.typography.bodySmall)
        Text(Wording.admission(spec, selected).second, style = MaterialTheme.typography.bodySmall)
        Wording.notes(spec, selected.workspace).forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
        asked.forEachIndexed { index, argument ->
          val focus = if (index == 0) Modifier.focusRequester(first) else Modifier
          if (argument.type == ArgumentType.Flag) {
            val checked = given[argument.name] == "yes"
            Row(focus.toggleable(checked, role = Role.Checkbox) { given[argument.name] = if (it) "yes" else "no" },
              verticalAlignment = Alignment.CenterVertically) {
              Checkbox(checked, null)
              Text("${argument.name} — ${argument.description}", style = MaterialTheme.typography.bodySmall)
            }
          } else OutlinedTextField(given[argument.name].orEmpty(), { given[argument.name] = it },
            Modifier.fillMaxWidth().then(focus).enter(submit),
            label = { Text(if (argument.required) "${argument.name} (required)" else argument.name) },
            supportingText = { Text(argument.description) }, singleLine = true,
            textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = LocalMono.current))
        }
        state.refusal?.let { Text(it, color = LocalStatus.current.bad) }
        trying.result?.let { Text(it.words, color = LocalStatus.current.of(it.tone)) }
        if (state.inFlight != null) LinearProgressIndicator(Modifier.fillMaxWidth())
      }
    },
    dismissButton = {
      Row(horizontalArrangement = Arrangement.spacedBy(Look.gap)) {
        // The stream decides: the entry is shown once it has arrived, never guessed at before.
        if (trying.result != null) Btn("Show in Activity", { send(GuiIntent.ShowTried) }, enabled = state.triedEntry != null)
        Btn("Close", { send(GuiIntent.CancelTry) })
      }
    },
    confirmButton = { Btn("Try", submit, enabled = ready, primary = true) },
    modifier = Modifier.escape { send(GuiIntent.CancelTry) },
  )
  LaunchedEffect(Unit) { if (asked.isNotEmpty()) first.requestFocus() }
}
