package io.github.kzagoris.proxenos.tui

import io.github.kzagoris.proxenos.control.ControlServer
import io.github.kzagoris.proxenos.control.ManagementClient
import io.github.kzagoris.proxenos.core.Activity
import io.github.kzagoris.proxenos.core.ConnectorAcknowledgement
import io.github.kzagoris.proxenos.core.RuntimeConfig
import io.github.kzagoris.proxenos.core.RuntimeFeed
import io.github.kzagoris.proxenos.core.RuntimeManagement
import io.github.kzagoris.proxenos.core.Tunnel
import io.github.kzagoris.proxenos.core.TunnelCredentials
import io.github.kzagoris.proxenos.core.WorkspaceOperationsPipeline
import io.github.kzagoris.proxenos.core.WorkspaceRegistry
import io.github.kzagoris.proxenos.coreapi.*
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.io.TempDir
import kotlin.test.*
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * The home screen attached as it is in use: over the control socket, to a real core. Keys go
 * through [Home.press] and the acts they ask for through the management client, exactly as the
 * Mosaic surface carries them out.
 */
class AttachedTest {
  @TempDir
  lateinit var temporary: Path

  private lateinit var pipeline: WorkspaceOperationsPipeline
  private lateinit var server: ControlServer
  private lateinit var socket: Path
  private val scope = CoroutineScope(Dispatchers.IO)

  @BeforeTest
  fun `a Runtime on the control socket`() {
    val config = RuntimeConfig(
      stateDirectory = Files.createDirectory(temporary.resolve("state")),
      mcpSocket = temporary.resolve("mcp.sock"),
      controlSocket = temporary.resolve("control.sock"),
      tunnelExecutable = temporary.resolve("tunnel-client"),
      killGrace = 200.milliseconds,
      commandBudget = 300.milliseconds,
    )
    val feed = RuntimeFeed()
    val registry = WorkspaceRegistry(config.registryFile, feed)
    val activity = Activity(config.activityFile, config.activityRetention, feed)
    pipeline = WorkspaceOperationsPipeline(registry, activity, config)
    val tunnel = Tunnel(config, "http://runtime.invalid/mcp", TunnelCredentials("tunnel", "key")) {}
    // Exiting is what closes the socket under every attached frontend; here that is the server
    // stopping, on its own thread as the real exit is, since this act arrived through it.
    val core = RuntimeManagement(
      registry, activity, tunnel, pipeline,
      ConnectorAcknowledgement(config.connectorFile, feed), feed,
    ) { Thread { server.stop() }.start() }
    socket = config.controlSocket
    server = ControlServer(core, socket).apply { start() }
  }

  @AfterTest
  fun `nothing outlives the test`() = runBlocking {
    scope.cancel()
    server.stop()
    pipeline.stop()
  }

  /** One TUI: its screen state, moved on by the stream and by the keys it is given. */
  private inner class Tui {
    val management = ManagementClient(socket)
    val home = MutableStateFlow(Home())

    init {
      scope.launch { management.observe().collect { event -> home.value = home.value.observed(event) } }
    }

    suspend fun press(vararg keys: String) {
      for (key in keys) {
        val step = home.value.press(Key(key))
        home.value = step.home
        when (val command = step.command) {
          is Command.Perform -> {
            management.perform(command.act)
            home.value = home.value.say(command.done)
          }
          is Command.Try -> {
            val (said, tone) = Wording.tried(command.tool, command.workspace, management.perform(ManagementAct.TryOperation(command.op)))
            home.value = home.value.say(said, tone)
          }
          null -> Unit
          else -> fail("unexpected $command")
        }
      }
    }

    /** What the Mosaic surface does once a second while the band has anything in it. */
    suspend fun readOutputs() {
      for (running in home.value.band) home.value = home.value.read(running.entry, management.perform(ManagementAct.ReadOutput(running.entry)))
    }

    suspend fun until(what: String, predicate: (Home) -> Boolean): Home =
      try {
        withTimeout(5.seconds) { home.first(predicate) }
      } catch (timeout: Exception) {
        fail("$what; the screen was:\n${render(home.value, Frame(140, 40)).joinToString("\n") { it.plain }}")
      }
  }

  @Test
  fun `two TUIs attached at once both update, and neither locks the other out`() = runBlocking {
    val first = Tui()
    val second = Tui()
    first.until("the first attaches") { it.snapshot != null }
    second.until("the second attaches") { it.snapshot != null }

    val root = Files.createDirectories(temporary.resolve("notes"))
    first.press("n", *root.toString().map(Char::toString).toTypedArray(), "Enter", "Enter")
    second.until("the second sees the first's registration") { home -> home.workspaces.any { it.workspace.name == "notes" } }

    // The second one acts while the first is still attached, and the first sees it.
    second.press("m", "2")
    first.until("the first sees the level the second set") { home -> home.workspaces.single().workspace.accessLevel == AccessLevel.Write }
    assertTrue(render(first.home.value, Frame(140, 40)).any { "notes · Write" in it.plain })
  }

  @Test
  fun `a refused Operation lands in the feed of every TUI attached, and is not flagged`() = runBlocking {
    val first = Tui()
    val second = Tui()
    first.until("attached") { it.snapshot != null }
    second.until("attached") { it.snapshot != null }

    // A withheld Workspace is named: the refusal is recorded and flagged nowhere.
    first.management.perform(ManagementAct.TryOperation(Operation.ReadFile("hidden", "notes.md")))
    val refused = second.until("the refusal reaches the other TUI") { home -> home.feed.any { it.tool == "read_file" && it.outcome is ActivityOutcome.Failed } }
    assertEquals(0, refused.feed.count { it.needsAttention })
    assertTrue(render(refused.copy(page = Page.Activity), Frame(140, 40)).any { "nothing unresolved" in it.plain })
  }

  @Test
  fun `a Stop from one TUI leaves the other detached, with nothing of the gone Runtime on its screen`() = runBlocking {
    val first = Tui()
    val watching = MutableStateFlow<Home>(Home())
    scope.launch {
      try {
        ManagementClient(socket).observe().collect { watching.value = watching.value.observed(it) }
      } catch (_: Exception) {
        watching.value = watching.value.detached(NOT_RUNNING)
      }
    }
    first.until("attached") { it.snapshot != null }
    withTimeout(5.seconds) { watching.first { it.snapshot != null } }
    first.press("i", "Enter", "X", "y")
    val detached = withTimeout(5.seconds) { watching.first { it.attachment is Attachment.Absent } }
    assertTrue(render(detached, Frame(140, 40)).any { "[S] Start" in it.plain })
  }

  @Test
  fun `a command tried from the pane is watched in the band, stopped with s then y, and leaves the band once reaped`() = runBlocking<Unit> {
    val tui = Tui()
    tui.until("attached") { it.snapshot != null }
    val root = Files.createDirectories(temporary.resolve("scripts"))
    tui.press("n", *root.toString().map(Char::toString).toTypedArray(), "Enter", "Enter")
    tui.until("registered") { home -> home.workspaces.any { it.workspace.name == "scripts" } }
    tui.press("m", "3", "y")
    tui.until("at Command") { home -> home.workspaces.single().workspace.accessLevel == AccessLevel.Command }

    // run_command, from the pane, through the same pipeline ChatGPT's calls take. It ignores TERM,
    // so it is still in the band through the grace and the KILL is what ends it.
    val runCommand = tui.home.value.snapshot!!.catalog.indexOfFirst { it.name == "run_command" }
    tui.press("w", *Array(runCommand) { "ArrowDown" }, "t", *"trap '' TERM; echo warming; echo ready; sleep 30".map(Char::toString).toTypedArray(), "Enter", "Enter")
    assertTrue("Promoted" in tui.home.value.notice!!.text, tui.home.value.notice!!.text)
    tui.press("Escape", "Escape", "a")

    val promoted = tui.until("the band shows it promoted") { home -> home.band.singleOrNull()?.promoted == true }
    tui.readOutputs()
    val row = render(tui.home.value, Frame(160, 40)).map { it.plain }.single { "`trap" in it }
    assertTrue(row.endsWith("│ ready"), "the band row's last line: $row")

    tui.press("s")
    assertTrue("full authority" in render(tui.home.value, Frame(160, 40)).joinToString(" ") { it.plain })
    val stopping = async { tui.press("y") }
    tui.until("the band shows it stopping") { home -> home.band.singleOrNull()?.stopping != null }
    stopping.await()
    val ended = tui.until("the band empties once it is reaped") { it.band.isEmpty() }
    assertFalse(render(ended, Frame(160, 40)).any { it.plain.startsWith("Running") })
    assertIs<ActivityOutcome.Uncertain>(ended.feed.single { it.id == promoted.band.single().entry }.outcome)
  }

  @Test
  fun `a Runtime that refuses to start is reported in its own words`() = runBlocking {
    val stub = temporary.resolve("runtime")
    Files.writeString(stub, "#!/bin/sh\necho 'runtime: will not start. No credentials file at /x/credentials.' >&2\nexit 78\n")
    Files.setPosixFilePermissions(stub, PosixFilePermissions.fromString("rwx------"))
    val launcher = RuntimeLauncher(temporary.resolve("elsewhere/control.sock"), stub)
    assertFalse(launcher.running())
    val refused = assertFailsWith<StartFailed> { launcher.start() }
    assertEquals("will not start. No credentials file at /x/credentials.", refused.message)
  }
}
