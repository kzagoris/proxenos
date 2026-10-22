package io.github.kzagoris.proxenos.frontend

import java.nio.file.Files
import java.nio.file.Path
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.io.TempDir
import kotlin.test.*

class RuntimeStartupTest {
  @TempDir lateinit var temporary: Path

  /** An OS boundary that models observed state, never an expected sequence of commands. */
  private class Machine : StartupSystem {
    var enabled = false
    var lingering = false
    var denyLinger = false
    var failEnable = false
    var starts = 0
    var failStart = false
    var failLink = false
    var linked = true
    var active = false
    override fun run(arguments: List<String>): StartupReply = when {
      arguments.contains("show-user") -> StartupReply(0, if (lingering) "yes" else "no")
      arguments.contains("enable-linger") -> if (denyLinger) StartupReply(1, "Authorization denied")
        else StartupReply(0, "").also { lingering = true }
      arguments.contains("show") -> StartupReply(0, "LoadState=${if (linked) "loaded" else "not-found"}\nUnitFileState=${if (enabled) "enabled" else "disabled"}\nActiveState=${if (active) "active" else "inactive"}\nDropInPaths=\n")
      arguments.contains("enable") -> if (failEnable) StartupReply(1, "Read-only filesystem")
        else StartupReply(0, "").also { enabled = true }
      arguments.contains("disable") -> StartupReply(0, "").also { enabled = false; linked = false }
      arguments.contains("link") -> if (failLink) StartupReply(1, "Temporary user manager failure")
        else StartupReply(0, "").also { linked = true }
      arguments.contains("start") -> if (failStart || !linked) StartupReply(1, "Runtime refused to start")
        else StartupReply(0, "").also { starts++; active = true }
      else -> StartupReply(0, "")
    }
  }

  private val environment get() = mapOf("HOME" to temporary.toString(),
    "XDG_CONFIG_HOME" to temporary.resolve("config").toString(),
    "PROXENOS_TUNNEL_CLIENT" to launcher("tools/tunnel-client").toString(), "XDG_RUNTIME_DIR" to temporary.resolve("run").toString())

  private fun launcher(name: String = "release/bin/runtime"): Path = temporary.resolve(name).also {
    Files.createDirectories(it.parent)
    Files.writeString(it, "#!/bin/sh\nexit 0\n")
    it.toFile().setExecutable(true)
  }

  @Test fun `enabling and disabling future boot startup never starts or stops the current Runtime`() = runBlocking {
    val machine = Machine().apply { active = true }
    val startup = RuntimeStartup(launcher(), temporary.resolve("run/control.sock"), environment, machine)
    assertEquals(StartupState.Off, startup.inspect().state)
    assertEquals(StartupState.On, startup.enable().state)
    assertTrue(machine.lingering)
    assertEquals(0, machine.starts)
    assertTrue(machine.active)
    assertEquals(StartupState.Off, startup.disable().state)
    assertTrue(machine.active)
    assertTrue(machine.lingering, "other user services can depend on lingering")
    assertTrue(startup.startRegistered(), "disabled boot startup still permits a supervised manual Start")
    assertEquals(1, machine.starts)
  }

  @Test fun `authorization denial leaves no enabled registration and a safe retry succeeds`() = runBlocking {
    val machine = Machine().apply { denyLinger = true }
    val startup = RuntimeStartup(launcher(), temporary.resolve("run/control.sock"), environment, machine)
    assertFailsWith<IllegalStateException> { startup.enable() }
    assertFalse(machine.enabled)
    assertFalse(Files.exists(temporary.resolve("config/systemd/user/proxenos.service")))
    machine.denyLinger = false
    assertEquals(StartupState.On, startup.enable().state)
  }

  @Test fun `failed enable restores registration but never disables account lingering`() = runBlocking {
    val machine = Machine().apply { failEnable = true }
    val startup = RuntimeStartup(launcher(), temporary.resolve("run/control.sock"), environment, machine)
    assertFailsWith<IllegalStateException> { startup.enable() }
    assertFalse(machine.enabled)
    assertTrue(machine.lingering)
    assertFalse(Files.exists(temporary.resolve("config/systemd/user/proxenos.service")))
    machine.failEnable = false
    assertEquals(StartupState.On, startup.enable().state)
  }

  @Test fun `a modified unit is reported and survives every registration action`() = runBlocking {
    val machine = Machine()
    val startup = RuntimeStartup(launcher(), temporary.resolve("run/control.sock"), environment, machine)
    startup.enable()
    val file = temporary.resolve("config/systemd/user/proxenos.service")
    Files.writeString(file, Files.readString(file) + "\n# user's customization\n")
    val changed = Files.readString(file)
    assertEquals(StartupState.NeedsAttention, startup.inspect().state)
    assertFailsWith<IllegalStateException> { startup.enable() }
    assertFailsWith<IllegalStateException> { startup.useInstallation() }
    assertEquals(changed, Files.readString(file))
  }

  @Test fun `a registered launch failure cannot fall back to a detached Runtime`() = runBlocking {
    val machine = Machine()
    val startup = RuntimeStartup(launcher(), temporary.resolve("run/control.sock"), environment, machine)
    assertFalse(startup.startRegistered())
    startup.enable()
    machine.failStart = true
    assertFailsWith<IllegalStateException> { startup.startRegistered() }
    assertEquals(0, machine.starts)
  }

  @Test fun `another release requires explicit registration repair without changing boot enablement`() = runBlocking {
    val machine = Machine()
    val socket = temporary.resolve("run/control.sock")
    RuntimeStartup(launcher(), socket, environment, machine).enable()
    val moved = RuntimeStartup(launcher("new release/bin/runtime"), socket, environment, machine)
    assertEquals(StartupState.NeedsAttention, moved.inspect().state)
    assertTrue(moved.inspect().canUseInstallation)
    assertEquals(StartupState.On, moved.useInstallation().state)
    assertEquals(0, machine.starts)
    moved.disable()
    assertEquals(StartupState.Off, moved.useInstallation().state)
  }

  @Test fun `a frontend selecting another socket must repair registration before a manual Start`() = runBlocking {
    val machine = Machine()
    val runtime = launcher()
    RuntimeStartup(runtime, temporary.resolve("run/first.sock"), environment, machine).enable()
    val other = RuntimeStartup(runtime, temporary.resolve("run/other.sock"), environment, machine)
    assertFailsWith<IllegalStateException> { other.startRegistered() }
    assertEquals(0, machine.starts, "a Start on the wrong control socket must not act")
    other.useInstallation()
    assertTrue(other.startRegistered())
  }

  @Test fun `retrying a partial Disable restores supervised manual Start while keeping boot startup off`() = runBlocking {
    val machine = Machine()
    val startup = RuntimeStartup(launcher(), temporary.resolve("run/control.sock"), environment, machine)
    startup.enable()
    machine.failLink = true
    assertFailsWith<IllegalStateException> { startup.disable() }
    assertTrue(startup.inspect().canDisable, "both frontends must offer the partial Disable retry")
    machine.failLink = false
    assertEquals(StartupState.Off, startup.disable().state)
    assertTrue(startup.startRegistered())
    assertFalse(machine.enabled)
    assertTrue(machine.lingering)
  }

  @Test fun `denied Enable preserves a previously disabled manual registration`() = runBlocking {
    val machine = Machine()
    val startup = RuntimeStartup(launcher(), temporary.resolve("run/control.sock"), environment, machine)
    startup.enable()
    startup.disable()
    machine.denyLinger = true
    assertFailsWith<IllegalStateException> { startup.enable() }
    assertTrue(startup.startRegistered(), "rollback must retain the pre-existing manual Start link")
    assertFalse(machine.enabled)
  }

  @Test fun `an unavailable configured tunnel executable cannot enable boot startup`() = runBlocking {
    val machine = Machine()
    val startup = RuntimeStartup(launcher(), temporary.resolve("run/control.sock"),
      environment + ("PROXENOS_TUNNEL_CLIENT" to temporary.resolve("missing-tunnel-client").toString()), machine)
    assertFailsWith<IllegalStateException> { startup.enable() }
    assertFalse(machine.enabled)
    assertEquals(StartupState.Off, startup.inspect().state)
  }

}
