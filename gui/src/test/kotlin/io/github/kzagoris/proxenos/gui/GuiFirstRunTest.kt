package io.github.kzagoris.proxenos.gui

import io.github.kzagoris.proxenos.control.ManagementClient
import io.github.kzagoris.proxenos.coreapi.ManagementAct
import io.github.kzagoris.proxenos.frontend.Attachment
import io.github.kzagoris.proxenos.frontend.Reason
import io.github.kzagoris.proxenos.frontend.RuntimeAttachment
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.io.TempDir
import kotlin.test.*
import kotlin.time.Duration.Companion.seconds

class GuiFirstRunTest {
  @TempDir lateinit var temporary: Path

  @Test
  fun `first run names the wizard and a wide credential mode refuses an explicit Start`() = runBlocking<Unit> {
    val config = Files.createDirectories(temporary.resolve("config"))
    val socket = temporary.resolve("control.sock")
    val launcher = temporary.resolve("runtime")
    fun quote(value: String) = "'" + value.replace("'", "'\"'\"'") + "'"
    Files.writeString(launcher, "#!/bin/sh\n" +
      "export XDG_CONFIG_HOME=${quote(config.toString())}\n" +
      "export JAVA_HOME=${quote(System.getProperty("java.home"))}\n" +
      "exec ${quote(System.getProperty("proxenos.testRuntime"))}\n")
    Files.setPosixFilePermissions(launcher, PosixFilePermissions.fromString("rwx------"))

    GuiOwner(RuntimeAttachment(socket, launcher)).use { gui ->
      gui.open()
      val missing = withTimeout(10.seconds) { gui.state.first { it.attachment is Attachment.Absent } }
      assertContains(missing.runtimeWords, "No credentials file")
      assertContains(missing.runtimeWords, "setup wizard")
      assertContains(missing.runtimeWords, "bin/wizard")
      assertNull(missing.snapshot)

      val credentials = Files.createDirectories(config.resolve("proxenos")).resolve("credentials")
      Files.writeString(credentials, "TUNNEL_ID=fixture\nRUNTIME_KEY=fixture\n")
      Files.setPosixFilePermissions(credentials, PosixFilePermissions.fromString("rw-r--r--"))
      gui.accept(GuiIntent.StartRuntime)
      val wide = withTimeout(10.seconds) { gui.state.first {
        (it.attachment as? Attachment.Absent)?.reason.let { reason -> reason is Reason.StartFailed && "0644" in reason.words }
      } }
      assertContains(wide.runtimeWords, "chmod 600")
      assertNull(wide.snapshot)
      assertFalse(Files.exists(socket))
    }
  }

  @Test
  fun `two GUI starts leave one Runtime and one tunnel child`() = runBlocking<Unit> {
    val config = Files.createDirectories(temporary.resolve("config/proxenos"))
    val state = Files.createDirectories(temporary.resolve("state"))
    val run = Files.createDirectories(temporary.resolve("run"))
    val socket = run.resolve("control.sock")
    val credentials = config.resolve("credentials")
    Files.writeString(credentials, "TUNNEL_ID=fixture\nRUNTIME_KEY=fixture\n")
    Files.setPosixFilePermissions(credentials, PosixFilePermissions.fromString("rw-------"))
    val starts = temporary.resolve("tunnel-starts")
    val pid = temporary.resolve("runtime-pid")
    val tunnel = temporary.resolve("tunnel-client")
    Files.writeString(tunnel, "#!/bin/sh\necho started >> '$starts'\necho \"\$PPID\" > '$pid'\nexec sleep 300\n")
    Files.setPosixFilePermissions(tunnel, PosixFilePermissions.fromString("rwx------"))
    val launcher = temporary.resolve("runtime")
    fun quote(value: String) = "'" + value.replace("'", "'\"'\"'") + "'"
    Files.writeString(launcher, "#!/bin/sh\n" +
      "export XDG_CONFIG_HOME=${quote(config.parent.toString())}\n" +
      "export XDG_STATE_HOME=${quote(state.toString())}\n" +
      "export XDG_RUNTIME_DIR=${quote(run.toString())}\n" +
      "export PROXENOS_TUNNEL_CLIENT=${quote(tunnel.toString())}\n" +
      "export JAVA_HOME=${quote(System.getProperty("java.home"))}\n" +
      "exec ${quote(System.getProperty("proxenos.testRuntime"))}\n")
    Files.setPosixFilePermissions(launcher, PosixFilePermissions.fromString("rwx------"))

    val first = GuiOwner(RuntimeAttachment(socket, launcher))
    val second = GuiOwner(RuntimeAttachment(socket, launcher))
    try {
      first.open()
      second.open()
      val arrivals = listOf(first, second).map { gui ->
        async { withTimeout(15.seconds) { gui.state.first { it.snapshot != null || it.attachment is Attachment.Absent } } }
      }.map { it.await() }
      assertTrue(arrivals.any { it.snapshot != null }, arrivals.toString())
      assertTrue(arrivals.all { it.snapshot != null || "already running" in it.runtimeWords }, arrivals.toString())
      assertEquals(1, Files.readAllLines(starts).size, "both GUI launches must share one Runtime")
    } finally {
      first.close()
      second.close()
      runCatching { ManagementClient(socket).perform(ManagementAct.Stop) }
      if (Files.exists(pid)) ProcessHandle.of(Files.readString(pid).trim().toLong()).ifPresent { it.destroyForcibly() }
    }
  }
}
