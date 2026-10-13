package io.github.kzagoris.proxenos.gui

import io.github.kzagoris.proxenos.control.ConfigToml
import io.github.kzagoris.proxenos.frontend.RuntimeAttachment
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.nio.file.attribute.PosixFilePermissions
import java.util.Properties

/** Local setup data is separate from Runtime state; credentials never travel in saved progress. */
class SetupFiles(private val environment: Map<String, String> = System.getenv()) {
  private val directory = ConfigToml.directory(environment)
  val credentials: Path = directory.resolve("credentials")
  private val progressFile = directory.resolve("setup.properties")
  private val privateFile = PosixFilePermissions.fromString("rw-------")

  fun tunnelId(): String? {
    if (!Files.isRegularFile(credentials)) return null
    if (Files.getPosixFilePermissions(credentials).any { it !in privateFile }) return null
    return Files.readAllLines(credentials).firstOrNull { it.trim().startsWith("TUNNEL_ID=") }
      ?.substringAfter('=')?.trim()?.takeIf { it.isNotEmpty() }
  }

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

  fun prerequisites(runtime: Path? = RuntimeAttachment.executable()): List<String> {
    val words = mutableListOf<String>()
    if (runtime == null || !Files.isExecutable(runtime)) words += "The Runtime launcher is unavailable. Install the Proxenos distribution or set PROXENOS_RUNTIME."
    val config = ConfigToml.read(environment, ignored = "gui_scale")
    val configured = config.path("tunnel_client", environment, "PROXENOS_TUNNEL_CLIENT")
    val state = config.path("state_dir", environment, "PROXENOS_STATE_DIR")
      ?: (environment["XDG_STATE_HOME"]?.let(Path::of)?.takeIf { it.isAbsolute }
        ?: ConfigToml.home(environment).resolve(".local/state")).resolve("proxenos")
    val candidates = if (configured != null) listOf(configured) else
      environment["PATH"].orEmpty().split(':').filter { it.isNotEmpty() }.map { Path.of(it).resolve("tunnel-client") } +
        listOf(state.resolve("tools/tunnel-client"))
    if (candidates.none { Files.isRegularFile(it) && Files.isExecutable(it) })
      words += "The tunnel client is unavailable. Run bin/install-tunnel-client from the Proxenos distribution to install it, then check again."
    return words
  }

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
