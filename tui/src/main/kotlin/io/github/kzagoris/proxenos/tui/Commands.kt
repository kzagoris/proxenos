package io.github.kzagoris.proxenos.tui

import io.github.kzagoris.proxenos.coreapi.AccessLevel
import io.github.kzagoris.proxenos.coreapi.ManagementAct
import io.github.kzagoris.proxenos.coreapi.RuntimeEvent
import io.github.kzagoris.proxenos.coreapi.RuntimeState
import io.github.kzagoris.proxenos.coreapi.TunnelComplaint
import io.github.kzagoris.proxenos.frontend.Attachment
import io.github.kzagoris.proxenos.frontend.LocalSetup
import io.github.kzagoris.proxenos.frontend.RuntimeAttachment
import io.github.kzagoris.proxenos.frontend.Wording
import io.github.kzagoris.proxenos.frontend.absoluteRoot
import io.github.kzagoris.proxenos.frontend.listingPrompt
import io.github.kzagoris.proxenos.frontend.overlaps
import io.github.kzagoris.proxenos.frontend.rootListing
import java.io.Flushable
import java.io.IOException
import java.nio.file.Path
import java.time.Instant
import kotlin.time.Duration
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

// The commands a coding agent drives setup with. None starts the Runtime: an agent that asks where
// things stand must not change them by asking, and `register` acts only on a Runtime already
// running. `tui status --json` is a versioned contract (ADR 0013); the others speak sentences and
// answer with their exit status.

/** Within one version a field may be added; none is renamed, retyped or dropped (ADR 0013). */
private const val STATUS_VERSION = 1

/** The Runtime's snapshot, or null when nothing answers. It never starts one. */
private suspend fun RuntimeAttachment.snapshot(): RuntimeEvent.Snapshot? =
  (attach(startIfAbsent = false).first { it is Attachment.Attached || it is Attachment.Absent } as? Attachment.Attached)?.snapshot

/**
 * `tui status --json`: the three stages, every Workspace and what this machine has, as one JSON
 * object in the domain's terms. Where the Runtime is absent the readings it alone could give are
 * null rather than guessed. The runtime key is never in it; the tunnel ID, which is no secret, is.
 */
internal suspend fun status(attachment: RuntimeAttachment, local: LocalSetup, runtime: Path?, out: Appendable): Int {
  val snapshot = attachment.snapshot()
  val status = buildJsonObject {
    put("version", STATUS_VERSION)
    put("runtime", if (snapshot == null) "absent" else "running")
    put("tunnel", snapshot?.let(::tunnel) ?: JsonNull)
    put("connector", snapshot?.let { Wording.connector(it.connectorUnconfirmed) })
    put("runningOperations", snapshot?.running?.size)
    put("workspaces", snapshot?.let(::workspaces) ?: JsonNull)
    put("tunnelId", local.tunnelId())
    putJsonArray("requirements") {
      local.requirements(runtime).forEach { requirement ->
        addJsonObject {
          put("name", requirement.name)
          put("met", requirement.met)
          put("words", requirement.words)
        }
      }
    }
  }
  out.appendLine(status.toString())
  return 0
}

private fun workspaces(snapshot: RuntimeEvent.Snapshot): JsonElement = buildJsonArray {
  snapshot.workspaces.forEach { state ->
    addJsonObject {
      put("name", state.workspace.name)
      put("root", state.workspace.root)
      put("accessLevel", state.workspace.accessLevel.name)
      put("broken", state.broken)
    }
  }
}

/** The Tunnel reading, with the tunnel's own complaint verbatim where it made one (ADR 0005). */
private fun tunnel(snapshot: RuntimeEvent.Snapshot): JsonObject = buildJsonObject {
  val state = snapshot.runtime.state
  put("state", Wording.tunnel(state))
  put("since", snapshot.runtime.enteredAt.toString())
  val complaint = (state as? RuntimeState.Failed)?.complaint ?: snapshot.connectingWords?.complaint
  put("complaint", complaint?.json() ?: JsonNull)
  put("failureCategory", snapshot.connectingWords?.failureCategory)
}

private fun TunnelComplaint.json(): JsonObject = buildJsonObject {
  put("statusCode", statusCode)
  put("errorCode", errorCode)
  put("mitigation", mitigation)
  put("message", message)
}

/** `tui check`: each requirement on its own line, and success only when every one is met. */
internal fun check(local: LocalSetup, runtime: Path?, out: Appendable): Int {
  val requirements = local.requirements(runtime)
  requirements.forEach { out.appendLine("${if (it.met) "ok" else "missing"}  ${it.name}: ${it.words}") }
  return if (requirements.all { it.met }) 0 else 1
}

/** `await-listing`'s exit status when the time ran out with nothing wrong: waiting again is the remedy. */
internal const val TIMED_OUT = 2

/**
 * `tui await-listing <workspace>`: waits for ChatGPT's listing of [workspace]'s Root, made after
 * this began, which is the one evidence that the whole path works (ADR 0012). Asking it, or the
 * user saying the connector exists, is not that evidence. 0 is the listing, [TIMED_OUT] is a wait
 * worth repeating, and 1 is a reason waiting cannot succeed, which it says.
 */
internal suspend fun awaitListing(attachment: RuntimeAttachment, workspace: String, timeout: Duration, out: Appendable): Int {
  val since = Instant.now()
  // The Root the wait began against: a listing after a move is of some other directory (ADR 0012).
  var root: String? = null
  fun refuse(words: String) = 1.also { out.appendLine(words) }
  val decided = withTimeoutOrNull(timeout) {
    attachment.attach(startIfAbsent = false).mapNotNull { attached ->
      if (attached is Attachment.Absent) return@mapNotNull refuse("The Runtime is not running. Start it with `tui start`, then wait again.")
      val snapshot = (attached as? Attachment.Attached)?.snapshot ?: return@mapNotNull null
      val state = snapshot.workspaces.find { it.workspace.name == workspace }
        ?: return@mapNotNull refuse("No Workspace named '$workspace' is registered.")
      val current = state.workspace.root
      when {
        state.broken -> refuse(Wording.reconfirm(state.workspace).joinToString(" "))
        state.workspace.accessLevel == AccessLevel.None ->
          refuse("'$workspace' is at None. ${Wording.NONE_EXPLANATION} Raise it to Read in the desktop app or the dashboard.")
        root != null && root != current -> refuse("'$workspace' moved to $current while waiting. Wait again to wait on that Root.")
        else -> {
          if (root == null) {
            root = current
            out.appendLine("Ask ChatGPT, in a new conversation with Proxenos enabled:")
            out.appendLine(listingPrompt(workspace))
            out.appendLine("Waiting for that listing; a Try from a frontend does not count.")
            (out as? Flushable)?.flush()
          }
          val entry = snapshot.rootListing(workspace, current, since)
          if (entry == null) null
          else 0.also { out.appendLine("ChatGPT listed the Root of '$workspace' with list_directory at ${entry.at}.") }
        }
      }
    }.first()
  }
  return decided ?: TIMED_OUT.also {
    out.appendLine("ChatGPT made no listing of the Root of '$workspace' within $timeout. Nothing was changed; wait again to keep waiting.")
  }
}

/**
 * `tui register <directory> [--name <name>]`: registers at Read through the running Runtime, as
 * the home screen's `n` does. An overlap the home screen would ask about is refused here instead:
 * there is nobody to ask, and registering the same files twice is not a default to take.
 */
internal suspend fun register(attachment: RuntimeAttachment, typed: String, name: String?, out: Appendable): Int {
  val notRunning = "The Runtime is not running. Start it with `tui start` and register again, " +
    "or register while it is stopped with `bin/runtime register <directory> --name <name>`."
  val snapshot = attachment.snapshot() ?: return 1.also { out.appendLine(notRunning) }
  val root = absoluteRoot(typed)
  val overlapping = overlaps(root, snapshot.workspaces)
  if (overlapping.isNotEmpty()) {
    Wording.overlap(root.toString(), overlapping).forEach(out::appendLine)
    out.appendLine("Nothing was registered. To register it anyway, use the desktop app or the dashboard.")
    return 1
  }
  val workspace = try {
    attachment.management.perform(ManagementAct.Register(root.toString(), name))
  } catch (refused: RuntimeException) {
    // The two the management client raises for a refusal; anything else is a defect to surface.
    if (refused !is IllegalArgumentException && refused !is IllegalStateException) throw refused
    return 1.also { out.appendLine("Not registered: ${refused.message}") }
  } catch (_: IOException) {
    return 1.also { out.appendLine(notRunning) }
  }
  out.appendLine("Registered '${workspace.name}' at ${workspace.accessLevel}: ${workspace.root}")
  return 0
}
