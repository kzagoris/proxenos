package io.github.kzagoris.proxenos.runtime

import io.github.kzagoris.proxenos.core.WorkspaceRegistry
import io.github.kzagoris.proxenos.core.RuntimeConfig
import io.github.kzagoris.proxenos.coreapi.ManagementAct
import java.io.IOException
import kotlinx.coroutines.runBlocking

/**
 * `runtime register <directory> [--name <name>]`: registers a Workspace at Read
 * while the Runtime is stopped.
 *
 * It refuses while a Runtime is running, by taking the same state-directory lock. That Runtime
 * loaded the registry when it started and rewrites the file from what it holds, so a
 * registration made underneath it would be served by nothing and then lost.
 */
internal fun register(arguments: List<String>, environment: Map<String, String>) {
  val (root, name) = parse(arguments)
  val stateDirectory = sourceStateDirectory(environment)
  privateDirectory(stateDirectory)
  val lock = try {
    InstanceLock.forStateDirectory(stateDirectory)
  } catch (running: StartRefused) {
    throw StartRefused("${running.message} Stop it first: it reads the registry only when it starts.")
  }
  lock.use {
    val registry = WorkspaceRegistry(RuntimeConfig.registryFileIn(stateDirectory))
    val workspace = try {
      runBlocking { registry.perform(ManagementAct.Register(root, name)) }
    } catch (failed: IOException) {
      throw StartRefused("$root cannot be registered: ${failed.message ?: failed::class.simpleName}.")
    } catch (failed: IllegalArgumentException) {
      throw StartRefused("${failed.message}. Choose another with --name.")
    }
    println("Registered '${workspace.name}' at ${workspace.accessLevel}: ${workspace.root}")
    println("The Runtime serves it from its next start.")
  }
}

private fun parse(arguments: List<String>): Pair<String, String?> {
  var root: String? = null
  var name: String? = null
  val rest = arguments.iterator()
  while (rest.hasNext()) {
    when (val argument = rest.next()) {
      "--name" -> name = if (rest.hasNext()) rest.next() else throw UsageError("--name needs a value.")
      else -> if (root == null && !argument.startsWith("--")) root = argument else throw UsageError("Unexpected argument: $argument")
    }
  }
  return (root ?: throw UsageError("Name the directory to register.")) to name
}

internal class UsageError(message: String) : Exception(message)

internal const val USAGE = "usage: runtime                                         start the Runtime\n" +
  "       runtime register <directory> [--name <name>]   register a Workspace at Read"
