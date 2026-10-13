package io.github.kzagoris.proxenos.gui

import io.github.kzagoris.proxenos.coreapi.*

// The five pages of the approved Guided flow; connection readings remain independent.
enum class SetupStep(val label: String) { Welcome("Welcome"), Tunnel("Tunnel"), Workspace("Workspace"), ChatGPT("ChatGPT"), TryIt("Try it") }

data class SetupProgress(
  val step: SetupStep = SetupStep.Welcome,
  val tunnelPart: Int = 0,
  val tunnelId: String = "",
  val furthest: SetupStep = SetupStep.Welcome,
  val associated: Boolean = false,
  val workspace: String? = null,
  val waitingSince: Long? = null,
  val waitingStart: String? = null,
  val waitingRoot: String? = null,
  val waitingName: String? = null,
  val completed: Boolean = false,
) {
  fun selected(snapshot: RuntimeEvent.Snapshot?): WorkspaceState? =
    snapshot?.workspaces?.find { it.workspace.id.value == workspace }

  /** Origin carries no conversation identity. This proves only the Operation shown, at its time. */
  fun evidence(snapshot: RuntimeEvent.Snapshot?): ActivityEntry? {
    snapshot ?: return null
    val selected = selected(snapshot)?.takeIf { !it.broken } ?: return null
    val since = waitingSince ?: return null
    if (waitingRoot != selected.workspace.root || waitingName != selected.workspace.name) return null
    return snapshot.activity.lastOrNull {
      it.runtimeStart == snapshot.start?.id && it.at.toEpochMilli() >= since &&
        it.origin == Origin.ChatGpt && it.workspace == selected.workspace.name &&
        it.tool == "list_directory" && it.arguments == "path=." && it.outcome is ActivityOutcome.Ok
    }
  }

  fun prompt(snapshot: RuntimeEvent.Snapshot?): String =
    "Use Proxenos to list my Workspaces, then list the files at the Root of " +
      "\"${selected(snapshot)?.workspace?.name.orEmpty().replace("\"", "\\\"")}\" using path \".\". Do not change any files."
}
