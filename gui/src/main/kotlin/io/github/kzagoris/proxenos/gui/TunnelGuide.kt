package io.github.kzagoris.proxenos.gui

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

private const val TUNNEL_DOCS = "https://developers.openai.com/api/docs/guides/secure-mcp-tunnels"
private const val KEY_DOCS = "https://github.com/openai/tunnel-client/blob/master/docs/permissions.md"
private const val RUNTIME_KEYS = "https://platform.openai.com/settings/organization/api-keys"

@Composable
internal fun CreationGuide() = TunnelGuide("creation") {
  GuideStep(1, "Open Platform → Tunnels → Create tunnel", "Choose the Platform organization that will manage the tunnel.")
  GuideStep(2, "Fill in the creation form", "Name and Description are both required. Use a name that identifies this computer.")
  Illustration("Create tunnel") {
    ExampleField("Name *", "Proxenos · My computer", 2)
    ExampleField("Description *", "Connect my local Proxenos Workspaces to ChatGPT", 2)
    ExampleField("Organizations", "Your Platform organization", 3)
    ExampleField("ChatGPT workspaces", "The ChatGPT workspace you will use", 3)
    Text("↓ Create", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge)
  }
  GuideStep(3, "Select the organization and ChatGPT workspace", "The ChatGPT workspace is the account context where you will add Proxenos. It is separate from a local Proxenos Workspace.")
  GuideStep(4, "Create, then copy the tunnel ID", "Return here and paste the ID below. Allow about 30 seconds for a new tunnel to become ready. Create the runtime API key separately in the connection step.")
  SetupCopy("Copy suggested name", "Proxenos · My computer")
  SetupCopy("Copy suggested description", "Connect my local Proxenos Workspaces to ChatGPT")
  GuideSource(TUNNEL_DOCS)
}

@Composable
internal fun TunnelIdGuide() = TunnelGuide("tunnel ID") {
  GuideStep(1, "Open the tunnel you want to use", "In Platform → Tunnels, select your existing tunnel or the one you just created.")
  Illustration("Find the tunnel ID") {
    Text("Proxenos · My computer", style = MaterialTheme.typography.titleSmall)
    ExampleField("Tunnel ID", "tunnel_0123456789abcdef0123456789abcdef", 2)
    Text("↓ Copy the ID → paste into Proxenos", color = MaterialTheme.colorScheme.primary)
  }
  GuideStep(2, "Copy the ID labelled tunnel_…", "Use the ID, rather than the tunnel name, organization ID or secret key. You can find this ID again later.")
  GuideSource(TUNNEL_DOCS)
}

@Composable
internal fun AssociationGuide() = TunnelGuide("association") {
  GuideStep(1, "Open the existing tunnel’s settings", "Select the tunnel in Platform → Tunnels, then open its edit or association controls.")
  Illustration("Review or edit associations") {
    ExampleField("Organizations", "Your Platform organization", 2)
    ExampleField("ChatGPT workspaces", "The workspace where you will add Proxenos", 2)
    Text("↓ Save the associations", color = MaterialTheme.colorScheme.primary)
  }
  GuideStep(2, "Include the intended ChatGPT workspace", "Keep the managing Platform organization included. Add the ChatGPT workspace if it is missing, then save. This keeps the same tunnel ID.")
  GuideStep(3, "Confirm below when you have checked it", "If you already selected the workspace during creation, just review it. If you cannot edit, ask the organization administrator for Tunnels Read + Manage. Running the tunnel needs Read + Use.")
  Text("If an enterprise workspace cannot be associated automatically, ask your OpenAI account team about the association.", style = MaterialTheme.typography.bodySmall)
  GuideSource(TUNNEL_DOCS)
}

@Composable
internal fun RuntimeKeyGuide() = TunnelGuide("runtime key") {
  GuideStep(1, "Open Runtime API keys in Platform", "Use the organization whose tunnel you selected. Runtime API keys and Admin API keys are separate; Proxenos needs a runtime key.")
  SetupLink("Open Runtime API keys", RUNTIME_KEYS)
  SetupCopy("Copy Runtime API keys link", RUNTIME_KEYS)
  Illustration("Two separate values for Proxenos") {
    ExampleField("Tunnels → tunnel ID", "tunnel_…")
    ExampleField("Runtime API keys → new secret key", "sk-••••••••  (example only)", 2)
    Text("↓ Paste ID + key into Proxenos → Save & connect", color = MaterialTheme.colorScheme.primary)
  }
  GuideStep(2, "Create a Restricted runtime API key", "Choose Restricted permissions and enable Tunnels Read + Use. The key’s owner also needs Read + Use for the intended organization. Copy the full secret when it is displayed, then paste it into Runtime key below.")
  GuideStep(3, "Already have a key?", "Reuse a valid key you saved. If Proxenos already has credentials, use Start with saved credentials instead of entering them again.")
  GuideStep(4, "Lost the secret? Create a replacement", "Platform does not reveal an old secret again. Keep the existing tunnel ID and create a new runtime API key. After the replacement works, revoke the lost key if it is no longer needed elsewhere.")
  Text("Paste the runtime key only into Proxenos. It is not needed in ChatGPT.", color = MaterialTheme.colorScheme.primary)
  GuideSource(KEY_DOCS)
}

@Composable
private fun TunnelGuide(name: String, content: @Composable ColumnScope.() -> Unit) {
  var expanded by rememberSaveable { mutableStateOf(false) }
  val colours = MaterialTheme.colorScheme
  Surface(color = colours.surfaceContainerLow, shape = MaterialTheme.shapes.medium,
    modifier = Modifier.fillMaxWidth().border(1.dp, colours.outlineVariant, MaterialTheme.shapes.medium)) {
    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
      TextBtn(onClick = { expanded = !expanded }) { Text("${if (expanded) "Hide" else "Show"} $name guide") }
      if (expanded) content()
    }
  }
}

@Composable
private fun GuideStep(number: Int, title: String, description: String) {
  Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
    Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = CircleShape, modifier = Modifier.size(28.dp)) {
      Box(contentAlignment = Alignment.Center) { Text(number.toString(), style = MaterialTheme.typography.labelLarge) }
    }
    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
      Text(title, style = MaterialTheme.typography.titleSmall)
      Text(description, style = MaterialTheme.typography.bodyMedium)
    }
  }
}

@Composable
private fun Illustration(title: String, content: @Composable ColumnScope.() -> Unit) {
  Surface(color = MaterialTheme.colorScheme.surface, shape = MaterialTheme.shapes.medium) {
    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
      Text("Illustration · example values", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
      Text(title, style = MaterialTheme.typography.titleMedium)
      content()
    }
  }
}

@Composable
private fun ExampleField(label: String, value: String, number: Int? = null) {
  Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
      Text(label, style = MaterialTheme.typography.labelMedium)
      Box(Modifier.fillMaxWidth().border(1.dp, MaterialTheme.colorScheme.outlineVariant, MaterialTheme.shapes.small).padding(12.dp)) {
        Text(value, style = MaterialTheme.typography.bodyMedium)
      }
    }
    if (number != null) Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = CircleShape, modifier = Modifier.size(28.dp)) {
      Box(contentAlignment = Alignment.Center) { Text(number.toString(), style = MaterialTheme.typography.labelLarge) }
    }
  }
}

@Composable
private fun GuideSource(url: String) {
  Text("Checked 13 October 2026 · Platform’s layout and labels may differ from these illustrations.",
    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
  SetupLink("Official instructions", url)
}
