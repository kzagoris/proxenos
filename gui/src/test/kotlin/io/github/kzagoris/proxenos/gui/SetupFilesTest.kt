package io.github.kzagoris.proxenos.gui

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import org.junit.jupiter.api.io.TempDir
import kotlin.test.*

class SetupFilesTest {
  @TempDir lateinit var temporary: Path

  @Test
  fun `requirements recognize the tunnel client installed in the default state tools directory`() {
    val tools = Files.createDirectories(temporary.resolve("state/proxenos/tools"))
    val tunnel = Files.writeString(tools.resolve("tunnel-client"), "#!/bin/sh\n")
    val runtime = Files.writeString(temporary.resolve("runtime"), "#!/bin/sh\n")
    listOf(tunnel, runtime).forEach { Files.setPosixFilePermissions(it, PosixFilePermissions.fromString("rwx------")) }
    val files = SetupFiles(mapOf("XDG_CONFIG_HOME" to temporary.resolve("config").toString(),
      "XDG_STATE_HOME" to temporary.resolve("state").toString(), "PATH" to ""))
    assertEquals(emptyList(), files.prerequisites(runtime))
  }

  @Test
  fun `saving credentials replaces a wide file with owner only values readable by the Runtime`() {
    val environment = mapOf("XDG_CONFIG_HOME" to temporary.toString())
    val files = SetupFiles(environment)
    val directory = Files.createDirectories(temporary.resolve("proxenos"))
    val credentials = directory.resolve("credentials")
    Files.writeString(credentials, "old")
    Files.setPosixFilePermissions(credentials, PosixFilePermissions.fromString("rw-r--r--"))
    files.saveCredentials(" tunnel_fixture ", " fixture-key ")
    assertEquals("TUNNEL_ID=tunnel_fixture\nRUNTIME_KEY=fixture-key\n", Files.readString(credentials))
    assertEquals(PosixFilePermissions.fromString("rw-------"), Files.getPosixFilePermissions(credentials))
    assertEquals("tunnel_fixture", files.tunnelId())
  }

  @Test
  fun `a multiline credential is refused before touching an existing key`() {
    val files = SetupFiles(mapOf("XDG_CONFIG_HOME" to temporary.toString()))
    files.saveCredentials("tunnel_fixture", "fixture-key")
    val credentials = temporary.resolve("proxenos/credentials")
    val before = Files.readString(credentials)
    assertFailsWith<IllegalArgumentException> { files.saveCredentials("tunnel_fixture", "bad\nTUNNEL_ID=other") }
    assertEquals(before, Files.readString(credentials))
  }

  @Test
  fun `setup resumes the chosen Workspace and wait boundary without persisting a key`() {
    val files = SetupFiles(mapOf("XDG_CONFIG_HOME" to temporary.toString()))
    val progress = SetupProgress(step = SetupStep.TryIt, workspace = "workspace-id", waitingSince = 1234L)
    files.saveProgress(progress)
    assertEquals(progress, files.loadProgress())
    assertFalse(Files.readString(temporary.resolve("proxenos/setup.properties")).contains("RUNTIME_KEY"))
    Files.writeString(temporary.resolve("proxenos/setup.properties"), "step=not-a-step\n")
    assertEquals(SetupProgress(), files.loadProgress())
  }
}
