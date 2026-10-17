package io.github.kzagoris.proxenos.gui

import io.github.kzagoris.proxenos.control.ConfigToml
import io.github.kzagoris.proxenos.frontend.LocalSetup
import io.github.kzagoris.proxenos.frontend.Requirement
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
  fun prerequisites(runtime: Path? = RuntimeAttachment.executable()): List<String> =
    local.requirements(runtime).filter { !it.met && it.name != Requirement.CREDENTIALS }.map { it.words }

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
