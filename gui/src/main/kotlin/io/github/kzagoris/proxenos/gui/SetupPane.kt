package io.github.kzagoris.proxenos.gui

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import io.github.kzagoris.proxenos.coreapi.*
import io.github.kzagoris.proxenos.frontend.Attachment
import io.github.kzagoris.proxenos.frontend.Reason
import io.github.kzagoris.proxenos.frontend.Wording

private const val PLATFORM = "https://platform.openai.com/settings/organization/tunnels"
private const val CHATGPT = "https://chatgpt.com/"

@Composable
internal fun SetupPane(state: GuiState, send: (GuiIntent) -> Unit, chooser: FolderChooser?) {
  val progress = state.setup ?: return
  val colours = MaterialTheme.colorScheme
  Column(Modifier.fillMaxSize()) {
    Row(Modifier.fillMaxWidth().hairlineBottom(colours.outlineVariant).padding(16.dp),
      verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
      Text("Proxenos / Setup", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
      Btn("Finish later", { send(GuiIntent.FinishLater) }, enabled = !state.setupBusy && !state.dialogOpen)
    }
    BoxWithConstraints(Modifier.weight(1f)) {
      val compact = maxWidth < 720.dp
      val steps = @Composable { modifier: Modifier ->
        Column(modifier.background(colours.surfaceContainerLow).padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
          Text("Connect to ChatGPT", style = MaterialTheme.typography.labelLarge, color = colours.onSurfaceVariant)
          SetupStep.entries.forEachIndexed { index, step ->
            val selected = step == progress.step
            TextButton(onClick = { send(GuiIntent.SetupNavigate(step, if (step == SetupStep.Tunnel) progress.tunnelPart else 0)) },
              enabled = index <= maxOf(progress.furthest, progress.step).ordinal && !state.setupBusy,
              colors = ButtonDefaults.textButtonColors(containerColor = if (selected) colours.primaryContainer else colours.surfaceContainerLow),
              modifier = Modifier.fillMaxWidth()) {
              Text("${index + 1}   ${step.label}", Modifier.fillMaxWidth(), color = if (selected) colours.primary else colours.onSurfaceVariant)
            }
          }
          if (!compact) {
            Spacer(Modifier.weight(1f))
            Text("You can leave and continue later. Completed setup steps stay here.", style = MaterialTheme.typography.bodySmall, color = colours.onSurfaceVariant)
          }
        }
      }
      val page = @Composable { modifier: Modifier ->
        Column(modifier.verticalScroll(rememberScrollState()).padding(if (compact) 20.dp else 36.dp),
          verticalArrangement = Arrangement.spacedBy(16.dp)) {
          Text("Step ${progress.step.ordinal + 1} of 5", color = colours.primary, style = MaterialTheme.typography.labelLarge)
          state.setupError?.let { SetupNote(it, warning = true) }
          if (state.setupBusy || state.inFlight != null) LinearProgressIndicator(Modifier.fillMaxWidth())
          when (progress.step) {
            SetupStep.Welcome -> SetupWelcome(state, send)
            SetupStep.Tunnel -> SetupTunnel(state, send)
            SetupStep.Workspace -> SetupWorkspace(state, send, chooser)
            SetupStep.ChatGPT -> SetupChatGPT(state, send)
            SetupStep.TryIt -> SetupTry(state, send)
          }
          if (progress.step != SetupStep.Welcome) TextButton(onClick = {
            if (progress.step == SetupStep.Tunnel && progress.tunnelPart > 0)
              send(GuiIntent.SetupNavigate(SetupStep.Tunnel, progress.tunnelPart - 1))
            else send(GuiIntent.SetupNavigate(SetupStep.entries[progress.step.ordinal - 1]))
          }, enabled = !state.setupBusy) { Text("← Back") }
        }
      }
      if (compact) Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp)) {
          SetupStep.entries.forEachIndexed { index, step ->
            TextButton(onClick = { send(GuiIntent.SetupNavigate(step, if (step == SetupStep.Tunnel) progress.tunnelPart else 0)) },
              enabled = index <= maxOf(progress.furthest, progress.step).ordinal && !state.setupBusy) {
              Text("${index + 1} ${step.label}", color = if (step == progress.step) colours.primary else colours.onSurfaceVariant)
            }
          }
        }
        page(Modifier.weight(1f).fillMaxWidth())
      } else Row(Modifier.fillMaxSize()) {
        steps(Modifier.width(220.dp).fillMaxHeight().hairlineEnd(colours.outlineVariant))
        page(Modifier.weight(1f).fillMaxHeight())
      }
    }
    Box(Modifier.fillMaxWidth().hairlineTop(colours.outlineVariant).horizontalScroll(rememberScrollState()).padding(12.dp)) {
      StageStrip(state, send)
    }
  }
}

@Composable
private fun SetupTitle(title: String, description: String) {
  Text(title, style = MaterialTheme.typography.headlineSmall)
  Text(description, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun SetupNote(text: String, warning: Boolean = false) {
  Surface(color = if (warning) MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.primaryContainer,
    shape = MaterialTheme.shapes.small, modifier = Modifier.fillMaxWidth()) {
    Text(text, Modifier.padding(16.dp), color = if (warning) LocalStatus.current.warn else MaterialTheme.colorScheme.onSurface)
  }
}

@Composable
private fun SetupLink(label: String, url: String) {
  val handler = LocalUriHandler.current
  var failed by remember { mutableStateOf(false) }
  Btn("$label ↗", {
    try { handler.openUri(url); failed = false } catch (_: Exception) { failed = true }
  })
  if (failed) SelectionContainer { Text("Could not open your browser. Open $url", color = LocalStatus.current.warn) }
}

@Composable
private fun SetupCopy(label: String, value: String) {
  val clipboard = LocalClipboardManager.current
  var copied by remember(value) { mutableStateOf(false) }
  var failed by remember { mutableStateOf(false) }
  Btn(if (copied) "Copied" else label, {
    try { clipboard.setText(AnnotatedString(value)); copied = true } catch (_: Exception) { failed = true }
  })
  if (failed) Text("Select the text and copy it with Ctrl+C.", color = LocalStatus.current.warn)
}

@Composable
private fun SetupWelcome(state: GuiState, send: (GuiIntent) -> Unit) {
  SetupTitle("Your local work, in ChatGPT", "Choose what ChatGPT can use on this machine. We’ll help you set up the connection and try your first request.")
  SetupNote("This machine · Proxenos + your Workspaces\n↔ Secure tunnel · Outbound connection to OpenAI\n↔ ChatGPT · Your Proxenos plugin")
  Section("Before you start")
  when (val checks = state.setupChecks) {
    null -> Text("Checking local requirements…")
    else -> if (checks.isEmpty()) Text("✓ Proxenos and the tunnel client are installed.") else checks.forEach { Text(it, color = LocalStatus.current.warn) }
  }
  Btn("Check local requirements", { send(GuiIntent.CheckSetup) })
  Text("You’ll need access to OpenAI Platform tunnel settings and custom MCP plugins in your ChatGPT workspace. Your workspace may require an administrator to enable this.")
  SetupNote("Start with Read. Let ChatGPT inspect a Workspace before deciding whether it may change files or run programs.")
  FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
    Btn("Use an existing tunnel", { send(GuiIntent.SetupNavigate(SetupStep.Tunnel, 1)) })
    Btn("Set up a new tunnel", { send(GuiIntent.SetupNavigate(SetupStep.Tunnel)) }, primary = true)
  }
}

@Composable
private fun SetupTunnel(state: GuiState, send: (GuiIntent) -> Unit) {
  val progress = state.setup!!
  var id by remember(state.setupTunnelId) { mutableStateOf(state.setupTunnelId) }
  var key by remember { mutableStateOf("") }
  var showKey by remember { mutableStateOf(false) }
  var editing by remember { mutableStateOf(false) }
  val tunnel = state.snapshot?.runtime?.state
  SetupTitle(when (progress.tunnelPart) { 0 -> "Create your tunnel"; 1 -> "Choose the ChatGPT workspace"; else -> "Connect this machine" },
    "A tunnel carries requests between ChatGPT and Proxenos. Complete the Platform steps, then return here.")
  Text("1. Create tunnel   /   2. Associate workspace   /   3. Connect", style = MaterialTheme.typography.bodySmall)
  SetupLink("Open Platform tunnel settings", PLATFORM)
  when (progress.tunnelPart) {
    0 -> {
      Text("Create a tunnel with a name you’ll recognize, such as Proxenos · My computer. Copy its tunnel ID here.")
      OutlinedTextField(id, { id = it }, label = { Text("Tunnel ID") }, singleLine = true, modifier = Modifier.fillMaxWidth())
      Text("Creating a tunnel requires Tunnels Read + Manage in the intended Platform organization. Using it requires Read + Use.")
      Btn("I created the tunnel", { send(GuiIntent.SetupNavigate(SetupStep.Tunnel, 1, id)) }, enabled = id.isNotBlank(), primary = true)
    }
    1 -> {
      Text("Open your tunnel’s associations and add the ChatGPT workspace where you want to use Proxenos.")
      SetupNote("A tunnel associated only with a Platform organization may not appear in your intended ChatGPT workspace.")
      Row(verticalAlignment = Alignment.CenterVertically) {
        Checkbox(progress.associated, { send(GuiIntent.SetupAssociated(it)) })
        Text("I associated the intended ChatGPT workspace.")
      }
      Btn("Continue", { send(GuiIntent.SetupNavigate(SetupStep.Tunnel, 2)) }, enabled = progress.associated, primary = true)
    }
    else -> {
      when (tunnel) {
        RuntimeState.Connected -> SetupNote("✓ Tunnel · Connected\nThe last successful poll is recent. This checks the tunnel link; create the plugin and try a ChatGPT request next.")
        RuntimeState.Connecting -> {
          SetupNote("Tunnel · Connecting\nNo successful poll yet.")
          state.snapshot?.let { snapshot -> snapshot.connectingWords?.let { words ->
            SelectionContainer { Column { Wording.connecting(clock.format(snapshot.runtime.enteredAt), words).forEach { Text(it) } } }
          } }
        }
        is RuntimeState.Failed -> SetupNote(Wording.complaint(tunnel.complaint), warning = true)
        RuntimeState.Disconnected -> {
          Text("The tunnel is Disconnected by choice. Access Levels are unchanged.")
          Btn("Connect", { send(GuiIntent.ConnectTunnel) }, enabled = state.canConnect)
        }
        null -> {
          (state.attachment as? Attachment.Absent)?.reason?.let { if (it is Reason.StartFailed) Text(it.words, color = LocalStatus.current.warn) }
          if (state.attachment == Attachment.Starting || state.attachment == Attachment.Attaching) Text(state.runtimeWords)
        }
      }
      if (tunnel == RuntimeState.Connected && !editing) {
        Btn("Choose a Workspace", { send(GuiIntent.SetupNavigate(SetupStep.Workspace)) }, primary = true)
        TextButton(onClick = { editing = true }) { Text("Review tunnel values") }
        Text(Wording.CONNECTED_CAVEAT, color = MaterialTheme.colorScheme.onSurfaceVariant)
        return
      }
      else Text("Continue when the Tunnel is Connected. Check the tunnel ID, runtime key, intended Platform organization and tunnel access if it keeps waiting.")
      HorizontalDivider()
      Text("Create a runtime key for this tunnel in Platform, then paste it below. Values are saved only when you choose Save & connect.")
      OutlinedTextField(id, { id = it }, label = { Text("Tunnel ID") }, singleLine = true, modifier = Modifier.fillMaxWidth())
      OutlinedTextField(key, { key = it }, label = { Text("Runtime key") }, singleLine = true,
        visualTransformation = if (showKey) VisualTransformation.None else PasswordVisualTransformation(),
        trailingIcon = { TextButton(onClick = { showKey = !showKey }) { Text(if (showKey) "Hide" else "Show") } }, modifier = Modifier.fillMaxWidth())
      Text("Your key is saved locally in the credentials file, readable only by your user account. It is never copied to ChatGPT.", style = MaterialTheme.typography.bodySmall)
      if (state.snapshot != null) {
        SetupNote("The running Runtime keeps its current credentials. To change them, Stop Runtime explicitly, then save and start it again. Stop ends running Operations as Uncertain; registrations and Access Levels survive.", warning = true)
        Btn("Stop Runtime…", { send(GuiIntent.AskStop) }, enabled = state.canStop)
      } else {
        Btn("Save & connect", {
          send(GuiIntent.SaveCredentials(id, key)); key = ""; showKey = false
        }, enabled = state.canStart && id.isNotBlank() && key.isNotBlank(), primary = true)
        if (state.setupHasCredentials) Btn("Start with saved credentials", { send(GuiIntent.StartRuntime) }, enabled = state.canStart)
      }
      Text("Saving starts the Runtime and connects the tunnel. Existing Workspaces keep their Access Levels.", color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
  }
}

@Composable
private fun SetupWorkspace(state: GuiState, send: (GuiIntent) -> Unit, chooser: FolderChooser?) {
  val progress = state.setup!!
  val selected = progress.selected(state.snapshot)
  SetupTitle("Choose your first Workspace", "A Workspace is a named registration of one local directory. Its Root is the directory ChatGPT can inspect.")
  Text("A new Workspace starts at Read: list, read and search files. It cannot change files or run programs.")
  if (state.snapshot == null) {
    Text("Start the Runtime to register or choose a Workspace.")
    Btn("Review tunnel setup", { send(GuiIntent.SetupNavigate(SetupStep.Tunnel, 2)) })
    return
  }
  if (selected == null) SetupWorkspaceForm(state, send, chooser) else {
    SetupNote("${selected.workspace.name} · Access Level ${selected.workspace.accessLevel}\nRoot: ${selected.workspace.root}" + if (selected.broken) "\nBroken: re-confirm the Root in Workspaces." else "")
    Btn("Add another Workspace", { send(GuiIntent.AddWorkspace) }, enabled = state.canAdd)
  }
  state.snapshot?.workspaces.orEmpty().filter { !it.broken && it.workspace.accessLevel != AccessLevel.None }.forEach {
    TextButton(onClick = { send(GuiIntent.SetupChoose(it.workspace.id)) }, enabled = state.inFlight == null) {
      Text("Use ${it.workspace.name} · ${it.workspace.accessLevel}")
    }
  }
  Text("None withholds the Workspace. Write allows file changes. Command runs programs with the full authority of your Linux account, beyond the Root. Change Access Levels later in Workspaces.", style = MaterialTheme.typography.bodySmall)
  Btn("Continue to ChatGPT", { send(GuiIntent.SetupNavigate(SetupStep.ChatGPT)) },
    enabled = selected != null && !selected.broken && selected.workspace.accessLevel != AccessLevel.None, primary = true)
}

@Composable
private fun SetupChatGPT(state: GuiState, send: (GuiIntent) -> Unit) {
  SetupTitle("Add Proxenos to ChatGPT", "Create a custom MCP plugin using your tunnel. Keep the Runtime running while you do this.")
  Text("1. Open ChatGPT → Plugins → + → Add custom MCP server.\n2. Enter these settings, review ChatGPT’s warning, then choose Create as a plugin.")
  SelectionContainer { Text("Name: Proxenos\nConnection: Tunnel\nTunnel ID: ${state.setupTunnelId.ifBlank { "Use the tunnel configured for this Runtime" }}\nAuthentication: No authentication") }
  FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
    SetupCopy("Copy name", "Proxenos")
    if (state.setupTunnelId.isNotBlank()) SetupCopy("Copy tunnel ID", state.setupTunnelId)
    SetupLink("Open ChatGPT", CHATGPT)
  }
  Section("My tunnel isn’t listed")
  Text("Check that it is associated with this ChatGPT workspace, and that you have Tunnels Read + Use. If available, paste the tunnel ID directly.")
  Section("I can’t find the option to add a plugin")
  Text("Check your workspace’s custom MCP access. If your account requires developer mode, enable it in ChatGPT settings. Ask your workspace administrator if the option is unavailable.")
  SetupNote("“I created the plugin” records your word, not a measurement. Proxenos cannot inspect your ChatGPT settings.")
  Btn("I created the plugin", { send(GuiIntent.SetupConfirmPlugin) }, enabled = state.snapshot != null && state.inFlight == null, primary = true)
}

@Composable
private fun SetupTry(state: GuiState, send: (GuiIntent) -> Unit) {
  val progress = state.setup!!
  val evidence = progress.evidence(state.snapshot)
  if (evidence == null) {
    SetupTitle("Try your first ChatGPT request", "Use the plugin in a conversation and watch an Operation appear here.")
    Text("1. Install the Proxenos plugin in ChatGPT if prompted.\n2. Start a conversation, type @, and select Proxenos.\n3. Send this prompt:")
    SelectionContainer { SetupNote(progress.prompt(state.snapshot)) }
    SetupCopy("Copy prompt", progress.prompt(state.snapshot))
    SetupLink("Open ChatGPT", CHATGPT)
    SetupNote("Waiting for a successful ChatGPT Root listing\nThe Operation will also appear in Activity.")
    if (progress.selected(state.snapshot) == null) Text("Your selected Workspace is unavailable. Return to Workspace and choose one.", color = LocalStatus.current.warn)
    if (state.snapshot == null) Btn("Review tunnel setup", { send(GuiIntent.SetupNavigate(SetupStep.Tunnel, 2)) })
    Text("If nothing appears, confirm the plugin is selected, the Tunnel is Connected and the Workspace is at Read or higher. Check ChatGPT for a tool error. A frontend Try does not count.")
  } else {
    SetupTitle("Your first request arrived", "ChatGPT successfully listed the selected Root through Proxenos.")
    SetupNote("✓ ChatGPT Operation received\n${evidence.workspace} · ${evidence.tool} · ${clock.format(evidence.at)} · ok")
    Text("This is evidence at that moment, not a promise of ongoing reachability. Proxenos cannot identify which conversation sent it.")
    Btn("View in Activity", { send(GuiIntent.ShowActivity(evidence.id)) })
    Text("Try asking ChatGPT to summarize files in your Workspace. Review Operations in Activity, and change Access Levels from Workspaces when you want ChatGPT to do more.")
    SetupNote("Closing this window leaves the Runtime running and Access Levels unchanged. After a reboot, open Proxenos to start the Runtime again.")
    Btn("Open Workspaces", { send(GuiIntent.CompleteSetup) }, primary = true)
  }
}
