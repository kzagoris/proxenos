package io.github.kzagoris.proxenos.gui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.*
import io.github.kzagoris.proxenos.coreapi.WorkspaceState
import io.github.kzagoris.proxenos.frontend.Wording
import io.github.kzagoris.proxenos.frontend.absoluteRoot
import io.github.kzagoris.proxenos.frontend.overlaps
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** The overlap [words] for [root] as typed, against the [workspaces] it was checked with. */
private data class OverlapCheck(val root: String, val workspaces: List<WorkspaceState>, val words: List<String>)


@Composable
internal fun AddDialog(state: GuiState, send: (GuiIntent) -> Unit, chooser: FolderChooser? = null) = RootDialog(state, send, chooser, moving = false)

@Composable
internal fun MoveDialog(state: GuiState, send: (GuiIntent) -> Unit, chooser: FolderChooser? = null) = RootDialog(state, send, chooser, moving = true)

@Composable
private fun RootDialog(state: GuiState, send: (GuiIntent) -> Unit, chooser: FolderChooser?, moving: Boolean) {
  val workspace = state.selectedWorkspace?.workspace
  var root by remember { mutableStateOf(if (moving) workspace!!.root else "") }
  var name by remember { mutableStateOf("") }
  val focus = remember { FocusRequester() }
  val scope = rememberCoroutineScope()
  var portal by remember(chooser) { mutableStateOf<Boolean?>(null) }
  var choosing by remember { mutableStateOf(false) }
  var chooserError by remember { mutableStateOf<String?>(null) }
  LaunchedEffect(chooser) { portal = chooser?.available() == true }

  val workspaces = state.snapshot?.workspaces.orEmpty().filter { !moving || it.workspace.id != workspace?.id }
  var checked by remember { mutableStateOf(OverlapCheck("", workspaces, emptyList())) }
  var pathError by remember { mutableStateOf<String?>(null) }
  LaunchedEffect(root, workspaces) {
    pathError = null
    val words = try {
      if (root.isBlank()) emptyList() else withContext(Dispatchers.IO) {
        val absolute = absoluteRoot(root)
        overlaps(absolute, workspaces).takeIf { it.isNotEmpty() }?.let { Wording.overlap(absolute.toString(), it) }.orEmpty()
      }
    } catch (invalid: IllegalArgumentException) {
      pathError = invalid.message ?: "Enter a valid Root."
      emptyList()
    }
    checked = OverlapCheck(root, workspaces, words)
  }
  val ready = root.isNotBlank() && pathError == null && !choosing && state.inFlight == null && checked.root == root && checked.workspaces == workspaces
  val folder = root.trim().trimEnd('/').substringAfterLast('/')
  val label = when {
    moving && checked.words.isNotEmpty() -> "Move anyway, at Read"
    moving -> "Move at Read"
    checked.words.isNotEmpty() -> "Add anyway, at Read"
    else -> "Add at Read"
  }
  val cancel = { send(if (moving) GuiIntent.CancelRegistration else GuiIntent.CancelAdd) }
  val submit = { if (ready) send(if (moving) GuiIntent.Move(root) else GuiIntent.Register(root, name.ifBlank { null })) }
  val enter = Modifier.enter(submit)
  AlertDialog(
    onDismissRequest = cancel,
    title = { Text(if (moving) "Move '${workspace!!.name}' to another folder?" else "Add Workspace") },
    text = {
      Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(Look.gap)) {
        if (moving) Text("It was at ${workspace!!.accessLevel}. It lands at Read because a level granted to the old Root does not carry over to another directory. Raise it again once you have checked what it is.")
        OutlinedTextField(root, { root = it }, Modifier.fillMaxWidth().then(if (moving) Modifier else Modifier.focusRequester(focus)).then(enter),
          label = { Text("Root") }, singleLine = true,
          textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = LocalMono.current))

        Btn("Choose…", {
          if (!choosing && portal == true) {
            choosing = true
            chooserError = null
            scope.launch {
              try {
                chooser?.choose(root)?.let { root = it }
              } catch (cancelled: CancellationException) {
                throw cancelled
              } catch (failed: Exception) {
                currentCoroutineContext().ensureActive()
                chooserError = "Could not choose a Root: ${failed.message ?: failed.javaClass.simpleName}. Enter the Root in the field."
              } finally {
                choosing = false
              }
            }
          }
        }, enabled = portal == true && !choosing && state.inFlight == null)
        if (portal == false) Text("No folder chooser is available. Enter the Root in the field.", style = MaterialTheme.typography.bodySmall)
        chooserError?.let { Text(it, color = LocalStatus.current.bad) }
        if (!moving) OutlinedTextField(name, { name = it }, Modifier.fillMaxWidth().then(enter),
          label = { Text("Name") }, placeholder = { Text(folder) }, singleLine = true)
        checked.words.forEach { Text(it, style = MaterialTheme.typography.bodySmall, color = LocalStatus.current.warn) }
        pathError?.let { Text(it, color = LocalStatus.current.bad) }
        state.refusal?.let { Text(it, color = LocalStatus.current.bad) }
        if (state.inFlight != null) LinearProgressIndicator(Modifier.fillMaxWidth())
      }
    },
    dismissButton = { Btn("Cancel", cancel, if (moving) Modifier.focusRequester(focus) else Modifier) },
    confirmButton = { Btn(label, submit, enabled = ready, primary = true) },
    modifier = Modifier.escape(cancel),
  )
  LaunchedEffect(Unit) { focus.requestFocus() }
}

@Composable
internal fun ReconfirmDialog(state: GuiState, send: (GuiIntent) -> Unit) {
  val workspace = state.selectedWorkspace?.workspace ?: return
  val cancel = remember { FocusRequester() }
  val words = Wording.reconfirm(workspace)
  AlertDialog(
    onDismissRequest = { send(GuiIntent.CancelRegistration) },
    title = { Text(words.first()) },
    text = {
      Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(Look.gap)) {
        Text(words.drop(1).joinToString(" "))
        state.refusal?.let { Text(it, color = LocalStatus.current.bad) }
      }
    },
    dismissButton = { Btn("Cancel", { send(GuiIntent.CancelRegistration) }, Modifier.focusRequester(cancel)) },
    confirmButton = { Btn("Re-confirm at Read", { send(GuiIntent.ConfirmReconfirm) }, enabled = state.inFlight == null) },
    modifier = Modifier.escape { send(GuiIntent.CancelRegistration) },
  )
  LaunchedEffect(Unit) { cancel.requestFocus() }
}

@Composable
internal fun ForgetDialog(state: GuiState, send: (GuiIntent) -> Unit) {
  val workspace = state.selectedWorkspace?.workspace ?: return
  val cancel = remember { FocusRequester() }
  AlertDialog(
    onDismissRequest = { send(GuiIntent.CancelRegistration) },
    title = { Text("Forget '${workspace.name}'?") },
    text = {
      Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(Look.gap)) {
        Text("Only the registration is forgotten. Activity stays; nothing already running is stopped.")
        val running = state.snapshot?.running.orEmpty()
        if (running.isNotEmpty()) Text("Running work in the Runtime (Workspace names recorded when each Operation started):")
        running.forEach {
          Text("Workspace at start: ${it.workspace ?: "none"} · ${it.tool}: ${it.arguments} · started ${clock.format(it.startedAt)} — still running, not stopped by Forget.")
        }
        state.refusal?.let { Text(it, color = LocalStatus.current.bad) }
      }
    },
    dismissButton = { Btn("Cancel", { send(GuiIntent.CancelRegistration) }, Modifier.focusRequester(cancel)) },
    confirmButton = { Btn("Forget Workspace", { send(GuiIntent.ConfirmForget) }, enabled = state.inFlight == null) },
    modifier = Modifier.escape { send(GuiIntent.CancelRegistration) }.onPreviewKeyEvent {
      it.key == Key.Enter || it.key == Key.NumPadEnter
    },
  )
  LaunchedEffect(Unit) { cancel.requestFocus() }
}

@Composable
internal fun RenameDialog(state: GuiState, send: (GuiIntent) -> Unit) {
  val workspace = state.selectedWorkspace?.workspace ?: return
  var name by remember(workspace.id) { mutableStateOf(workspace.name) }
  val field = remember { FocusRequester() }
  val submit = { if (name.isNotBlank() && state.inFlight == null) send(GuiIntent.Rename(name)) }
  AlertDialog(
    onDismissRequest = { send(GuiIntent.CancelRegistration) },
    title = { Text("Rename Workspace") },
    text = {
      Column(verticalArrangement = Arrangement.spacedBy(Look.gap)) {
        OutlinedTextField(name, { name = it }, Modifier.fillMaxWidth().focusRequester(field).enter(submit), label = { Text("Name") }, singleLine = true)
        state.refusal?.let { Text(it, color = LocalStatus.current.bad) }
      }
    },
    dismissButton = { Btn("Cancel", { send(GuiIntent.CancelRegistration) }) },
    confirmButton = { Btn("Rename", submit, enabled = name.isNotBlank() && state.inFlight == null) },
    modifier = Modifier.onPreviewKeyEvent {
      if (it.key == Key.Escape) true.also { _ -> if (it.type == KeyEventType.KeyDown) send(GuiIntent.CancelRegistration) } else false
    },
  )
  LaunchedEffect(Unit) { field.requestFocus() }
}
