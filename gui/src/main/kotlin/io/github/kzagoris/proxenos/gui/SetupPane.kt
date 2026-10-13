package io.github.kzagoris.proxenos.gui

import androidx.compose.animation.*
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
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import io.github.kzagoris.proxenos.coreapi.*
import io.github.kzagoris.proxenos.frontend.Attachment
import io.github.kzagoris.proxenos.frontend.Reason
import io.github.kzagoris.proxenos.frontend.Wording
import io.github.kzagoris.proxenos.gui.res.*
import kotlinx.coroutines.delay

private const val PLATFORM = "https://platform.openai.com/settings/organization/tunnels"
private const val CHATGPT = "https://chatgpt.com/"

/** A page of the flow: a step, and for Tunnel which of its three parts. Ordered as the flow runs. */
private data class SetupPage(val step: SetupStep, val part: Int) : Comparable<SetupPage> {
  override fun compareTo(other: SetupPage) = compareValuesBy(this, other, { it.step }, { it.part })
}

@Composable
internal fun SetupPane(state: GuiState, send: (GuiIntent) -> Unit, chooser: FolderChooser?) {
  val progress = state.setup ?: return
  val colours = MaterialTheme.colorScheme
  // Held above the pages, so a typed tunnel ID survives moving between the Tunnel parts.
  var tunnelId by remember(state.setupTunnelId) { mutableStateOf(state.setupTunnelId) }
  val navigate = { step: SetupStep -> send(GuiIntent.SetupNavigate(step, if (step == SetupStep.Tunnel) progress.tunnelPart else 0)) }
  Column(Modifier.fillMaxSize()) {
    Row(Modifier.fillMaxWidth().hairlineBottom(colours.outlineVariant).padding(16.dp),
      verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
      Text("Proxenos / Setup", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
      Btn("Finish later", { send(GuiIntent.FinishLater) }, enabled = !state.setupBusy && !state.dialogOpen)
    }
    BoxWithConstraints(Modifier.weight(1f)) {
      val compact = maxWidth < 720.dp
      val page = @Composable { modifier: Modifier ->
        Column(modifier) {
          Box(Modifier.fillMaxWidth().height(3.dp)) {
            if (state.setupBusy || state.inFlight != null) LinearProgressIndicator(Modifier.fillMaxSize())
          }
          // Forward slides in from the right, Back from the left; each page starts at its top.
          val slideIn = motion<IntOffset>(240)
          val slideOut = motion<IntOffset>(200)
          val fadeIn = motion<Float>(240, 60)
          val fadeOut = motion<Float>(140)
          AnimatedContent(SetupPage(progress.step, progress.tunnelPart), Modifier.weight(1f).fillMaxWidth(),
            transitionSpec = {
              val way = if (targetState > initialState) 1 else -1
              (slideInHorizontally(slideIn) { way * it / 10 } + fadeIn(fadeIn)) togetherWith
                (slideOutHorizontally(slideOut) { -way * it / 10 } + fadeOut(fadeOut))
            }, label = "setup page") { shown ->
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), horizontalAlignment = Alignment.CenterHorizontally) {
              Column(Modifier.widthIn(max = 720.dp).fillMaxWidth().padding(if (compact) 20.dp else 36.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text("Step ${shown.step.ordinal + 1} of ${SetupStep.entries.size}", color = colours.primary,
                  style = MaterialTheme.typography.labelLarge)
                state.setupError?.let { key(it) { Appear { SetupNote(it, Tone.Warn) } } }
                when (shown.step) {
                  SetupStep.Welcome -> SetupWelcome(state, send)
                  SetupStep.Tunnel -> SetupTunnel(state, send, shown.part, tunnelId) { tunnelId = it }
                  SetupStep.Workspace -> SetupWorkspace(state, send, chooser)
                  SetupStep.ChatGPT -> SetupChatGPT(state, send)
                  SetupStep.TryIt -> SetupTry(state, send)
                }
                if (shown.step != SetupStep.Welcome) {
                  val (previous, part) = if (shown.step == SetupStep.Tunnel && shown.part > 0) SetupStep.Tunnel to shown.part - 1
                    else SetupStep.entries[shown.step.ordinal - 1] to 0
                  Btn("Back", { send(GuiIntent.SetupNavigate(previous, part)) },
                    enabled = !state.setupBusy && state.setupLock(previous) == null, icon = Res.drawable.arrow_back)
                }
              }
            }
          }
        }
      }
      if (compact) Column(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxWidth().hairlineBottom(colours.outlineVariant).padding(horizontal = 12.dp, vertical = 8.dp)) {
          StepTrack(progress, state::setupLock, !state.setupBusy, navigate)
        }
        page(Modifier.weight(1f).fillMaxWidth())
      } else Row(Modifier.fillMaxSize()) {
        Column(Modifier.width(232.dp).fillMaxHeight().background(colours.surfaceContainerLow).hairlineEnd(colours.outlineVariant)
          .padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
          Text("Connect to ChatGPT", style = MaterialTheme.typography.labelLarge, color = colours.onSurfaceVariant)
          StepRail(progress, state::setupLock, !state.setupBusy, navigate)
          Spacer(Modifier.weight(1f))
          Text("You can leave and continue later. Completed setup steps stay here.", style = MaterialTheme.typography.bodySmall, color = colours.onSurfaceVariant)
        }
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
internal fun SetupLink(label: String, url: String) {
  val handler = LocalUriHandler.current
  var failed by remember { mutableStateOf(false) }
  Btn("$label ↗", {
    try { handler.openUri(url); failed = false } catch (_: Exception) { failed = true }
  })
  if (failed) SelectionContainer { Text("Could not open your browser. Open $url", color = LocalStatus.current.warn) }
}

@Composable
internal fun SetupCopy(label: String, value: String) {
  val clipboard = LocalClipboardManager.current
  var copied by remember(value) { mutableStateOf(false) }
  var failed by remember { mutableStateOf(false) }
  // The confirmation is momentary, so the same value can be copied again.
  LaunchedEffect(copied) { if (copied) { delay(2000); copied = false } }
  Btn(if (copied) "Copied" else label, {
    try { clipboard.setText(AnnotatedString(value)); copied = true } catch (_: Exception) { failed = true }
  }, icon = if (copied) Res.drawable.check else null)
  if (failed) Text("Select the text and copy it with Ctrl+C.", color = LocalStatus.current.warn)
}

@Composable
private fun SetupWelcome(state: GuiState, send: (GuiIntent) -> Unit) {
  SetupTitle("Your local work, in ChatGPT", "Choose what ChatGPT can use on this machine. We’ll help you set up the connection and try your first request.")
  ConnectionDiagram()
  Section("Before you start")
  when (val checks = state.setupChecks) {
    null -> Requirement("Checking local requirements…", null)
    else -> if (checks.isEmpty()) Requirement("Proxenos and the tunnel client are installed.", Tone.Ok)
      else checks.forEach { Requirement(it, Tone.Warn) }
  }
  Btn("Check local requirements", { send(GuiIntent.CheckSetup) })
  Text("You’ll need access to OpenAI Platform tunnel settings and custom MCP plugins in your ChatGPT workspace. Your workspace may require an administrator to enable this.")
  SetupNote("Let ChatGPT inspect a Workspace before deciding whether it may change files or run programs.", title = "Start with Read")
  FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
    Btn("Use an existing tunnel", { send(GuiIntent.SetupNavigate(SetupStep.Tunnel, 1)) })
    Btn("Set up a new tunnel", { send(GuiIntent.SetupNavigate(SetupStep.Tunnel)) }, primary = true)
  }
}

@Composable
private fun SetupTunnel(state: GuiState, send: (GuiIntent) -> Unit, part: Int, id: String, setId: (String) -> Unit) {
  val progress = state.setup!!
  var key by remember { mutableStateOf("") }
  var showKey by remember { mutableStateOf(false) }
  var editing by remember { mutableStateOf(false) }
  LaunchedEffect(state.setupCredentialRevision) {
    editing = false
    key = ""
    showKey = false
  }
  val tunnel = state.snapshot?.runtime?.state
  val saved = state.setupSavedTunnelId == state.setupTunnelId && state.setupHasCredentials && !editing
  SetupTitle(when (part) { 0 -> "Create your tunnel"; 1 -> "Choose the ChatGPT workspace"; else -> "Connect this machine" },
    if (part == 2 && saved) "Your credentials are saved. Watch the connection status below, then choose a Workspace."
    else "A tunnel carries requests between ChatGPT and Proxenos. Complete the Platform steps, then return here.")
  PartTrack(progress)
  if (part < 2 || !saved) FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
    SetupLink("Open Platform tunnel settings", PLATFORM)
    SetupCopy("Copy Platform tunnel settings link", PLATFORM)
  }
  when (part) {
    0 -> {
      Text("Create a tunnel with a name you’ll recognize, such as Proxenos · My computer. Copy its tunnel ID here.")
      CreationGuide()
      TunnelIdGuide()
      OutlinedTextField(id, setId, label = { Text("Tunnel ID") }, singleLine = true, modifier = Modifier.fillMaxWidth())
      Text("Creating a tunnel requires Tunnels Read + Manage in the intended Platform organization. Using it requires Read + Use.",
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
      Btn("I created the tunnel", { send(GuiIntent.SetupNavigate(SetupStep.Tunnel, 1, id)) }, enabled = id.isNotBlank(), primary = true)
    }
    1 -> {
      Text("Open the tunnel you created or want to reuse. Copy its ID, then review or edit its associations to include the ChatGPT workspace where you want to use Proxenos.")
      TunnelIdGuide()
      OutlinedTextField(id, setId, label = { Text("Tunnel ID") }, singleLine = true, modifier = Modifier.fillMaxWidth())
      AssociationGuide()
      SetupNote("A tunnel associated only with a Platform organization may not appear in your intended ChatGPT workspace.", Tone.Warn)
      Row(verticalAlignment = Alignment.CenterVertically) {
        Checkbox(progress.associated, { send(GuiIntent.SetupAssociated(it)) })
        Text("I associated the intended ChatGPT workspace.")
      }
      Btn("Continue", { send(GuiIntent.SetupNavigate(SetupStep.Tunnel, 2, id)) }, enabled = progress.associated && id.isNotBlank(), primary = true)
    }
    else -> {
      if (saved) Appear { SetupNote(title = "Credentials saved on this machine", tone = Tone.Ok, icon = Res.drawable.lock) {
        SelectionContainer { Text("Tunnel ID: ${state.setupSavedTunnelId}", fontFamily = LocalMono.current) }
        Text("Runtime key: saved securely (hidden)")
        Text("You do not need to enter these values again.", style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant)
      } }
      // A new connection state fades in where the last one was.
      key(tunnel?.let { it::class }) { Appear { Column(verticalArrangement = Arrangement.spacedBy(16.dp)) { when (tunnel) {
        RuntimeState.Connected -> SetupNote("The last successful poll is recent. This checks the tunnel link; create the plugin and try a ChatGPT request next.",
          Tone.Ok, title = "Tunnel · Connected")
        RuntimeState.Connecting -> SetupNote("No successful poll yet.", title = "Tunnel · Connecting", busy = true) {
          state.snapshot?.let { snapshot -> snapshot.connectingWords?.let { words ->
            SelectionContainer { Column { Wording.connecting(clock.format(snapshot.runtime.enteredAt), words).forEach {
              Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } } }
          } }
        }
        is RuntimeState.Failed -> SetupNote(Wording.complaint(tunnel.complaint), Tone.Bad)
        RuntimeState.Disconnected -> {
          Text("The tunnel is Disconnected by choice. Access Levels are unchanged.")
          Btn("Connect", { send(GuiIntent.ConnectTunnel) }, enabled = state.canConnect)
        }
        null -> {
          (state.attachment as? Attachment.Absent)?.reason?.let { if (it is Reason.StartFailed)
            SetupNote(it.words, Tone.Bad,
              title = if (saved) "Credentials are saved, but the Runtime could not start." else "The Runtime could not start.") }
          if (state.attachment == Attachment.Starting || state.attachment == Attachment.Attaching)
            Requirement(state.runtimeWords, null)
        }
      } } } }
      if (saved || (tunnel == RuntimeState.Connected && !editing && !state.setupHasCredentials)) {
        if (tunnel == RuntimeState.Connected) {
          Btn("Choose a Workspace", { send(GuiIntent.SetupNavigate(SetupStep.Workspace)) }, primary = true)
          Text(Wording.CONNECTED_CAVEAT, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
          Text("Saving confirms local storage. Tunnel · Connected confirms the tunnel ID and key work with OpenAI.",
            color = MaterialTheme.colorScheme.onSurfaceVariant)
          if (state.attachment is Attachment.Absent) Btn("Start with saved credentials", { send(GuiIntent.StartRuntime) }, enabled = state.canStart, primary = true)
        }
        TextBtn(onClick = { editing = true }, enabled = !state.setupBusy && state.inFlight == null &&
          state.attachment != Attachment.Starting && state.attachment != Attachment.Attaching) { Text("Change credentials") }
        return
      }
      else Text("Continue when the Tunnel is Connected. Check the tunnel ID, runtime key, intended Platform organization and tunnel access if it keeps waiting.")
      HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
      if (state.setupHasCredentials && state.setupSavedTunnelId != state.setupTunnelId) {
        SetupNote("Saved credentials belong to ${state.setupSavedTunnelId}. To use ${state.setupTunnelId}, save its runtime key below.", Tone.Warn)
      }
      Text("Use a saved runtime API key or create a new one in Platform → Runtime API keys. The key is separate from the tunnel ID. Values are saved only when you choose Save & connect.")
      RuntimeKeyGuide()
      OutlinedTextField(id, setId, label = { Text("Tunnel ID") }, singleLine = true, modifier = Modifier.fillMaxWidth())
      OutlinedTextField(key, { key = it }, label = { Text("Runtime key") }, singleLine = true,
        visualTransformation = if (showKey) VisualTransformation.None else PasswordVisualTransformation(),
        trailingIcon = { TextBtn(onClick = { showKey = !showKey }) { Text(if (showKey) "Hide" else "Show") } }, modifier = Modifier.fillMaxWidth())
      Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        Ic(Res.drawable.lock, MaterialTheme.colorScheme.onSurfaceVariant, 16.dp)
        Text("Your key is saved locally in the credentials file, readable only by your user account. It is never copied to ChatGPT.",
          style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
      }
      if (state.snapshot != null) {
        SetupNote("The running Runtime keeps its current credentials. To change them, Stop Runtime explicitly, then save and start it again. Stop ends running Operations as Uncertain; registrations and Access Levels survive.", Tone.Warn)
        Btn("Stop Runtime…", { send(GuiIntent.AskStop) }, enabled = state.canStop)
      } else {
        Btn("Save & connect", {
          send(GuiIntent.SaveCredentials(id, key))
        }, enabled = state.canStart && id.isNotBlank() && key.isNotBlank(), primary = true)
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
    SetupNote(title = "${selected.workspace.name} · Access Level ${selected.workspace.accessLevel}",
      tone = if (selected.broken) Tone.Bad else Tone.Info, icon = if (selected.broken) null else Res.drawable.folder) {
      SelectionContainer { Text("Root: ${selected.workspace.root}", fontFamily = LocalMono.current) }
      if (selected.broken) Text("Broken: re-confirm the Root in Workspaces.")
    }
    Btn("Add another Workspace", { send(GuiIntent.AddWorkspace) }, enabled = state.canAdd)
  }
  state.snapshot?.workspaces.orEmpty().filter { it.usable }.forEach {
    TextBtn(onClick = { send(GuiIntent.SetupChoose(it.workspace.id)) }, enabled = state.inFlight == null) {
      Text("Use ${it.workspace.name} · ${it.workspace.accessLevel}")
    }
  }
  Text("None withholds the Workspace. Write allows file changes. Command runs programs with the full authority of your Linux account, beyond the Root. Change Access Levels later in Workspaces.",
    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
  Btn("Continue to ChatGPT", { send(GuiIntent.SetupNavigate(SetupStep.ChatGPT)) },
    enabled = selected?.usable == true, primary = true)
}

@Composable
private fun SetupChatGPT(state: GuiState, send: (GuiIntent) -> Unit) {
  val tunnelId = state.setupSavedTunnelId ?: state.setupTunnelId
  SetupTitle("Add Proxenos to ChatGPT", "Create a custom MCP plugin using your tunnel. Keep the Runtime running while you do this.")
  Text("1. Open ChatGPT → Plugins → + → Add custom MCP server.\n2. Enter these settings, review ChatGPT’s warning, then choose Create as a plugin.")
  ValueCard(listOf("Name" to "Proxenos", "Connection" to "Tunnel",
    "Tunnel ID" to tunnelId.ifBlank { "Use the tunnel configured for this Runtime" }, "Authentication" to "No authentication"))
  FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
    SetupCopy("Copy name", "Proxenos")
    if (tunnelId.isNotBlank()) SetupCopy("Copy tunnel ID", tunnelId)
    SetupLink("Open ChatGPT", CHATGPT)
  }
  Section("My tunnel isn’t listed")
  Text("Check that it is associated with this ChatGPT workspace, and that you have Tunnels Read + Use. If available, paste the tunnel ID directly.")
  Section("I can’t find the option to add a plugin")
  Text("Check your workspace’s custom MCP access. If your account requires developer mode, enable it in ChatGPT settings. Ask your workspace administrator if the option is unavailable.")
  SetupNote("Proxenos cannot inspect your ChatGPT settings.", title = "“I created the plugin” records your word, not a measurement.")
  Btn("I created the plugin", { send(GuiIntent.SetupConfirmPlugin) }, enabled = state.snapshot != null && state.inFlight == null, primary = true)
}

@Composable
private fun SetupTry(state: GuiState, send: (GuiIntent) -> Unit) {
  val progress = state.setup!!
  val evidence = progress.evidence(state.snapshot)
  if (evidence == null) {
    SetupTitle("Try your first ChatGPT request", "Use the plugin in a conversation and watch an Operation appear here.")
    Text("1. Install the Proxenos plugin in ChatGPT if prompted.\n2. Start a conversation, type @, and select Proxenos.\n3. Send this prompt:")
    Shaded { SelectionContainer { Text(progress.prompt(state.snapshot), fontFamily = LocalMono.current) } }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
      SetupCopy("Copy prompt", progress.prompt(state.snapshot))
      SetupLink("Open ChatGPT", CHATGPT)
    }
    SetupNote("The Operation will also appear in Activity.", title = "Waiting for a successful ChatGPT Root listing",
      busy = state.snapshot?.runtime?.state == RuntimeState.Connected && progress.selected(state.snapshot) != null)
    if (progress.selected(state.snapshot) == null) Text("Your selected Workspace is unavailable. Return to Workspace and choose one.", color = LocalStatus.current.warn)
    if (state.snapshot == null) Btn("Review tunnel setup", { send(GuiIntent.SetupNavigate(SetupStep.Tunnel, 2)) })
    Text("If nothing appears, confirm the plugin is selected, the Tunnel is Connected and the Workspace is at Read or higher. Check ChatGPT for a tool error. A frontend Try does not count.",
      style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
  } else {
    SetupTitle("Your first request arrived", "ChatGPT successfully listed the selected Root through Proxenos.")
    Appear { SetupNote("${evidence.workspace} · ${evidence.tool} · ${clock.format(evidence.at)} · ok", Tone.Ok, title = "ChatGPT Operation received") }
    Text("This is evidence at that moment, not a promise of ongoing reachability. Proxenos cannot identify which conversation sent it.")
    Btn("View in Activity", { send(GuiIntent.ShowActivity(evidence.id)) })
    Text("Try asking ChatGPT to summarize files in your Workspace. Review Operations in Activity, and change Access Levels from Workspaces when you want ChatGPT to do more.")
    SetupNote("Closing this window leaves the Runtime running and Access Levels unchanged. After a reboot, open Proxenos to start the Runtime again.")
    Btn("Open Workspaces", { send(GuiIntent.CompleteSetup) }, primary = true)
  }
}
