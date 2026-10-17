package io.github.kzagoris.proxenos.gui

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import io.github.kzagoris.proxenos.frontend.RuntimeAttachment
import io.github.kzagoris.proxenos.frontend.Attachment
import org.junit.jupiter.api.io.TempDir
import kotlin.test.*

class GuiTunnelInstallTest {
  @TempDir lateinit var temporary: Path

  private val state get() = temporary.resolve("custom state")
  private val bin get() = temporary.resolve("distribution/bin")
  private val environment get() = mapOf(
    "HOME" to temporary.toString(),
    "XDG_CONFIG_HOME" to temporary.resolve("config").toString(),
    "PATH" to "/usr/bin:/bin",
    "PROXENOS_RUNTIME" to bin.resolve("runtime").toString(),
    "TUNNEL_CLIENT_RELEASES" to temporary.resolve("releases").toUri().toString(),
  )

  @BeforeTest
  fun distribution() {
    Files.createDirectories(bin)
    executable(bin.resolve("runtime"), "#!/bin/sh\nexit 91\n")
    Files.copy(Path.of(System.getProperty("proxenos.testInstaller")), bin.resolve("install-tunnel-client"))
    val config = Files.createDirectories(temporary.resolve("config/proxenos"))
    Files.writeString(config.resolve("config.toml"), "state_dir = \"$state\"\n")
  }

  @Test
  fun `GUI installs the verified release in the configured state directory and rechecks requirements`() = runBlocking {
    publish()
    val files = SetupFiles(environment)
    assertTrue(files.prerequisites(bin.resolve("runtime")).isNotEmpty())
    val output = files.installTunnelClient().toList().joinToString("\n")
    assertContains(output, "verified")
    assertContains(output, "SHA256SUMS.txt")
    assertTrue(Files.isExecutable(state.resolve("tools/tunnel-client")))
    assertEquals(emptyList(), files.prerequisites(bin.resolve("runtime")))
  }

  @Test
  fun `only an explicit install starts one installer while the owner remains responsive and rechecks success`() = runBlocking {
    val gate = temporary.resolve("gate")
    val starts = temporary.resolve("starts")
    executable(bin.resolve("install-tunnel-client"), """
      #!/bin/bash
      echo started >> '$starts'
      echo 'downloading fixture'
      while [ ! -f '$gate' ]; do sleep 0.05; done
      mkdir -p "${'$'}PROXENOS_STATE_DIR/tools"
      printf '#!/bin/sh\n' > "${'$'}PROXENOS_STATE_DIR/tools/tunnel-client"
      chmod 755 "${'$'}PROXENOS_STATE_DIR/tools/tunnel-client"
    """.trimIndent())
    val files = SetupFiles(environment)
    GuiOwner(RuntimeAttachment(temporary.resolve("control.sock"), bin.resolve("runtime")), setupFiles = files).use { gui ->
      gui.open()
      withTimeout(5000) { gui.state.first { it.setupChecks != null && it.attachment is Attachment.Absent } }
      gui.accept(GuiIntent.CheckSetup)
      gui.accept(GuiIntent.SetupNavigate(SetupStep.Tunnel))
      withTimeout(5000) { gui.state.first { it.setup?.step == SetupStep.Tunnel } }
      assertFalse(Files.exists(starts), "opening and checking must never install")
      gui.accept(GuiIntent.SetupNavigate(SetupStep.Welcome))
      gui.accept(GuiIntent.InstallTunnelClient)
      val installing = withTimeout(5000) { gui.state.first { "downloading fixture" in it.setupInstallOutput } }
      assertTrue(installing.setupBusy)
      gui.accept(GuiIntent.InstallTunnelClient)
      gui.accept(GuiIntent.Show(Destination.Activity))
      withTimeout(5000) { gui.state.first { it.destination == Destination.Activity } }
      Files.createFile(gate)
      val finished = withTimeout(5000) { gui.state.first { !it.setupBusy && it.setupChecks == emptyList<String>() } }
      assertNull(finished.setupError)
      assertFalse(finished.setupTunnelMissing)
      assertEquals(1, Files.readAllLines(starts).size)
      assertFalse(Files.exists(files.credentials), "installation must not write credentials")
      assertFalse(Files.exists(temporary.resolve("control.sock")), "installation must not start the Runtime")
    }
  }

  @Test
  fun `checksum refusal keeps details and permits an explicit successful retry`() = runBlocking {
    publish(checksum = "0".repeat(64))
    val files = SetupFiles(environment)
    GuiOwner(RuntimeAttachment(temporary.resolve("control.sock"), bin.resolve("runtime")), setupFiles = files).use { gui ->
      gui.open()
      withTimeout(5000) { gui.state.first { it.setupTunnelMissing && it.attachment is Attachment.Absent } }
      gui.accept(GuiIntent.InstallTunnelClient)
      val failed = withTimeout(5000) { gui.state.first { it.setupError != null && !it.setupBusy } }
      assertContains(failed.setupInstallOutput, "does not match")
      assertTrue(failed.setupTunnelMissing)
      assertFalse(Files.exists(state.resolve("tools/tunnel-client")))
      publish()
      gui.accept(GuiIntent.InstallTunnelClient)
      val installed = withTimeout(5000) { gui.state.first { !it.setupTunnelMissing && !it.setupBusy } }
      assertNull(installed.setupError)
      assertEquals(emptyList(), installed.setupChecks)
      assertFalse(installed.setupInstallOutput.contains("does not match"))
    }
  }

  @Test
  fun `download failure is visible and never reports installed`() = runBlocking {
    failedInstall("could not download")
  }

  @Test
  fun `missing checksum is visible and never reports installed`() = runBlocking {
    publish()
    Files.writeString(temporary.resolve("releases/v0.0.14/SHA256SUMS.txt"), "")
    failedInstall("is not listed")
  }

  @Test
  fun `an archive without the executable is visibly refused`() = runBlocking {
    publish(entry = "LICENSE")
    failedInstall("has no tunnel-client")
  }

  @Test
  fun `an unwritable state directory leaves an actionable failure`() = runBlocking {
    publish()
    Files.createDirectories(state)
    Files.setPosixFilePermissions(state, PosixFilePermissions.fromString("r-x------"))
    try {
      failedInstall("Permission denied")
    } finally {
      Files.setPosixFilePermissions(state, PosixFilePermissions.fromString("rwx------"))
    }
  }

  @Test
  fun `missing installer explains how to repair the distribution`() = runBlocking {
    Files.delete(bin.resolve("install-tunnel-client"))
    failedInstall("Reinstall the Proxenos distribution")
  }

  @Test
  fun `a zero exit without an executable cannot claim installation succeeded`() = runBlocking {
    executable(bin.resolve("install-tunnel-client"), "#!/bin/sh\nexit 0\n")
    failedInstall("still unavailable")
  }

  @Test
  fun `an existing PATH executable wins over state tools and requires no installer`() = runBlocking {
    val onPath = executable(Files.createDirectories(temporary.resolve("path")).resolve("tunnel-client"), "#!/bin/sh\n")
    val inState = executable(Files.createDirectories(state.resolve("tools")).resolve("tunnel-client"), "#!/bin/sh\necho state\n")
    Files.delete(bin.resolve("install-tunnel-client"))
    val files = SetupFiles(environment + ("PATH" to "${onPath.parent}:/usr/bin:/bin"))
    assertEquals(onPath, files.tunnelClient())
    assertEquals(emptyList(), files.installTunnelClient().toList())
    assertEquals("#!/bin/sh\n", Files.readString(onPath))
    assertEquals("#!/bin/sh\necho state\n", Files.readString(inState))
    assertEquals(emptyList(), files.prerequisites())
  }

  @Test
  fun `an unavailable configured executable asks for correction instead of installing an unused copy`() = runBlocking {
    publish()
    failedInstall("Correct tunnel_client", environment + ("PROXENOS_TUNNEL_CLIENT" to temporary.resolve("elsewhere").toString()))
  }

  @Test
  fun `closing the GUI reaps an installer waiting for a download`() = runBlocking {
    val pid = temporary.resolve("installer-pid")
    executable(bin.resolve("install-tunnel-client"), "#!/bin/sh\necho $$ > '$pid'\necho downloading\nsleep 300\n")
    val gui = GuiOwner(RuntimeAttachment(temporary.resolve("control.sock"), bin.resolve("runtime")), setupFiles = SetupFiles(environment))
    try {
      gui.open()
      withTimeout(5000) { gui.state.first { it.setupTunnelMissing } }
      gui.accept(GuiIntent.InstallTunnelClient)
      withTimeout(5000) { gui.state.first { "downloading" in it.setupInstallOutput } }
      val process = ProcessHandle.of(Files.readString(pid).trim().toLong()).orElseThrow()
      assertTrue(process.isAlive)
      gui.close()
      withTimeout(5000) { while (process.isAlive) delay(10) }
    } finally {
      gui.close()
    }
  }

  private suspend fun failedInstall(details: String, env: Map<String, String> = environment) {
    GuiOwner(RuntimeAttachment(temporary.resolve("control.sock"), bin.resolve("runtime")), setupFiles = SetupFiles(env)).use { gui ->
      gui.open()
      withTimeout(5000) { gui.state.first { it.setupTunnelMissing } }
      gui.accept(GuiIntent.InstallTunnelClient)
      val failed = withTimeout(5000) { gui.state.first { it.setupError != null && !it.setupBusy } }
      assertContains(failed.setupError + failed.setupInstallOutput, details)
      assertTrue(failed.setupTunnelMissing)
      assertTrue(failed.setupChecks!!.isNotEmpty())
      assertFalse(Files.exists(state.resolve("tools/tunnel-client")))
    }
  }

  private fun publish(entry: String = "tunnel-client", checksum: String? = null) {
    val version = "0.0.14"
    val arch = if (System.getProperty("os.arch") in listOf("amd64", "x86_64")) "amd64" else "arm64"
    val release = Files.createDirectories(temporary.resolve("releases/v$version"))
    val archive = release.resolve("tunnel-client-v$version-linux-$arch.zip")
    ZipOutputStream(Files.newOutputStream(archive)).use {
      it.putNextEntry(ZipEntry(entry))
      it.write("#!/bin/sh\necho fixture\n".toByteArray())
      it.closeEntry()
    }
    val hash = checksum ?: MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(archive))
      .joinToString("") { "%02x".format(it) }
    Files.writeString(release.resolve("SHA256SUMS.txt"), "$hash  ${archive.fileName}\n")
  }

  private fun executable(path: Path, script: String): Path = Files.writeString(path, script).also {
    Files.setPosixFilePermissions(it, PosixFilePermissions.fromString("rwx------"))
  }
}
