package io.github.kzagoris.proxenos.gui

import io.github.kzagoris.proxenos.control.ControlServer
import io.github.kzagoris.proxenos.control.ManagementClient
import io.github.kzagoris.proxenos.core.*
import io.github.kzagoris.proxenos.coreapi.*
import io.github.kzagoris.proxenos.frontend.Attachment
import io.github.kzagoris.proxenos.frontend.RuntimeAttachment
import java.nio.file.Files
import java.nio.file.Path
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.channels.Channels
import java.nio.channels.ServerSocketChannel
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.jupiter.api.io.TempDir
import kotlin.test.*
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class GuiOwnerTest {
  @TempDir lateinit var temporary: Path
  private lateinit var server: ControlServer
  private lateinit var pipeline: WorkspaceOperationsPipeline
  private lateinit var core: RuntimeManagement
  private lateinit var socket: Path
  private val owners = mutableListOf<GuiOwner>()

  @BeforeTest
  fun runtime() {
    socket = temporary.resolve("control.sock")
    val config = RuntimeConfig(
      stateDirectory = Files.createDirectories(temporary.resolve("state")),
      mcpSocket = temporary.resolve("mcp.sock"), controlSocket = socket,
      tunnelExecutable = temporary.resolve("tunnel-client"),
      killGrace = 200.milliseconds, commandBudget = 100.milliseconds,
    )
    val feed = RuntimeFeed()
    val registry = WorkspaceRegistry(config.registryFile, feed)
    val activity = Activity(config.activityFile, config.activityRetention, feed)
    pipeline = WorkspaceOperationsPipeline(registry, activity, config)
    core = RuntimeManagement(registry, activity,
      Tunnel(config, "http://runtime.invalid/mcp", TunnelCredentials("tunnel", "key")) {},
      pipeline, ConnectorAcknowledgement(config.connectorFile, feed), feed,
    ) { Thread { server.stop() }.start() }
    server = ControlServer(core, socket).apply { start() }
  }

  @AfterTest
  fun cleanup() = runBlocking {
    owners.forEach { it.close() }
    server.stop()
    pipeline.stop()
  }

  private fun owner(management: WorkspaceManagement? = null): GuiOwner {
    val attachment = RuntimeAttachment(socket, null)
    return GuiOwner(attachment, management ?: attachment.management).also { owners += it; it.open() }
  }

  private suspend fun GuiOwner.until(predicate: (GuiState) -> Boolean): GuiState =
    withTimeout(5.seconds) { state.first(predicate) }

  @Test
  fun `level changes wait for the stream and cannot send a second act while a reply is pending`() = runBlocking<Unit> {
    val root = Files.createDirectory(temporary.resolve("notes"))
    val workspace = core.perform(ManagementAct.Register(root.toString()))
    val entered = CompletableDeferred<Unit>()
    val reply = CompletableDeferred<Unit>()
    val received = AtomicInteger()
    val delayed = object : WorkspaceManagement by core {
      @Suppress("UNCHECKED_CAST")
      override suspend fun <R> perform(act: ManagementAct<R>): R {
        assertEquals<ManagementAct<*>>(ManagementAct.SetLevel(workspace.id, AccessLevel.Write), act)
        received.incrementAndGet()
        entered.complete(Unit)
        reply.await()
        return workspace.copy(accessLevel = AccessLevel.Write) as R
      }
    }
    val gui = owner(delayed)
    gui.until { it.snapshot?.workspaces?.size == 1 }
    gui.accept(GuiIntent.SelectWorkspace(workspace.id))
    gui.until { it.workspace == workspace.id }
    gui.accept(GuiIntent.SetLevel(AccessLevel.Write))
    withTimeout(5.seconds) { entered.await() }
    repeat(20) { gui.accept(GuiIntent.SetLevel(AccessLevel.Write)) }
    gui.accept(GuiIntent.Show(Destination.Activity))
    gui.until { it.destination == Destination.Activity }
    reply.complete(Unit)
    val replied = gui.until { it.inFlight == null }
    assertEquals(AccessLevel.Read, replied.snapshot!!.workspaces.single().workspace.accessLevel)
    core.perform(ManagementAct.SetLevel(workspace.id, AccessLevel.Write))
    gui.until { it.snapshot?.workspaces?.single()?.workspace?.accessLevel == AccessLevel.Write }
    assertEquals(1, received.get())
  }

  @Test
  fun `Access changes follow the stream and None leaves a Promoted command running`() = runBlocking<Unit> {
    val root = Files.createDirectory(temporary.resolve("scripts"))
    val workspace = core.perform(ManagementAct.Register(root.toString()))
    val gui = owner()
    gui.until { it.snapshot?.workspaces?.size == 1 }
    gui.accept(GuiIntent.SelectWorkspace(workspace.id))
    gui.until { it.workspace == workspace.id }
    gui.accept(GuiIntent.SetLevel(AccessLevel.Command))
    val asking = gui.until { it.commandConfirmation == workspace.id }
    assertEquals(AccessLevel.Read, asking.selectedWorkspace!!.workspace.accessLevel)
    gui.accept(GuiIntent.CancelCommand)
    gui.until { it.commandConfirmation == null }
    assertEquals(AccessLevel.Read, gui.state.value.selectedWorkspace!!.workspace.accessLevel)
    gui.accept(GuiIntent.SetLevel(AccessLevel.Command))
    gui.until { it.commandConfirmation != null }
    gui.accept(GuiIntent.ConfirmCommand)
    gui.until { it.selectedWorkspace?.workspace?.accessLevel == AccessLevel.Command && it.inFlight == null }

    val promoted = core.perform(ManagementAct.TryOperation(
      Operation.RunCommand(workspace.name, "sleep 30", deliveryKey = "gui-access")))
    assertIs<CommandReply.Promoted>(assertIs<Outcome.Ok<CommandReply>>(promoted).value)
    val before = gui.until { it.snapshot?.running?.singleOrNull()?.promoted == true }.snapshot!!
    for (level in listOf(AccessLevel.Read, AccessLevel.Write, AccessLevel.None)) {
      gui.accept(GuiIntent.SetLevel(level))
      val changed = gui.until { it.selectedWorkspace?.workspace?.accessLevel == level && it.inFlight == null }.snapshot!!
      assertEquals(before.running.single().entry, changed.running.single().entry)
      assertEquals(before.runtime, changed.runtime)
      assertEquals(before.start, changed.start)
    }
    val discovery = core.perform(ManagementAct.TryOperation(Operation.ListWorkspaces))
    assertTrue(assertIs<Outcome.Ok<List<WorkspaceListing>>>(discovery).value.isEmpty())
  }

  @Test
  fun `selection keeps the Workspace identity through rename and level changes and remains gone after Forget`() = runBlocking<Unit> {
    val first = core.perform(ManagementAct.Register(Files.createDirectory(temporary.resolve("notes")).toString()))
    core.perform(ManagementAct.Register(Files.createDirectory(temporary.resolve("scripts")).toString()))
    val gui = owner()
    gui.until { it.snapshot?.workspaces?.size == 2 }
    gui.accept(GuiIntent.SelectWorkspace(first.id))
    gui.until { it.workspace == first.id }
    core.perform(ManagementAct.Rename(first.id, "renamed"))
    gui.until { it.selectedWorkspace?.workspace?.name == "renamed" }
    core.perform(ManagementAct.SetLevel(first.id, AccessLevel.Write))
    val changed = gui.until { it.selectedWorkspace?.workspace?.accessLevel == AccessLevel.Write }
    assertEquals(first.id, changed.workspace)
    core.perform(ManagementAct.Forget(first.id))
    val gone = gui.until { it.snapshot?.workspaces?.size == 1 }
    assertEquals(first.id, gone.workspace)
    assertNull(gone.selectedWorkspace)
    assertEquals("scripts", gone.snapshot!!.workspaces.single().workspace.name)
  }

  @Test
  fun `constructing an owner waits for the window to open before attaching`() = runBlocking<Unit> {
    val gui = GuiOwner(RuntimeAttachment(socket, null)).also { owners += it }
    delay(100)
    assertNull(gui.state.value.snapshot, "a renderer that has not opened must not start or attach to the Runtime")
    gui.open()
    assertNotNull(gui.until { it.snapshot != null }.snapshot)
  }

  @Test
  fun `an external Stop clears the window and only explicit Start attaches again`() = runBlocking<Unit> {
    val gui = owner()
    val first = gui.until { it.snapshot != null }
    val root = Files.createDirectory(temporary.resolve("notes"))
    core.perform(ManagementAct.Register(root.toString()))
    gui.until { it.snapshot?.workspaces?.size == 1 }
    RuntimeAttachment(socket, null).management.perform(ManagementAct.Stop)
    val absent = gui.until { it.attachment is Attachment.Absent }
    assertEquals("Not running", absent.runtimeWords)
    assertNull(absent.snapshot)
    runtime()
    delay(100)
    assertIs<Attachment.Absent>(gui.state.value.attachment)
    gui.accept(GuiIntent.StartRuntime)
    val next = gui.until { it.snapshot != null }
    assertNotEquals(first.snapshot!!.start!!.id, next.snapshot!!.start!!.id)
    assertTrue(next.snapshot!!.activity.isEmpty())
  }

  @Test
  fun `Disconnect and Connect change only the tunnel while a command keeps running`() = runBlocking<Unit> {
    val gui = owner()
    gui.until { it.snapshot != null }
    val root = Files.createDirectory(temporary.resolve("scripts"))
    val workspace = core.perform(ManagementAct.Register(root.toString()))
    core.perform(ManagementAct.SetLevel(workspace.id, AccessLevel.Command))
    core.perform(ManagementAct.TryOperation(Operation.RunCommand(workspace.name, "sleep 30", deliveryKey = "gui-disconnect")))
    val before = gui.until { it.snapshot?.running?.isNotEmpty() == true }.snapshot!!

    gui.accept(GuiIntent.DisconnectTunnel)
    val disconnected = gui.until { it.snapshot?.runtime?.state == RuntimeState.Disconnected && it.inFlight == null }.snapshot!!
    assertEquals(before.workspaces, disconnected.workspaces)
    assertEquals(before.running.single().entry, disconnected.running.single().entry)
    assertEquals(before.start, disconnected.start)

    gui.accept(GuiIntent.ConnectTunnel)
    val connecting = gui.until { it.snapshot?.runtime?.state == RuntimeState.Connecting && it.inFlight == null }.snapshot!!
    assertEquals(before.workspaces, connecting.workspaces)
    assertEquals(before.running.single().entry, connecting.running.single().entry)
    assertEquals(before.start, connecting.start)
  }

  @Test
  fun `connector acknowledgement records the user's word and survives another window`() = runBlocking<Unit> {
    val gui = owner()
    val before = gui.until { it.snapshot != null }.snapshot!!
    assertTrue(before.connectorUnconfirmed)
    gui.accept(GuiIntent.AcknowledgeConnector)
    val confirmed = gui.until { it.snapshot?.connectorUnconfirmed == false && it.notice != null }
    assertContains(confirmed.notice!!, "your word")
    assertContains(confirmed.notice, "not a measurement")
    assertEquals(before.runtime, confirmed.snapshot!!.runtime)
    assertEquals(before.activity, confirmed.snapshot!!.activity)
    gui.close()
    assertFalse(owner().until { it.snapshot != null }.snapshot!!.connectorUnconfirmed)
  }

  @Test
  fun `a connection act is sent once and its reply cannot replace the stream`() = runBlocking<Unit> {
    val entered = CompletableDeferred<Unit>()
    val reply = CompletableDeferred<Unit>()
    val received = AtomicInteger()
    val delayed = object : WorkspaceManagement by core {
      @Suppress("UNCHECKED_CAST")
      override suspend fun <R> perform(act: ManagementAct<R>): R {
        require(act == ManagementAct.AcknowledgeConnector)
        received.incrementAndGet()
        entered.complete(Unit)
        reply.await()
        return Unit as R
      }
    }
    val gui = owner(delayed)
    gui.until { it.snapshot != null }
    gui.accept(GuiIntent.AcknowledgeConnector)
    withTimeout(5.seconds) { entered.await() }
    repeat(20) {
      gui.accept(GuiIntent.AcknowledgeConnector)
      gui.accept(GuiIntent.DisconnectTunnel)
    }
    gui.accept(GuiIntent.Show(Destination.Activity))
    gui.until { it.destination == Destination.Activity }
    assertEquals(1, received.get())
    reply.complete(Unit)
    val replied = gui.until { it.inFlight == null && it.notice != null }
    assertTrue(replied.snapshot!!.connectorUnconfirmed)
    core.perform(ManagementAct.AcknowledgeConnector)
    gui.until { it.snapshot?.connectorUnconfirmed == false }
    assertEquals(1, received.get())
  }

  @Test
  fun `local Stop is confirmed and leaves a running command Uncertain`() = runBlocking<Unit> {
    val gui = owner()
    gui.until { it.snapshot != null }
    val root = Files.createDirectory(temporary.resolve("scripts"))
    val workspace = core.perform(ManagementAct.Register(root.toString()))
    core.perform(ManagementAct.SetLevel(workspace.id, AccessLevel.Command))
    core.perform(ManagementAct.TryOperation(Operation.RunCommand(workspace.name, "sleep 30", deliveryKey = "gui-stop")))
    val running = gui.until { it.snapshot?.running?.isNotEmpty() == true }.snapshot!!.running.single()
    gui.accept(GuiIntent.AskStop)
    gui.until { it.stopConfirmation }
    assertNotNull(gui.state.value.snapshot)
    gui.accept(GuiIntent.CancelStop)
    gui.until { !it.stopConfirmation }
    assertNotNull(gui.state.value.snapshot)
    gui.accept(GuiIntent.AskStop)
    gui.until { it.stopConfirmation }
    gui.accept(GuiIntent.ConfirmStop)
    val stopped = gui.until { it.attachment is Attachment.Absent && it.inFlight == null && it.stopped }
    assertEquals("Stopped", stopped.runtimeWords)
    val settled = core.observe().first() as RuntimeEvent.Snapshot
    assertIs<ActivityOutcome.Uncertain>(settled.activity.single { it.id == running.entry }.outcome)
  }

  @Test
  fun `Stop dial failure says nothing was done and does not mark the Runtime stopped`() = runBlocking<Unit> {
    val gui = owner(ManagementClient(temporary.resolve("absent.sock")))
    gui.until { it.snapshot != null }
    gui.accept(GuiIntent.AskStop)
    gui.until { it.stopConfirmation }
    gui.accept(GuiIntent.ConfirmStop)
    val failed = gui.until { it.notice != null }
    assertEquals("The Runtime isn't answering; nothing was done.", failed.notice)
    assertFalse(failed.stopped)
    assertNotNull(failed.snapshot)
  }

  @Test
  fun `a cut Stop reply is sent once and is never replayed on a new window`() = runBlocking<Unit> {
    val muteSocket = temporary.resolve("mute.sock")
    val received = AtomicInteger()
    val mute = ServerSocketChannel.open(StandardProtocolFamily.UNIX).apply { bind(UnixDomainSocketAddress.of(muteSocket)) }
    val serving = Thread {
      while (true) {
        val connection = runCatching { mute.accept() }.getOrNull() ?: break
        connection.use {
          val request = Channels.newInputStream(it).bufferedReader().readLine()
          if (request != null) received.incrementAndGet()
          Channels.newOutputStream(it).write("{\"@type\":\"Done\",\"res".toByteArray())
        }
      }
    }.apply { start() }
    try {
      val gui = owner(ManagementClient(muteSocket))
      gui.until { it.snapshot != null }
      gui.accept(GuiIntent.AskStop)
      gui.until { it.stopConfirmation }
      repeat(20) { gui.accept(GuiIntent.ConfirmStop) }
      val failed = gui.until { it.notice != null }
      assertEquals("No reply; nothing was resent; what the Runtime shows now is what happened.", failed.notice)
      assertFalse(failed.stopped)
      gui.close()
      owner(ManagementClient(muteSocket)).until { it.snapshot != null }
      delay(100)
      assertEquals(1, received.get())
    } finally {
      mute.close()
      serving.join(1000)
    }
  }

  @Test
  fun `one suspended act leaves intake running and closure cancels only this window`() = runBlocking<Unit> {
    val entered = CompletableDeferred<Unit>()
    val cancelled = CompletableDeferred<Unit>()
    val received = AtomicInteger()
    val delayed = object : WorkspaceManagement by core {
      override suspend fun <R> perform(act: ManagementAct<R>): R {
        received.incrementAndGet()
        entered.complete(Unit)
        try { awaitCancellation() } finally { cancelled.complete(Unit) }
      }
    }
    val gui = owner(delayed)
    gui.until { it.snapshot != null }
    gui.accept(GuiIntent.AskStop)
    gui.until { it.stopConfirmation }
    repeat(20) { gui.accept(GuiIntent.ConfirmStop) }
    withTimeout(5.seconds) { entered.await() }
    assertEquals(ManagementAct.Stop, gui.state.value.inFlight)
    val root = Files.createDirectory(temporary.resolve("notes"))
    core.perform(ManagementAct.Register(root.toString()))
    gui.until { it.snapshot?.workspaces?.size == 1 }
    gui.close()
    withTimeout(5.seconds) { cancelled.await() }
    assertEquals(1, received.get())
    val other = owner()
    assertEquals("notes", other.until { it.snapshot != null }.snapshot!!.workspaces.single().workspace.name)
  }
}
