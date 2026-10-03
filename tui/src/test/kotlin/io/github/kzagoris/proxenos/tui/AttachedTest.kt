package io.github.kzagoris.proxenos.tui

import io.github.kzagoris.proxenos.control.ControlServer
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
import io.github.kzagoris.proxenos.frontend.Attachment
import io.github.kzagoris.proxenos.frontend.RuntimeAttachment
import io.github.kzagoris.proxenos.gui.GuiIntent
import io.github.kzagoris.proxenos.gui.GuiOwner
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withContext
import org.junit.jupiter.api.io.TempDir
import kotlin.test.*
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * The home screen attached as it is in use: over the control socket, to a real core. Keys go
 * through [Home.press] and the acts they ask for through the management client, and the stream
 * arrives through the attachment, exactly as the Mosaic surface carries them out.
 */
class AttachedTest {
  @TempDir
  lateinit var temporary: Path

  private lateinit var pipeline: WorkspaceOperationsPipeline
  private lateinit var server: ControlServer
  private lateinit var socket: Path
  // As in Mosaic, screen updates have one owner even while socket calls suspend.
  private val scope = CoroutineScope(Dispatchers.IO.limitedParallelism(1))
  private val guis = mutableListOf<GuiOwner>()

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
    guis.forEach { it.close() }
    scope.cancel()
    server.stop()
    pipeline.stop()
  }

  private fun gui(managementOverride: WorkspaceManagement? = null): GuiOwner {
    val attachment = RuntimeAttachment(socket, null)
    return GuiOwner(attachment, managementOverride ?: attachment.management).also { guis += it; it.open() }
  }

  /** One TUI: its screen state, moved on by the attachment and by the keys it is given. */
  private inner class Tui(managementOverride: WorkspaceManagement? = null) {
    private val attachment = RuntimeAttachment(socket, executable = null)
    val management = managementOverride ?: attachment.management
    val home = MutableStateFlow(Home())
    private var attempt = 0

    init {
      scope.launch { attachment.attach(startIfAbsent = false).collect { home.value = home.value.observed(it) } }
    }

    suspend fun press(vararg keys: String) = withContext(scope.coroutineContext) {
      for (key in keys) {
        val step = home.value.press(Key(key))
        home.value = step.home
        step.command?.let { command ->
          carryOut(command, management, { home.value }, { home.value = it }, { attempt++ }, { attempt })
        }
      }
    }

    /** What the Mosaic surface does once a second while the band has anything in it. */
    suspend fun readOutputs() = withContext(scope.coroutineContext) {
      for (running in home.value.band) {
        val output = management.perform(ManagementAct.ReadOutput(running.entry))
        home.value = home.value.read(running.entry, output)
      }
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
  fun `a TUI level change and command reach two GUIs and a management client forgets their selection`() = runBlocking {
    val tui = Tui()
    val first = gui()
    val second = gui()
    val management = RuntimeAttachment(socket, null).management
    tui.until("TUI attaches") { it.snapshot != null }
    withTimeout(5.seconds) { first.state.first { it.snapshot != null } }
    withTimeout(5.seconds) { second.state.first { it.snapshot != null } }

    val root = Files.createDirectory(temporary.resolve("scripts"))
    val workspace = management.perform(ManagementAct.Register(root.toString()))
    Files.createDirectory(temporary.resolve("other")).let { management.perform(ManagementAct.Register(it.toString())) }
    tui.until("both Workspaces appear") { it.workspaces.size == 2 }
    for (gui in listOf(first, second)) {
      withTimeout(5.seconds) { gui.state.first { it.snapshot?.workspaces?.size == 2 } }
      gui.accept(GuiIntent.SelectWorkspace(workspace.id))
      withTimeout(5.seconds) { gui.state.first { it.workspace == workspace.id } }
    }

    tui.press("m", "3", "y")
    tui.until("TUI sees Command") { it.workspaces.first().workspace.accessLevel == AccessLevel.Command }
    for (gui in listOf(first, second)) {
      val changed = withTimeout(5.seconds) { gui.state.first { it.selectedWorkspace?.workspace?.accessLevel == AccessLevel.Command } }
      assertEquals(workspace.id, changed.workspace)
    }

    val runCommand = tui.home.value.snapshot!!.catalog.indexOfFirst { it.name == "run_command" }
    tui.press("w", *Array(runCommand) { "ArrowDown" }, "t", *"sleep 30".map(Char::toString).toTypedArray(), "Enter", "Enter")
    val running = tui.until("the TUI sees its command") { it.band.singleOrNull()?.promoted == true }.band.single()
    for (gui in listOf(first, second)) {
      val followed = withTimeout(5.seconds) { gui.state.first { it.running.singleOrNull()?.entry == running.entry } }
      assertEquals(workspace.id, followed.workspace)
    }

    // Forget is available on the management seam but not as a TUI key (GUI-SPEC §1).
    management.perform(ManagementAct.Forget(workspace.id))
    tui.until("TUI sees the remaining Workspace") { it.workspaces.size == 1 }
    for (gui in listOf(first, second)) {
      val gone = withTimeout(5.seconds) { gui.state.first { it.snapshot?.workspaces?.size == 1 } }
      assertEquals(workspace.id, gone.workspace)
      assertNull(gone.selectedWorkspace)
      assertEquals("other", gone.snapshot!!.workspaces.single().workspace.name)
      assertEquals(running.entry, gone.running.single().entry)
    }
  }

  @Test
  fun `GUI and TUI level changes both succeed and both show the last applied level`() = runBlocking {
    val client = RuntimeAttachment(socket, null).management
    val applied = CompletableDeferred<Unit>()
    val release = CompletableDeferred<Unit>()
    val delayed = object : WorkspaceManagement by client {
      override suspend fun <R> perform(act: ManagementAct<R>): R {
        val result = client.perform(act)
        if (act is ManagementAct.SetLevel && act.level == AccessLevel.Write) {
          applied.complete(Unit)
          release.await()
        }
        return result
      }
    }
    val tui = Tui()
    val gui = gui(delayed)
    tui.until("TUI attaches") { it.snapshot != null }
    withTimeout(5.seconds) { gui.state.first { it.snapshot != null } }
    val workspace = client.perform(ManagementAct.Register(Files.createDirectory(temporary.resolve("notes")).toString()))
    tui.until("TUI sees the Workspace") { it.workspaces.singleOrNull()?.workspace?.id == workspace.id }
    withTimeout(5.seconds) { gui.state.first { it.snapshot?.workspaces?.singleOrNull()?.workspace?.id == workspace.id } }
    gui.accept(GuiIntent.SelectWorkspace(workspace.id))
    withTimeout(5.seconds) { gui.state.first { it.workspace == workspace.id } }

    try {
      gui.accept(GuiIntent.SetLevel(AccessLevel.Write))
      withTimeout(5.seconds) { applied.await() }
      tui.until("TUI sees Write") { it.workspaces.single().workspace.accessLevel == AccessLevel.Write }
      tui.press("m", "0")
      tui.until("TUI sees None") { it.workspaces.single().workspace.accessLevel == AccessLevel.None }
    } finally {
      release.complete(Unit)
    }

    val settled = withTimeout(5.seconds) {
      gui.state.first { it.inFlight == null && it.selectedWorkspace?.workspace?.accessLevel == AccessLevel.None }
    }
    assertNull(settled.refusal)
    assertNull(settled.notice)
    assertEquals(AccessLevel.None, tui.home.value.workspaces.single().workspace.accessLevel)
    assertTrue(tui.home.value.notice?.text?.contains("now at None") == true)
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
    val watching = Tui()
    first.until("attached") { it.snapshot != null }
    watching.until("attached") { it.snapshot != null }
    first.press("i", "Enter", "X", "y")
    val stopped = first.until("the local Stop confirmation survives the stream ending") { it.stopped && it.attachment is Attachment.Absent }
    assertTrue(render(stopped, Frame(140, 40)).any { "The Runtime stopped." in it.plain })
    val detached = watching.until("the stream's end reaches the other TUI") { it.attachment is Attachment.Absent }
    assertNull(detached.snapshot)
    assertTrue(render(detached, Frame(140, 40)).any { "[S] Start" in it.plain })
  }

  @Test
  fun `a delayed Stop reply cannot detach a newer Start`() = runBlocking<Unit> {
    val client = RuntimeAttachment(socket, executable = null).management
    val returned = CompletableDeferred<Unit>()
    val release = CompletableDeferred<Unit>()
    // The real Runtime stops and the stream ends, while its reply waits at the frontend seam.
    val delayed = object : WorkspaceManagement by client {
      override suspend fun <R> perform(act: ManagementAct<R>): R {
        val result = client.perform(act)
        if (act == ManagementAct.Stop) {
          returned.complete(Unit)
          release.await()
        }
        return result
      }
    }
    val tui = Tui(delayed)
    tui.until("attached") { it.snapshot != null }
    val stopping = async { tui.press("i", "Enter", "X", "y") }
    try {
      withTimeout(5.seconds) { returned.await() }
      tui.until("the stream ends before the Stop reply is handled") { it.attachment is Attachment.Absent }
      tui.press("S")
      release.complete(Unit)
      stopping.await()
      assertEquals(Attachment.Starting, tui.home.value.attachment)
      assertFalse(tui.home.value.stopped)
    } finally {
      release.complete(Unit)
    }
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
}
