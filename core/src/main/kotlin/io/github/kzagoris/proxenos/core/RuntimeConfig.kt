package io.github.kzagoris.proxenos.core

import io.github.kzagoris.proxenos.coreapi.Operation
import java.nio.file.Path
import kotlin.time.Duration

/**
 * Everything the core is given rather than goes looking for (SPEC §9, §11). The core takes one
 * of these and deliberately does not source it: where each value comes from — XDG defaults, the
 * optional `config.toml`, the environment — is the `runtime` artifact's business, and a test
 * builds one with a 200 ms budget and a stub script where the Runtime would put 45 s and
 * `tunnel-client`.
 *
 * **It never carries the credential.** The tunnel ID and runtime key go from the credentials
 * file to the tunnel child's environment and nowhere else, so this can be printed, logged and
 * pasted into a bug report whole.
 *
 * It lives in the core rather than in `core-api`, where SPEC §14 item 2 first listed it: no
 * frontend ever holds one, and its paths are exactly the filesystem detail `core-api` keeps out.
 *
 * The 64 KiB output cap is not here, though §11's table lists it as a tunable: the catalog
 * states it to the model in `read_file`'s `limit` argument, so changing it changes the catalog
 * ChatGPT froze (§11.5). It is a property of the build, and moves with one.
 */
data class RuntimeConfig(
  /**
   * Registrations, Activity, the acknowledged connector, and `tools/`, where the tunnel executable
   * is looked for last.
   */
  val stateDirectory: Path,
  /** What the tunnel child dials (§7), and the path the single-instance lock is taken on. */
  val mcpSocket: Path,
  /** The management seam (§7), reachable only by this Linux user. */
  val controlSocket: Path,
  /** Where the tunnel child binds `/metrics` (§8.4). Beside the MCP socket unless moved. */
  val tunnelHealthSocket: Path = mcpSocket.resolveSibling("tunnel-health.sock"),
  /** Where the tunnel child writes its health listener's base URL, so the rendezvous is not guessed. */
  val tunnelHealthUrlFile: Path = mcpSocket.resolveSibling("tunnel-health.url"),
  val tunnelExecutable: Path,
  /**
   * §8.4: the tunnel child's long-poll wait and the guardrail after it, passed to it as flags.
   * The Runtime launches the child with these, so it knows the poll cycle rather than guessing
   * it — and the staleness threshold behind Connected is derived from them, never fixed.
   */
  val tunnelPollTimeout: Duration = Tunnel.POLL_TIMEOUT,
  val tunnelPollGuardrail: Duration = Tunnel.POLL_GUARDRAIL,
  /**
   * §8.4: how long past one full poll cycle a success may be before the link is judged Failed —
   * room for a response to be posted and the next poll to go out.
   */
  val tunnelStalenessMargin: Duration = Tunnel.STALENESS_MARGIN,
  /** §8.4: how often the tunnel child's `/metrics` is read. */
  val tunnelReadInterval: Duration = Tunnel.READ_INTERVAL,
  /** §6.2: how long a `run_command` is given before it is Promoted. */
  val commandBudget: Duration = Operation.COMMAND_BUDGET,
  /** §6.3: TERM, this long, then SIGKILL. */
  val killGrace: Duration = Operation.KILL_GRACE,
  /** §6.6: concurrent `run_command`s per Runtime. */
  val commandConcurrency: Int = Operation.COMMAND_CONCURRENCY_CAP,
  /** §6.4: how long past its reply a Delivery record is kept. */
  val deliveryRetention: Duration = Deliveries.RETENTION,
  val deliveryRecordQuota: Int = Deliveries.RECORD_CAP,
  val deliveryKeyQuota: Int = Deliveries.KEY_CAP,
  /** §10.1: by age, not count; an unresolved entry outlives it until settled. */
  val activityRetention: Duration = Activity.DEFAULT_RETENTION,
) {
  val registryFile: Path get() = registryFileIn(stateDirectory)
  val activityFile: Path get() = stateDirectory.resolve("activity")
  /** Present exactly when the user last Disconnected (§8.2): the connect-*intent*, persisted. */
  val disconnectedFile: Path get() = stateDirectory.resolve("disconnected")
  /** The catalog fingerprint the user last acknowledged their connector was built against (§11.5). */
  val connectorFile: Path get() = stateDirectory.resolve("connector")

  companion object {
    /** For `runtime register`, which has a state directory and no reason to build the rest. */
    fun registryFileIn(stateDirectory: Path): Path = stateDirectory.resolve("registry.properties")
  }
}
