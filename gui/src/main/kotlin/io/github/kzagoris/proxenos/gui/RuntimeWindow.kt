package io.github.kzagoris.proxenos.gui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.*
import androidx.compose.ui.unit.dp
import io.github.kzagoris.proxenos.frontend.Attachment
import io.github.kzagoris.proxenos.frontend.Wording

@Composable
fun RuntimeWindow(state: GuiState, send: (GuiIntent) -> Unit) {
  val snackbar = remember { SnackbarHostState() }
  LaunchedEffect(state.notice) {
    state.notice?.let {
      snackbar.showSnackbar(it, withDismissAction = true, duration = SnackbarDuration.Indefinite)
      send(GuiIntent.DismissNotice)
    }
  }
  Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
    Text("Proxenos", style = MaterialTheme.typography.headlineSmall)
    Text("Runtime · ${state.runtimeWords}", style = MaterialTheme.typography.titleMedium)
    if (state.snapshot != null) {
      Text(Wording.attached("this window"))
      Button(onClick = { send(GuiIntent.AskStop) }, enabled = state.inFlight == null) { Text("Stop Runtime…") }
    } else {
      Text("Tunnel · Can't tell")
      Text("Connector · Can't tell")
      Text(Wording.NO_AUTOSTART)
      Button(onClick = { send(GuiIntent.StartRuntime) },
        enabled = state.attachment is Attachment.Absent && state.inFlight == null,
      ) { Text("Start Runtime") }
    }
    state.inFlight?.let {
      Text(it)
      LinearProgressIndicator(Modifier.fillMaxWidth())
    }
    Spacer(Modifier.weight(1f))
    Text("Closing this window leaves the Runtime running.", style = MaterialTheme.typography.bodySmall)
    SnackbarHost(snackbar)
  }
  if (state.stopConfirmation) {
    val cancel = remember { FocusRequester() }
    val words = Wording.stopRuntime()
    AlertDialog(
      onDismissRequest = { send(GuiIntent.CancelStop) },
      title = { Text(words.first()) },
      text = { Text(words.drop(1).joinToString(" ")) },
      dismissButton = {
        TextButton(onClick = { send(GuiIntent.CancelStop) }, modifier = Modifier.focusRequester(cancel)) { Text("Cancel") }
      },
      confirmButton = { TextButton(onClick = { send(GuiIntent.ConfirmStop) }) { Text("Stop Runtime") } },
      modifier = Modifier.onPreviewKeyEvent { event ->
        if (event.type == KeyEventType.KeyDown && event.key == Key.Escape) {
          send(GuiIntent.CancelStop)
          true
        } else false
      },
    )
    LaunchedEffect(Unit) { cancel.requestFocus() }
  }
}
