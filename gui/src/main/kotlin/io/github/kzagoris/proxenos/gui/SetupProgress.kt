package io.github.kzagoris.proxenos.gui

import io.github.kzagoris.proxenos.coreapi.*
import io.github.kzagoris.proxenos.frontend.listingPrompt
import io.github.kzagoris.proxenos.frontend.rootListing
import java.time.Instant

// The five pages of the approved Guided flow; connection readings remain independent.
enum class SetupStep(val label: String) { Welcome("Welcome"), Tunnel("Tunnel"), Workspace("Workspace"), ChatGPT("ChatGPT"), TryIt("Try it") }

/** Setup can use this Workspace: its Root is not Broken and it is not withheld at None. */
val WorkspaceState.usable: Boolean get() = !broken && workspace.accessLevel != AccessLevel.None

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

  /** The Root listing of the Workspace chosen, unrenamed and unmoved since the wait began (ADR 0012). */
  fun evidence(snapshot: RuntimeEvent.Snapshot?): ActivityEntry? {
    snapshot ?: return null
    val selected = selected(snapshot) ?: return null
    val since = waitingSince ?: return null
    val root = waitingRoot ?: return null
    if (waitingName != selected.workspace.name) return null
    return snapshot.rootListing(selected.workspace.name, root, Instant.ofEpochMilli(since))
  }

  fun prompt(snapshot: RuntimeEvent.Snapshot?): String = listingPrompt(selected(snapshot)?.workspace?.name.orEmpty())
}
