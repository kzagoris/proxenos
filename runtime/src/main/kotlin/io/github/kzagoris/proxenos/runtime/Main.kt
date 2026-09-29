package io.github.kzagoris.proxenos.runtime

import io.github.kzagoris.proxenos.control.ControlServer
import io.github.kzagoris.proxenos.core.Activity
import io.github.kzagoris.proxenos.core.ConnectorAcknowledgement
import io.github.kzagoris.proxenos.core.RuntimeFeed
import io.github.kzagoris.proxenos.core.RuntimeManagement
import io.github.kzagoris.proxenos.core.WorkspaceOperationsPipeline
import io.github.kzagoris.proxenos.core.WorkspaceRegistry
import io.github.kzagoris.proxenos.core.Tunnel
import io.github.kzagoris.proxenos.core.catalogFingerprint
import io.github.kzagoris.proxenos.coreapi.Origin
import io.github.kzagoris.proxenos.mcp.McpEndpoint
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import kotlin.system.exitProcess
import kotlinx.coroutines.runBlocking

/**
 * The composition root (SPEC §1, §9): the one place that sees every artifact. It sources the
 * configuration, takes the single-instance lock, builds the core, hands the ChatGPT-stamped view
 * of it to the MCP endpoint, and starts the tunnel child that dials back in.
 *
 * Nothing here reads standard input. A frontend starts the Runtime with no terminal attached,
 * so every reason not to start is said once on standard error and the process exits.
 *
 * There is no autostart at login, and nothing here offers one (§8.2, §11.4): that would boot
 * the machine into a state where a Command Workspace is reachable with nobody present. The
 * systemd `--user` unit in `docs/` is for a user who has read why and disagrees.
 */
fun main(args: Array<String>) {
  try {
    when (args.firstOrNull()) {
      null -> start(System.getenv())
      "register" -> register(args.drop(1), System.getenv())
      else -> throw UsageError("Unknown command: ${args.first()}")
    }
  } catch (refused: StartRefused) {
    say(if (args.isEmpty()) "will not start. ${refused.message}" else "${refused.message}")
    exitProcess(REFUSED)
  } catch (usage: UsageError) {
    say("${usage.message}\n$USAGE")
    exitProcess(USAGE_ERROR)
  }
}

/** `EX_USAGE` from sysexits.h. */
private const val USAGE_ERROR = 64

/**
 * `EX_CONFIG` from sysexits.h: a refusal is nothing a restart can fix, and a supervisor told
 * this status — the unit in `docs/` is — does not try.
 */
private const val REFUSED = 78

private fun start(environment: Map<String, String>) {
  val (config, logicalHost, credentials) = sourceConfiguration(environment)
  privateDirectory(config.stateDirectory)
  privateDirectory(config.mcpSocket.parent)
  privateDirectory(config.controlSocket.parent)
  val instance = listOf(InstanceLock.forStateDirectory(config.stateDirectory), InstanceLock.forSocket(config.mcpSocket))

  // One feed, so a frontend's snapshot and its changes come from every part of the core at once.
  val feed = RuntimeFeed()
  val registry = WorkspaceRegistry(config.registryFile, feed)
  val activity = Activity(config.activityFile, config.activityRetention, feed)
  val pipeline = WorkspaceOperationsPipeline(registry, activity, config)
  // Read before the control socket opens, so no frontend attaches to a judgement not yet made.
  val connector = ConnectorAcknowledgement(config.connectorFile, feed, catalogFingerprint(pipeline.catalog))
  // The two surfaces, each with its own Origin stamped here and nowhere else (§9). ChatGPT gets
  // the operations and nothing more; the management acts are on the control socket alone, so
  // raising an Access Level is not on any path a conversation has.
  val endpoint = McpEndpoint(pipeline.operationsFor(Origin.ChatGpt), config.mcpSocket, logicalHost)
  val tunnel = Tunnel(config, logicalHost.url, credentials, ::say)
  // Stop from a frontend has already ended the work and the link; exiting runs the hook below,
  // which finds nothing left to stop and releases the sockets and locks. On its own thread,
  // because exitProcess waits for that hook and the hook stops the server this act arrived on.
  val management = RuntimeManagement(registry, activity, tunnel, pipeline, connector, feed) {
    Thread({ exitProcess(0) }, "runtime-exit").start()
  }
  val control = ControlServer(management, config.controlSocket)

  // Stop: the tunnel and every running Operation at once, so the whole costs one grace period
  // rather than two (§6.3) — the pipeline refuses new calls before it reaps, so nothing arriving
  // through a tunnel still dying can start. What was running is left Uncertain rather than
  // Lost. Then the socket, and last the locks.
  Runtime.getRuntime().addShutdownHook(
    Thread({
      say("stopping.")
      val tunnelStopped = Thread(tunnel::stop, "tunnel-stop").apply { start() }
      runBlocking { pipeline.stop() }
      tunnelStopped.join()
      control.stop()
      endpoint.stop()
      instance.forEach(InstanceLock::close)
    }, "runtime-stop"),
  )

  endpoint.start()
  control.start()
  say("serving MCP on ${config.mcpSocket} as ${logicalHost.url}; catalog fingerprint ${connector.fingerprint}.")
  tunnel.start()
  tunnel.join()
}

/** Created owner-only, because the sockets in it are this Linux user's alone (§7). */
internal fun privateDirectory(directory: Path) {
  if (Files.isDirectory(directory)) return
  Files.createDirectories(directory, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")))
}

private fun say(line: String) = System.err.println("runtime: $line")
