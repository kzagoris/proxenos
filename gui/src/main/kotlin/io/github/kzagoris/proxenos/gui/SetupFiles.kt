package io.github.kzagoris.proxenos.gui

import io.github.kzagoris.proxenos.control.ConfigToml
import io.github.kzagoris.proxenos.control.TunnelClient
import io.github.kzagoris.proxenos.control.stateDirectory
import io.github.kzagoris.proxenos.frontend.LocalSetup
import io.github.kzagoris.proxenos.frontend.Requirement
import io.github.kzagoris.proxenos.frontend.RuntimeAttachment
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.nio.file.attribute.PosixFilePermissions
import java.util.Properties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible

/** Local setup data is separate from Runtime state; credentials never travel in saved progress. */
class SetupFiles(private val environment: Map<String, String> = System.getenv()) {
  private val directory = ConfigToml.directory(environment)
  private val local = LocalSetup(environment)
  val credentials: Path = local.credentials
  private val progressFile = directory.resolve("setup.properties")
  private val privateFile = PosixFilePermissions.fromString("rw-------")

  fun tunnelId(): String? = local.tunnelId()

  fun saveCredentials(tunnelId: String, runtimeKey: String) {
    val id = token(tunnelId, "Tunnel ID")
    val key = token(runtimeKey, "Runtime key")
    replace(credentials, "TUNNEL_ID=$id\nRUNTIME_KEY=$key\n")
  }

  private fun token(value: String, label: String): String = value.trim().also {
    require(it.isNotEmpty() && it.none { character -> character.isWhitespace() || character.isISOControl() }) {
      "$label must be a single non-empty value."
    }
  }

  fun loadProgress(): SetupProgress {
    if (!Files.exists(progressFile)) return SetupProgress()
    return try {
      val values = Properties().apply { Files.newBufferedReader(progressFile).use { load(it) } }
      SetupProgress(
        step = SetupStep.valueOf(values.getProperty("step", "Welcome")),
        tunnelPart = values.getProperty("tunnelPart", "0").toInt().coerceIn(0, 2),
        furthest = SetupStep.valueOf(values.getProperty("furthest", values.getProperty("step", "Welcome"))),
        tunnelId = values.getProperty("tunnelId", ""),
        associated = values.getProperty("associated").toBoolean(),
        workspace = values.getProperty("workspace"),
        waitingStart = values.getProperty("waitingStart"),
        waitingRoot = values.getProperty("waitingRoot"),
        waitingName = values.getProperty("waitingName"),
        waitingSince = values.getProperty("waitingSince")?.toLong(),
        completed = values.getProperty("completed").toBoolean(),
      )
    } catch (_: IllegalArgumentException) {
      SetupProgress()
    }
  }

  fun saveProgress(progress: SetupProgress) {
    val values = Properties().apply {
      setProperty("step", progress.step.name)
      setProperty("furthest", progress.furthest.name)
      setProperty("tunnelPart", progress.tunnelPart.toString())
      setProperty("tunnelId", progress.tunnelId)
      setProperty("associated", progress.associated.toString())
      progress.workspace?.let { setProperty("workspace", it) }
      progress.waitingStart?.let { setProperty("waitingStart", it) }
      progress.waitingRoot?.let { setProperty("waitingRoot", it) }
      progress.waitingName?.let { setProperty("waitingName", it) }
      progress.waitingSince?.let { setProperty("waitingSince", it.toString()) }
      setProperty("completed", progress.completed.toString())
    }
    val text = java.io.StringWriter().also { values.store(it, "Proxenos setup progress") }.toString()
    replace(progressFile, text)
  }

  /** The Welcome step's checks: credentials are the Tunnel step's to save, so they are not among them. */
  fun prerequisites(runtime: Path? = RuntimeAttachment.executable(environment)): List<String> =
    local.requirements(runtime).filter { !it.met && it.name != Requirement.CREDENTIALS }.map {
      if (it.name == Requirement.TUNNEL_CLIENT)
        "The tunnel client is unavailable. Choose Install tunnel-client to download and verify it."
      else it.words
    }

  fun tunnelClient(): Path? {
    val config = ConfigToml.read(environment, ignored = "gui_scale")
    val configured = TunnelClient.configured(config, environment)
    return if (configured != null) configured.takeIf { Files.isExecutable(it) }
      else TunnelClient.search(environment, stateDirectory(config, environment))
  }

  /** Runs the distribution's verifier; no download or checksum policy lives in the GUI. */
  fun installTunnelClient(): Flow<String> = channelFlow {
    if (tunnelClient() != null) return@channelFlow
    val config = ConfigToml.read(environment, ignored = "gui_scale")
    val configured = TunnelClient.configured(config, environment)
    check(configured == null) {
      "The configured tunnel client at $configured is unavailable. Correct tunnel_client in config.toml or PROXENOS_TUNNEL_CLIENT, then check again."
    }
    val runtime = RuntimeAttachment.executable(environment)
    val installer = runtime?.toRealPath()?.parent?.resolve("install-tunnel-client")
    check(installer != null && Files.isRegularFile(installer) && Files.isExecutable(installer)) {
      "The tunnel client installer is unavailable. Reinstall the Proxenos distribution with bin/install-tunnel-client beside bin/runtime, then try again."
    }
    val builder = ProcessBuilder(installer.toString()).redirectErrorStream(true)
      .redirectInput(ProcessBuilder.Redirect.from(java.io.File("/dev/null")))
    builder.environment().apply {
      clear()
      putAll(environment)
      // Unlike the shell installer, the GUI reads state_dir in config.toml.
      put("PROXENOS_STATE_DIR", stateDirectory(config, environment).toString())
    }
    val process = builder.start()
    try {
      val output = launch {
        process.inputStream.bufferedReader().use { reader ->
          while (true) send(reader.readLine() ?: break)
        }
      }
      val exit = runInterruptible { process.waitFor() }
      output.join()
      check(exit == 0) { "The tunnel client installer exited with status $exit. Review the installer details below, correct the problem, then try again." }
      check(tunnelClient() != null) { "The installer finished, but tunnel-client is still unavailable. Check your state directory and executable permissions, then try again." }
    } finally {
      if (process.isAlive) {
        process.descendants().use { children -> children.forEach { it.destroyForcibly() } }
        process.destroyForcibly()
      }
      process.inputStream.close()
    }
  }.flowOn(Dispatchers.IO)

  private fun replace(file: Path, text: String) {
    Files.createDirectories(directory, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")))
    val temporary = Files.createTempFile(directory, ".setup-", ".tmp", PosixFilePermissions.asFileAttribute(privateFile))
    try {
      Files.writeString(temporary, text)
      Files.move(temporary, file, ATOMIC_MOVE, REPLACE_EXISTING)
    } finally {
      Files.deleteIfExists(temporary)
    }
  }
}
