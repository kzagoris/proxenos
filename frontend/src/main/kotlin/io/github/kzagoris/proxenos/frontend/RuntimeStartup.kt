package io.github.kzagoris.proxenos.frontend

import io.github.kzagoris.proxenos.control.ControlSocket
import io.github.kzagoris.proxenos.control.ConfigToml
import io.github.kzagoris.proxenos.control.TunnelClient
import io.github.kzagoris.proxenos.control.stateDirectory
import java.io.IOException
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.channels.SocketChannel
import java.nio.channels.FileChannel
import java.nio.channels.OverlappingFileLockException
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.nio.file.StandardOpenOption.CREATE
import java.nio.file.StandardOpenOption.WRITE
import java.nio.file.attribute.PosixFilePermissions
import java.security.MessageDigest
import java.util.Properties
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible

enum class StartupState { Off, On, NeedsAttention, Unavailable }
enum class StartupAction { Enable, Disable, UseInstallation }

data class StartupStatus(
  val state: StartupState,
  val words: String,
  val enabled: Boolean? = null,
  val lingering: Boolean? = null,
  val registered: Boolean = false,
  val managed: Boolean = false,
  val launcher: String? = null,
  val controlSocket: String? = null,
  val supervised: Boolean? = null,
  val canEnable: Boolean = false,
  val canDisable: Boolean = false,
  val canUseInstallation: Boolean = false,
) {
  val label: String get() = when (state) {
    StartupState.NeedsAttention -> "Needs attention"
    else -> state.name
  }
}

/**
 * Local installation setup, shared by both frontends; it never sends a Runtime act. Boot
 * enablement and the current Runtime's life are independent (ADR 0015).
 */
class RuntimeStartup(
  executable: Path?,
  private val socket: Path,
  private val environment: Map<String, String> = System.getenv(),
  private val system: StartupSystem = LocalStartupSystem,
) {
  private val home = Path.of(environment["HOME"] ?: System.getProperty("user.home"))
  private val config = Path.of(environment["XDG_CONFIG_HOME"] ?: home.resolve(".config").toString())
  private val directory = config.resolve("systemd/user")
  private val unit = directory.resolve(UNIT)
  private val record = directory.resolve("proxenos-startup.properties")
  private val launcher = executable?.toAbsolutePath()?.normalize()?.let(::followLauncher)

  suspend fun inspect(): StartupStatus = runInterruptible(Dispatchers.IO) { inspectNow() }
  suspend fun enable(): StartupStatus = change(StartupAction.Enable)
  suspend fun disable(): StartupStatus = change(StartupAction.Disable)
  suspend fun useInstallation(): StartupStatus = change(StartupAction.UseInstallation)

  /** False only when no unit is registered; a failed registered Start must never spawn another. */
  suspend fun startRegistered(): Boolean = runInterruptible(Dispatchers.IO) {
    val status = inspectNow()
    if (!status.registered) return@runInterruptible false
    check(status.managed && status.launcher == launcher?.toString() &&
      status.controlSocket == socket.toAbsolutePath().normalize().toString()) { status.words }
    if (awaitPreviousStop()) return@runInterruptible true
    checked("systemctl", "--user", "start", UNIT)
    true
  }

  /** True when a competing Start has made the existing Runtime ready. */
  private fun awaitPreviousStop(): Boolean {
    val toml = ConfigToml.read(environment, ignored = "gui_scale")
    val state = stateDirectory(toml, environment)
    val mcp = toml.path("mcp_socket", environment, "PROXENOS_MCP_SOCKET")
      ?: environment["XDG_RUNTIME_DIR"]?.let(Path::of)?.takeIf { it.isAbsolute }?.resolve("proxenos/mcp.sock")
    val locks = listOfNotNull(state.resolve("runtime.lock"), mcp?.resolveSibling("${mcp.fileName}.lock"))
    val deadline = System.nanoTime() + 20_000_000_000L
    // A detached Runtime closes its control socket before the MCP endpoint's grace period
    // ends and its instance locks are released. Starting the unit in that gap exits 78.
    while (locks.any { !released(it) }) {
      if (answering()) return true
      check(System.nanoTime() < deadline) {
        "Another Runtime still holds its instance lock. Wait for Stop to finish before starting again; nothing was stopped or started."
      }
      Thread.sleep(50)
    }
    return answering()
  }

  private fun answering(): Boolean = try {
    SocketChannel.open(StandardProtocolFamily.UNIX).use { it.connect(UnixDomainSocketAddress.of(socket)) }
    true
  } catch (_: IOException) { false }

  private fun released(file: Path): Boolean {
    if (!Files.exists(file)) return true
    return try {
      FileChannel.open(file, WRITE).use { channel -> channel.tryLock()?.use { true } ?: false }
    } catch (_: NoSuchFileException) { true
    } catch (_: OverlappingFileLockException) { false }
  }

  suspend fun apply(action: StartupAction): StartupStatus = when (action) {
    StartupAction.Enable -> enable()
    StartupAction.Disable -> disable()
    StartupAction.UseInstallation -> useInstallation()
  }

  private suspend fun change(action: StartupAction): StartupStatus = runInterruptible(Dispatchers.IO) {
    // Java rejects overlapping file locks within one JVM, so serialize there before taking
    // the lock that serializes separate GUI/TUI processes. Inspection never creates files.
    mutations.withLock {
      // All frontends configure the same account-wide unit, even with different XDG configs.
      val lock = home.resolve(".local/state/proxenos-startup.lock")
      Files.createDirectories(lock.parent)
      Files.createDirectories(directory)
      FileChannel.open(lock, CREATE, WRITE).use { channel ->
        channel.lock().use { changeNow(action) }
      }
    }
  }

  private fun changeNow(action: StartupAction): StartupStatus {
    val before = inspectNow()
    if (action == StartupAction.Disable) {
      if (before.state == StartupState.Off && !before.registered) return before
      // A prior Disable may already have removed enablement but failed to retain the link.
      // Repeating it must repair manual Start as well as converge on boot startup being off.
      check(before.managed) { before.words }
      checked("systemctl", "--user", "disable", UNIT)
      // disable also removes links to units outside the manager's search path. Retain the
      // registration for manual Start, even when Proxenos uses a custom XDG configuration.
      checked("systemctl", "--user", "link", unit.toAbsolutePath().toString())
      return inspectNow().also { check(it.managed && it.enabled == false) { it.words } }
    }
    if (action == StartupAction.UseInstallation && before.managed && !before.canUseInstallation &&
      before.launcher == launcher?.toString() && before.state != StartupState.Unavailable) return before
    check(before.state != StartupState.Unavailable) { before.words }
    check(if (action == StartupAction.UseInstallation) before.canUseInstallation else before.canEnable) { before.words }
    val selected = checkNotNull(launcher) { "Cannot find the Runtime launcher for this installation." }
    check(Files.isExecutable(selected)) { "The Runtime launcher at $selected is unavailable." }
    val content = unitText(selected)
    val oldUnit = unit.takeIf(Files::exists)?.let(Files::readAllBytes)
    val oldRecord = record.takeIf(Files::exists)?.let(Files::readAllBytes)
    try {
      replace(unit, content.toByteArray())
      val registration = Properties().apply {
        setProperty("launcher", selected.toString())
        setProperty("socket", socket.toAbsolutePath().normalize().toString())
        setProperty("sha256", digest(content.toByteArray()))
      }
      val text = java.io.StringWriter().also { registration.store(it, "Proxenos startup registration") }.toString()
      replace(record, text.toByteArray())
      checked("systemctl", "--user", "link", unit.toAbsolutePath().toString())
      checked("systemctl", "--user", "daemon-reload")
      if (action == StartupAction.Enable) {
        checked("loginctl", "enable-linger")
        checked("systemctl", "--user", "enable", unit.toAbsolutePath().toString())
      }
      return inspectNow().also {
        check(it.managed && it.launcher == selected.toString() &&
          (action != StartupAction.Enable || it.state == StartupState.On)) { it.words }
      }
    } catch (failed: Exception) {
      // Enable can have changed symlinks before failing. Restore only our prior enablement;
      // never --now, and never undo account-wide lingering.
      val restoreFailures = mutableListOf<String>()
      fun restoreStep(action: () -> Unit) {
        runCatching(action).exceptionOrNull()?.let { restoreFailures.add(it.message ?: "Unknown restoration failure") }
      }
      restoreStep { if (action == StartupAction.Enable && before.enabled != true) checked("systemctl", "--user", "disable", UNIT) }
      restoreStep { restore(unit, oldUnit) }
      restoreStep { restore(record, oldRecord) }
      // disable removes custom-XDG links as well as boot enablement. Restore an existing
      // manual registration before claiming that the previous setup has been restored.
      restoreStep { if (oldUnit != null) checked("systemctl", "--user", "link", unit.toAbsolutePath().toString()) }
      restoreStep { checked("systemctl", "--user", "daemon-reload") }
      throw IllegalStateException("${failed.message ?: "Startup configuration failed."} " +
        if (restoreFailures.isEmpty()) "Proxenos registration was restored; the Runtime was left unchanged. Account lingering, if enabled, was left enabled."
        else "Registration could not be fully restored: ${restoreFailures.joinToString("; ")}. Check automatic startup before retrying.", failed)
    }
  }

  private fun inspectNow(): StartupStatus {
    val exists = Files.exists(unit)
    val registration = runCatching { Properties().apply { if (Files.exists(record)) Files.newBufferedReader(record).use(::load) } }.getOrDefault(Properties())
    val unitMatchesRecord = exists && runCatching { digest(Files.readAllBytes(unit)) == registration.getProperty("sha256") }.getOrDefault(false)
    val registeredLauncher = registration.getProperty("launcher")
    fun unavailable(words: String) = StartupStatus(StartupState.Unavailable, words, registered = exists,
      managed = unitMatchesRecord, launcher = registeredLauncher)
    val reply = try {
      system.run(listOf("systemctl", "--user", "show", UNIT, "--property=LoadState,UnitFileState,ActiveState,FragmentPath,DropInPaths,Result,ExecMainStatus,InvocationID"))
    } catch (_: Exception) { return unavailable("The systemd user manager is unavailable. Manual startup remains available when no unit is registered.") }
    if (reply.code != 0) return unavailable("The systemd user manager did not answer: ${reply.words}")
    val values = reply.words.lineSequence().filter { '=' in it }.associate { it.substringBefore('=') to it.substringAfter('=') }
    val fragment = values["FragmentPath"].orEmpty()
    val registered = exists || fragment.isNotEmpty()
    val enabled = values["UnitFileState"] in listOf("enabled", "enabled-runtime")
    val persistent = values["UnitFileState"] == "enabled"
    val active = values["ActiveState"] in listOf("active", "activating", "reloading")
    val linger = runCatching { system.run(listOf("loginctl", "show-user", System.getProperty("user.name"), "--property=Linger", "--value")) }.getOrNull()
    val lingering = if (linger?.code == 0) when (linger.words.trim()) { "yes" -> true; "no" -> false; else -> null } else null
    val effectiveUnitUnmodified = unitMatchesRecord && values["DropInPaths"].isNullOrBlank() &&
      // A custom XDG configuration is linked into the manager's own search path. Its
      // FragmentPath names that link; compare file identity rather than spelling.
      (fragment.isEmpty() || runCatching { Files.isSameFile(Path.of(fragment), unit) }.getOrDefault(false))
    val installationAndSocketMatch = registeredLauncher == launcher?.toString() && registration.getProperty("socket") == socket.toAbsolutePath().normalize().toString()
    val executable = launcher?.let(Files::isExecutable) == true
    val (state, words) = when {
      registered && !effectiveUnitUnmodified -> StartupState.NeedsAttention to
        "The existing $UNIT is external or has been modified. It was left unchanged; review it before configuring Proxenos startup."
      registered && !installationAndSocketMatch -> StartupState.NeedsAttention to
        "Automatic startup uses $registeredLauncher. Use this installation to register ${launcher ?: "an available Runtime launcher"}."
      registered && !executable -> StartupState.NeedsAttention to
        "The registered Runtime launcher at $registeredLauncher is unavailable. Restore the release or use another installation."
      lingering == null -> StartupState.Unavailable to
        "Account lingering cannot be checked because logind is unavailable or refused access. Startup before login cannot be configured here."
      registered && values["LoadState"] != "loaded" -> StartupState.NeedsAttention to
        "The registered unit is not loaded by systemd. Enable automatic startup to repair its registration."
      registered && values["ActiveState"] == "failed" -> StartupState.NeedsAttention to failureWords(values)
      registered && persistent && lingering -> StartupState.On to
        "The Runtime is configured to start at boot, before login."
      enabled -> StartupState.NeedsAttention to
        "The unit is enabled without persistent boot startup and account lingering. Enable automatic startup to complete the configuration."
      else -> StartupState.Off to "Automatic startup is off. Opening a frontend still starts an absent Runtime."
    }
    return StartupStatus(state, words, enabled = if (registered) enabled else false, lingering = lingering,
      registered = registered, managed = effectiveUnitUnmodified, launcher = registeredLauncher,
      controlSocket = registration.getProperty("socket"), supervised = if (registered) active else false,
      canEnable = lingering != null && executable && (!registered || (effectiveUnitUnmodified && installationAndSocketMatch)),
      canDisable = registered && effectiveUnitUnmodified && (enabled || values["LoadState"] != "loaded"),
      canUseInstallation = lingering != null && registered && effectiveUnitUnmodified && !installationAndSocketMatch && executable)
  }

  private fun failureWords(values: Map<String, String>): String {
    val invocation = values["InvocationID"]?.takeIf { it.matches(Regex("[0-9a-f]{32}")) }
    // Only the Runtime's deliberately secret-free startup refusal is displayed. Never show
    // arbitrary tunnel/child output, nor a refusal from an earlier invocation.
    val refusal = invocation?.let { id -> runCatching {
      system.run(listOf("journalctl", "--user", "--unit=$UNIT", "_SYSTEMD_INVOCATION_ID=$id",
        "--output=cat", "--no-pager", "--lines=20", "--grep=^runtime: will not start[.]"))
        .takeIf { it.code == 0 }?.words?.lineSequence()
        ?.firstOrNull { it.startsWith("runtime: ${ControlSocket.REFUSAL}") }
        ?.removePrefix("runtime: ${ControlSocket.REFUSAL}")?.take(4096)
    }.getOrNull() }
    val setup = if (refusal == null) runCatching {
      LocalSetup(environment).requirements(launcher).filterNot { it.met }.joinToString(" ") { it.words }
    }.getOrNull().orEmpty() else refusal
    return "The Runtime unit failed (result ${values["Result"] ?: "unknown"}, status ${values["ExecMainStatus"] ?: "unknown"}). " +
      "$setup Review systemctl --user status $UNIT or journalctl --user -u $UNIT. Start Runtime retries without changing boot startup."
  }

  private fun checked(vararg arguments: String) {
    val reply = system.run(arguments.toList())
    check(reply.code == 0) { "${arguments.first()} failed: ${reply.words}" }
  }

  private fun unitText(selected: Path): String {
    val toml = ConfigToml.read(environment, ignored = "gui_scale")
    val state = stateDirectory(toml, environment)
    val tunnel = (TunnelClient.configured(toml, environment) ?: TunnelClient.search(environment, state))
      ?.toAbsolutePath()?.normalize()
    check(tunnel != null && Files.isExecutable(tunnel)) {
      "Cannot configure boot startup: the tunnel executable is unavailable. Install it or set tunnel_client in config.toml."
    }
    val paths = environment.filterKeys { it in PATH_SETTINGS }.toMutableMap()
    // A tunnel found only on a shell's PATH must also resolve under the user manager.
    paths["PROXENOS_TUNNEL_CLIENT"] = tunnel.toString()
    paths["HOME"] = home.toString()
    paths["XDG_CONFIG_HOME"] = config.toString()
    paths["XDG_STATE_HOME"] = environment["XDG_STATE_HOME"] ?: home.resolve(".local/state").toString()
    paths["XDG_DATA_HOME"] = environment["XDG_DATA_HOME"] ?: home.resolve(".local/share").toString()
    paths[ControlSocket.VARIABLE] = socket.toAbsolutePath().normalize().toString()
    // A portable release's Java must work with the manager's short PATH too. The launcher's
    // own jre/ still wins over JAVA_HOME (ADR 0010).
    paths["JAVA_HOME"] = environment["JAVA_HOME"] ?: System.getProperty("java.home")
    check(Files.isExecutable(selected.parent.parent.resolve("jre/bin/java")) ||
      Files.isExecutable(Path.of(paths.getValue("JAVA_HOME")).resolve("bin/java"))) {
      "Cannot configure boot startup: Java is unavailable outside the shell. Restore the bundled jre or set JAVA_HOME."
    }
    return buildString {
      appendLine("# Managed by Proxenos. Change startup through the GUI or TUI.")
      appendLine("[Unit]\nDescription=Proxenos Runtime")
      appendLine("[Service]\nType=simple")
      // systemd rejects some valid filenames as the executable itself. Pass the launcher
      // as data to a fixed exec command; ':' prevents systemd expanding literal dollars.
      appendLine("ExecStart=:/bin/sh -c 'exec \"${'$'}1\"' proxenos-runtime ${quote(selected.toString())}")
      paths.toSortedMap().forEach { (key, value) -> appendLine("Environment=${quote("$key=$value")}") }
      appendLine("Restart=on-failure\nRestartPreventExitStatus=78\nKillMode=mixed\nTimeoutStopSec=30")
      appendLine("[Install]\nWantedBy=default.target")
    }
  }

  private fun quote(value: String): String {
    require(value.none { it.isISOControl() }) { "Startup paths must not contain control characters." }
    val escaped = value.replace("\\", "\\\\").replace("\"", "\\\"").replace("%", "%%")
    return "\"$escaped\""
  }

  private fun replace(file: Path, bytes: ByteArray) {
    val temporary = Files.createTempFile(directory, ".startup-", ".tmp",
      PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")))
    try {
      Files.write(temporary, bytes)
      Files.move(temporary, file, ATOMIC_MOVE, REPLACE_EXISTING)
    } finally { Files.deleteIfExists(temporary) }
  }

  private fun restore(file: Path, bytes: ByteArray?) { if (bytes == null) Files.deleteIfExists(file) else replace(file, bytes) }

  private fun digest(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

  private fun followLauncher(path: Path): Path {
    var current = path
    repeat(40) {
      if (!Files.isSymbolicLink(current)) return current
      val link = Files.readSymbolicLink(current)
      current = (if (link.isAbsolute) link else current.parent.resolve(link)).normalize()
    }
    return current
  }

  companion object {
    const val UNIT = "proxenos.service"
    private val mutations = ReentrantLock()
    private val PATH_SETTINGS = setOf("XDG_STATE_HOME", "XDG_DATA_HOME", "PROXENOS_STATE_DIR", "PROXENOS_MCP_SOCKET",
      "PROXENOS_TUNNEL_CLIENT")
  }
}
