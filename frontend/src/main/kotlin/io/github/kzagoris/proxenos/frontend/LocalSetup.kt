package io.github.kzagoris.proxenos.frontend

import io.github.kzagoris.proxenos.control.ConfigToml
import io.github.kzagoris.proxenos.control.TunnelClient
import io.github.kzagoris.proxenos.control.stateDirectory
import io.github.kzagoris.proxenos.coreapi.ActivityEntry
import io.github.kzagoris.proxenos.coreapi.ActivityOutcome
import io.github.kzagoris.proxenos.coreapi.Origin
import io.github.kzagoris.proxenos.coreapi.RuntimeEvent
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.time.Instant

/** One thing this machine needs before the Runtime can serve ChatGPT, and whether it has it. */
data class Requirement(val name: String, val met: Boolean, val words: String) {
  companion object {
    const val RUNTIME = "runtime"
    const val TUNNEL_CLIENT = "tunnel-client"
    const val CREDENTIALS = "credentials"
  }
}

/**
 * What setup checks on this machine before any Runtime answers, so the GUI's Welcome step and
 * `tui check` cannot come to disagree. The runtime key is never read out of the credentials
 * file: only the tunnel ID, which is not a secret, leaves it.
 */
class LocalSetup(private val environment: Map<String, String> = System.getenv()) {
  val credentials: Path = ConfigToml.directory(environment).resolve("credentials")

  /** The saved tunnel ID, or null where no owner-only credentials file carries one. */
  fun tunnelId(): String? {
    if (!ownerOnly()) return null
    return Files.readAllLines(credentials).firstOrNull { it.trim().startsWith("TUNNEL_ID=") }
      ?.substringAfter('=')?.trim()?.takeIf { it.isNotEmpty() }
  }

  /** The Runtime launcher, `tunnel-client` where the Runtime looks for it, and the credentials file. */
  fun requirements(runtime: Path? = RuntimeAttachment.executable()): List<Requirement> =
    listOf(runtime(runtime), tunnelClient(), saved())

  private fun runtime(runtime: Path?) =
    if (runtime != null && Files.isExecutable(runtime)) Requirement(Requirement.RUNTIME, true, "The Runtime launcher is $runtime.")
    else Requirement(Requirement.RUNTIME, false, "The Runtime launcher is unavailable. Install the Proxenos distribution or set ${RuntimeAttachment.VARIABLE}.")

  /** The Runtime's own search (TunnelClient), so this cannot pass where the Runtime's start would refuse. */
  private fun tunnelClient(): Requirement {
    val toml = ConfigToml.read(environment, ignored = "gui_scale")
    // A configured one is the only one the Runtime will run, so it alone decides.
    val configured = TunnelClient.configured(toml, environment)
    val found = (if (configured != null) configured.takeIf { Files.isExecutable(it) } else TunnelClient.search(environment, stateDirectory(toml, environment)))
      ?: return Requirement(Requirement.TUNNEL_CLIENT, false,
        "The tunnel client is unavailable. Run bin/install-tunnel-client from the Proxenos distribution to install it, then check again.")
    return Requirement(Requirement.TUNNEL_CLIENT, true, "The tunnel client is $found.")
  }

  private fun saved(): Requirement = when {
    !Files.isRegularFile(credentials) -> Requirement(Requirement.CREDENTIALS, false,
      "No credentials file at $credentials. Save the tunnel ID and runtime key in the desktop app's setup, or run bin/wizard in a terminal.")
    !ownerOnly() -> Requirement(Requirement.CREDENTIALS, false,
      "$credentials can be read by other users, so the Runtime refuses it. Run chmod 600 on it.")
    else -> Requirement(Requirement.CREDENTIALS, true, "Credentials are saved at $credentials, readable by their owner only.")
  }

  private fun ownerOnly(): Boolean =
    Files.isRegularFile(credentials) && Files.getPosixFilePermissions(credentials).all { it in OWNER_ONLY }

  private companion object {
    val OWNER_ONLY = PosixFilePermissions.fromString("rw-------")
  }
}

/**
 * ChatGPT's listing of [workspace]'s Root, started at or after [since] in this Runtime start, and
 * successful: the evidence guided setup waits for (ADR 0012). It counts only while the Workspace
 * still has the [root] the wait began against and is not Broken. A frontend's Try, an earlier
 * start's entry, a failure and an Undelivered answer do not count. Origin carries no conversation
 * identity, so this proves only the Operation returned, at its time.
 */
fun RuntimeEvent.Snapshot.rootListing(workspace: String, root: String, since: Instant): ActivityEntry? {
  workspaces.find { it.workspace.name == workspace }?.takeIf { !it.broken && it.workspace.root == root } ?: return null
  return activity.lastOrNull {
    it.runtimeStart == start?.id && !it.at.isBefore(since) &&
      it.origin == Origin.ChatGpt && it.workspace == workspace &&
      it.tool == "list_directory" && it.arguments == "path=." && it.outcome is ActivityOutcome.Ok
  }
}

/** What to ask ChatGPT for so that it makes the [rootListing] of [workspace]. */
fun listingPrompt(workspace: String): String =
  "Use Proxenos to list my Workspaces, then list the files at the Root of " +
    "\"${workspace.replace("\"", "\\\"")}\" using path \".\". Do not change any files."
