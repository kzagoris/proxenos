package io.github.kzagoris.proxenos.tui

import io.github.kzagoris.proxenos.coreapi.AccessLevel
import io.github.kzagoris.proxenos.coreapi.CommandReply
import io.github.kzagoris.proxenos.coreapi.ConnectingWords
import io.github.kzagoris.proxenos.coreapi.Operation
import io.github.kzagoris.proxenos.coreapi.OperationSpec
import io.github.kzagoris.proxenos.coreapi.Outcome
import io.github.kzagoris.proxenos.coreapi.StopPhase
import io.github.kzagoris.proxenos.coreapi.Workspace
import io.github.kzagoris.proxenos.coreapi.WorkspaceState

/**
 * The sentences the screen is obliged to carry (SPEC §10.3), each at a place it could otherwise
 * quietly lie. Kept together, as plain paragraphs, so what the screen says can be read and
 * checked in one place; the screen wraps them to its width.
 */
object Wording {
  fun raiseToCommand(workspace: Workspace): List<String> =
    listOf("Raise '${workspace.name}' to Command?") + commandAuthority(workspace) + "[y] raise to Command · any other key cancels"

  /**
   * SPEC §10.3: what Command authorises. Said when raising to it, and kept in the Workspace
   * detail pane, which is the only place it survives once that confirmation is gone.
   */
  fun commandAuthority(workspace: Workspace): List<String> = listOf(
    "Commands run with the full authority of your Linux account. They are not bounded by the Root " +
      "${workspace.root}: a command can read ~/.ssh, and anything else your account can reach.",
    "There is no per-call prompt. Once it is set, this level authorises every command while nobody is watching.",
  )

  /** SPEC §10.2: the stop confirmation names the command, the Workspace, the start and the elapsed. */
  fun stopCommand(command: String, workspace: String?, started: String, elapsed: String): List<String> = listOf(
    "Stop `$command` in '${workspace ?: "—"}'? It started $started and has been running $elapsed.",
    "It ran with the full authority of your Linux account. Stopping it sends TERM to its process tree and group, " +
      "waits out the grace, then sends SIGKILL.",
    "Its result will be Uncertain: what it did to this machine is unknown, and nothing is rolled back.",
    "[y] stop it · any other key cancels",
  )

  const val ALREADY_STOPPING: String = "already stopping — there is no second, harder stop"

  /**
   * What is known once the stop has run its course — not that everything ended: survivors are
   * never rounded off to "stopped" (§6.3), and the entry is where the reaping names them.
   */
  fun stopped(command: String): String =
    "The stop ran its course for `$command`: TERM, the grace, then SIGKILL. Its result is Uncertain and nothing was rolled back; " +
      "its entry in Activity says whether anything outlived the kill."

  /** Where the one kill has got to (SPEC §6.3), for a row that is still in the band because it is still running. */
  fun stopping(phase: StopPhase): String = when (phase) {
    StopPhase.Terminating -> "stopping (TERM sent, SIGKILL after the grace) · $ALREADY_STOPPING"
    StopPhase.Killing -> "stopping (SIGKILL sent, waiting to reap it) · $ALREADY_STOPPING"
  }

  /**
   * SPEC §10.3: lowering a level does not stop running work. A promoted command's result can no
   * longer be collected either, and both are said: one without the other implies the wrong thing.
   */
  fun lowered(command: String, workspace: String, level: AccessLevel, started: String, promoted: Boolean): String =
    buildString {
      append("'$workspace' is at $level now, and that did not stop `$command`, started $started: lowering a level stops no running work, ")
      append("and it is still running with the full authority of your Linux account. ")
      if (promoted) append("Its result can no longer be collected either: get_result needs Command, so ChatGPT cannot collect what it did. ")
      append("To end it, select it in the band and press [s].")
    }

  /** SPEC §10.3: what an Undelivered entry means, where it is selected. */
  const val UNDELIVERED: String =
    "Undelivered: this Operation completed on this machine and its answer never reached ChatGPT. ChatGPT saw a failure " +
      "and does not know the change was made. Nothing is replayed or retried: what it did stays done, and nothing here does it again."

  /**
   * One catalog entry against one Workspace as it now stands (SPEC §10.2): whether a call naming
   * it would be admitted, and why — in terms of the level the pipeline checks, never a guess at
   * what the call would then do.
   */
  fun admission(spec: OperationSpec, state: WorkspaceState): Pair<Boolean, String> {
    val workspace = state.workspace
    val level = workspace.accessLevel
    val required = spec.requiredLevel
    return when {
      required == null -> true to when {
        state.broken -> "needs no level; '${workspace.name}' is Broken, so it is left out of what this lists"
        level == AccessLevel.None -> "needs no level; '${workspace.name}' is at None, withheld, so it is left out of what this lists"
        else -> "needs no level; '${workspace.name}' is listed, at $level"
      }

      state.broken -> false to "needs $required; '${workspace.name}' is Broken — its Root is no longer the directory registered, " +
        "so a call naming it is refused until it is re-confirmed"

      level >= required -> true to "needs $required; '${workspace.name}' is at $level"
      level == AccessLevel.None -> false to "needs $required; '${workspace.name}' is at None, withheld — a call naming it is " +
        "answered as though no such Workspace existed"

      else -> false to "needs $required; '${workspace.name}' is at $level, so a call is refused and changes nothing"
    }
  }

  /** What else a catalog entry carries in the pane, beyond whether it is permitted. */
  fun notes(spec: OperationSpec, workspace: Workspace): List<String> = when {
    spec.name == RUN_COMMAND -> commandAuthority(workspace)
    spec.name == "get_result" -> listOf(
      "The level is re-checked on every call: below Command, a command already running can no longer be collected.",
    )

    spec.arguments.any { it.name == Operation.KEY_ARGUMENT } -> listOf("Tried from here, it is given a fresh request_id of its own.")
    else -> emptyList()
  }

  /** What a TryOperation came back with: the pipeline's own words, which is what ChatGPT would have been told. */
  fun tried(tool: String, workspace: String, outcome: Outcome<*>): Pair<String, Tone> = when (outcome) {
    is Outcome.Ok -> {
      val promoted =
        (outcome.value as? CommandReply.Promoted)?.let { " It outran its call and was Promoted as Handle ${it.handle.value}: it is in the band." }
      "Tried $tool against '$workspace': ok. What it returned is in Activity.${promoted.orEmpty()}" to Tone.Good
    }

    is Outcome.Failed -> "Tried $tool against '$workspace': failed. ${outcome.message}" to Tone.Warn
    is Outcome.Uncertain -> "Tried $tool against '$workspace': uncertain. ${outcome.message}" to Tone.Bad
  }

  fun overlap(root: String, overlaps: List<Overlap>): List<String> = buildList {
    add("$root overlaps a Root already registered:")
    for ((workspace, relation) in overlaps) add(
      when (relation) {
        Relation.Same -> "  it is the Root of '${workspace.name}' (${workspace.root}), at ${workspace.accessLevel}"
        Relation.Inside -> "  it is inside '${workspace.name}' (${workspace.root}), at ${workspace.accessLevel}"
        Relation.Contains -> "  it contains '${workspace.name}' (${workspace.root}), at ${workspace.accessLevel}"
      },
    )
    add(
      "There is no union and no intersection: each Workspace is governed by its own Access Level alone, " +
        "so a file under both is reachable at whichever level the call names.",
    )
    add("[y] register anyway, at Read · any other key cancels")
  }

  fun reconfirm(workspace: Workspace): List<String> = listOf(
    "Re-confirm '${workspace.name}' at ${workspace.root}?",
    "It was at ${workspace.accessLevel}. It lands at Read${if (workspace.accessLevel == AccessLevel.Read) "" else ", not ${workspace.accessLevel}"}, " +
      "because the thing on disk changed identity: the directory there is no longer the one that was registered, " +
      "so a level granted to that one does not carry over to this. Raise it again once you have checked what it is.",
    "[y] re-confirm at Read · any other key cancels",
  )

  fun stopRuntime(): List<String> = listOf(
    "Stop the Runtime?",
    "Every running Operation is ended and left Uncertain, and nothing is rolled back. Registrations survive, " +
      "but nothing is exposed and no frontend can attach until the Runtime is started again.",
    "[y] stop the Runtime · any other key cancels",
  )

  /** SPEC §10.3 and §11.4: there is no autostart, and the silence after a reboot is expected. */
  const val NO_AUTOSTART: String =
    "There is no autostart, and that is deliberate: a Runtime started at login would leave a Command Workspace " +
      "reachable with nobody present. After a reboot every call from ChatGPT fails until the Runtime is started " +
      "once, so that silence is expected rather than a fault to hunt."

  /**
   * SPEC §10.3 and §11.5: the Unconfirmed detail, in the Connector stage of Review. The literal
   * steps, and plainly that nothing here can check them — so the key records the user's word,
   * and says so.
   */
  val UNCONFIRMED: List<String> = listOf(
    "Connector Unconfirmed: this Runtime cannot verify that ChatGPT is using a connector created from this Runtime's current tool list. " +
      "ChatGPT saves that tool list when the connector is created and does not refresh it later. Create the connector again:",
    "1. Delete the app in ChatGPT.  2. New Plugin.  3. Select the tunnel.  4. Choose No Auth, not the OAuth default: OAuth fails before any tool call.",
    "Nothing on this machine can check whether these were done. [C] records your word that they were; it is not a measurement.",
  )

  const val CONNECTOR_ACKNOWLEDGED: String =
    "Recorded your word that the connector was made again. That is not a measurement: nothing here checked it."

  /**
   * SPEC §10.3 and §11.3: the first-run credential panel. The tunnel's words verbatim and the
   * three things only the user can check — and never which of them it is, because a deleted
   * tunnel and a rejected key are the same 401.
   */
  fun connecting(since: String, words: ConnectingWords): List<String> = buildList {
    add(
      "No poll has succeeded since $since, one long-poll wait and more. It stays Connecting: " +
        "the tunnel client carries on polling by itself, and Connecting does not turn into anything else.",
    )
    words.failureCategory?.let { add("The tunnel's control-plane health says: failure_category \"$it\"") }
    add(complaint(words.complaint))
    add(
      "Only you can check these, and nothing here can tell which it is: the tunnel ID matches the tunnel you created; " +
        "the runtime key has not been revoked; the key belongs to that tunnel.",
    )
  }

  /** What any cancelled confirmation or prompt leaves on the screen. */
  const val CANCELLED: String = "Cancelled; nothing was changed."

  /** SPEC §10.3, verbatim. */
  const val CONNECTED_CAVEAT: String =
    "Connected means the tunnel between this machine and OpenAI is up. It does not mean ChatGPT still has a " +
      "connector pointed at it: deleting the connector in ChatGPT leaves this reading unchanged, and its catalog " +
      "is a snapshot that never refreshes."

  /** SPEC §10.2: this dashboard's side of the link to the Runtime — never the Runtime's own life. */
  const val ATTACHED: String =
    "Attached: this dashboard holds the Runtime's stream. The Runtime runs whether or not a frontend is attached."

  /** The two transient readings before Attached: dialling, and waiting for a start to answer. */
  const val ATTACHING: String = "Attaching: this dashboard is dialling the Runtime's control socket."
  const val STARTING: String =
    "Starting: the Runtime was asked to start, and this dashboard is waiting for it to answer."

  /**
   * SPEC §10.2: a stage below one that is not running. The link is the Runtime's to hold and the
   * catalog is the Runtime's to serve, so with no Runtime there is nothing to measure — and a red
   * reading would be a claim nothing has measured.
   */
  const val CANT_TELL: String = "Can't tell"
  const val CANT_TELL_TUNNEL: String =
    "Can't tell: the link is the Runtime's to hold, so there is nothing to measure until the Runtime is running."
  const val CANT_TELL_CONNECTOR: String =
    "Can't tell: there is no catalog to compare until the Runtime is running and serving one."

  /** SPEC §10.2 and ADR 0007: what Confirmed can be — the user's word, never a measurement. */
  const val CONNECTOR_CONFIRMED: String =
    "Confirmed by your word. Nothing on this machine can check the connector: a connector made again and one never made read the same here."
}
