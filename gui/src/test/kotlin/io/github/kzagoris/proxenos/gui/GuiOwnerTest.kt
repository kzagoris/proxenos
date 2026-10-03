package io.github.kzagoris.proxenos.gui

import io.github.kzagoris.proxenos.control.ControlServer
import io.github.kzagoris.proxenos.control.ManagementClient
import io.github.kzagoris.proxenos.core.*
import io.github.kzagoris.proxenos.coreapi.*
import io.github.kzagoris.proxenos.frontend.Attachment
import io.github.kzagoris.proxenos.frontend.RuntimeAttachment
import io.github.kzagoris.proxenos.frontend.Wording
import io.github.kzagoris.proxenos.frontend.feed
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
  fun `Register from the GUI appears through the stream and keeps its generated identity`() = runBlocking<Unit> {
    val root = Files.createDirectory(temporary.resolve("notes"))
    val gui = owner()
    gui.until { it.snapshot != null }
    gui.accept(GuiIntent.AddWorkspace)
    gui.until { it.adding }
    gui.accept(GuiIntent.Register(root.toString(), "Notes"))
    val registered = gui.until { it.snapshot?.workspaces?.singleOrNull()?.workspace?.name == "Notes" && it.inFlight == null }
    val workspace = registered.snapshot!!.workspaces.single().workspace
    assertEquals(root.toString(), workspace.root)
    assertEquals(AccessLevel.Read, workspace.accessLevel)
    assertNull(registered.notice)
    assertFalse(registered.adding)
    val observed = RuntimeAttachment(socket, null).attach(startIfAbsent = false)
      .first { it is Attachment.Attached } as Attachment.Attached
    assertEquals(workspace, observed.snapshot.workspaces.single().workspace)
  }

  @Test
  fun `a hidden window consumes the named burst before it is shown again`() = runBlocking<Unit> {
    val gui = owner()
    gui.until { it.snapshot != null }
    // No StateFlow collector while the window is hidden. Eight independent clients produce
    // 1,000 read-only Try acts, as in GUI-SPEC §14.4; each opens and completes an Activity entry.
    val clients = List(8) { ManagementClient(socket) }
    val halfway = CompletableDeferred<Unit>()
    val resume = CompletableDeferred<Unit>()
    val reached = AtomicInteger()
    withTimeout(90.seconds) {
      val workers = clients.map { client ->
        async(Dispatchers.IO) {
          repeat(50) {
            assertIs<Outcome.Ok<List<WorkspaceListing>>>(client.perform(ManagementAct.TryOperation(Operation.ListWorkspaces)))
          }
          if (reached.incrementAndGet() == clients.size) halfway.complete(Unit)
          resume.await()
          repeat(75) {
            assertIs<Outcome.Ok<List<WorkspaceListing>>>(client.perform(ManagementAct.TryOperation(Operation.ListWorkspaces)))
          }
        }
      }
      halfway.await()
      while (gui.state.value.snapshot?.activity?.size != 400) delay(10)
      resume.complete(Unit)
      workers.awaitAll()
      while (gui.state.value.snapshot?.activity?.size != 1_000) delay(10)
    }

    val current = gui.state.value.snapshot!!
    val fresh = RuntimeAttachment(socket, null).attach(startIfAbsent = false)
      .first { it is Attachment.Attached } as Attachment.Attached
    assertEquals(fresh.snapshot.activity.map { it.id }, current.activity.map { it.id })
    assertEquals(1_000, current.feed.size)
    assertEquals(1_000, gui.state.first().feedRows.size)
  }


  @Test
  fun `Rename through the owner preserves the selected identity`() = runBlocking<Unit> {
    val workspace = core.perform(ManagementAct.Register(Files.createDirectory(temporary.resolve("notes")).toString()))
    val gui = owner()
    gui.until { it.snapshot?.workspaces?.size == 1 }
    gui.accept(GuiIntent.SelectWorkspace(workspace.id))
    gui.until { it.workspace == workspace.id }
    gui.accept(GuiIntent.AskRename)
    gui.until { it.registration == Registration.Rename }
    gui.accept(GuiIntent.Rename("renamed"))
    val renamed = gui.until { it.selectedWorkspace?.workspace?.name == "renamed" && it.inFlight == null }
    assertEquals(workspace.id, renamed.workspace)
    assertNull(renamed.registration)
  }

  @Test
  fun `moving a Write Workspace through the owner preserves its id and lands at Read`() = runBlocking<Unit> {
    val workspace = core.perform(ManagementAct.Register(Files.createDirectory(temporary.resolve("notes")).toString()))
    val next = Files.createDirectory(temporary.resolve("moved"))
    core.perform(ManagementAct.SetLevel(workspace.id, AccessLevel.Write))
    val gui = owner()
    gui.until { it.snapshot?.workspaces?.size == 1 }
    gui.accept(GuiIntent.SelectWorkspace(workspace.id))
    gui.until { it.workspace == workspace.id }
    gui.accept(GuiIntent.AskMove)
    gui.until { it.registration == Registration.Move }
    gui.accept(GuiIntent.Move(next.toString()))
    val moved = gui.until { it.selectedWorkspace?.workspace?.root == next.toString() && it.inFlight == null }
    assertEquals(workspace.id, moved.workspace)
    assertEquals(AccessLevel.Read, moved.selectedWorkspace!!.workspace.accessLevel)
    assertNull(moved.registration)
  }


  @Test
  fun `re-confirming a Broken Workspace through the owner binds the replacement at Read`() = runBlocking<Unit> {
    val root = Files.createDirectory(temporary.resolve("notes"))
    val workspace = core.perform(ManagementAct.Register(root.toString()))
    core.perform(ManagementAct.SetLevel(workspace.id, AccessLevel.Write))
    val gui = owner()
    gui.until { it.snapshot?.workspaces?.size == 1 }
    gui.accept(GuiIntent.SelectWorkspace(workspace.id))
    gui.until { it.workspace == workspace.id }
    Files.move(root, temporary.resolve("old"))
    Files.createDirectory(root)
    core.perform(ManagementAct.TryOperation(Operation.ListWorkspaces))
    gui.until { it.selectedWorkspace?.broken == true }
    gui.accept(GuiIntent.AskReconfirm)
    gui.until { it.registration == Registration.Reconfirm }
    gui.accept(GuiIntent.ConfirmReconfirm)
    val rebound = gui.until { it.selectedWorkspace?.broken == false && it.inFlight == null }
    assertEquals(workspace.id, rebound.workspace)
    assertEquals(root.toString(), rebound.selectedWorkspace!!.workspace.root)
    assertEquals(AccessLevel.Read, rebound.selectedWorkspace!!.workspace.accessLevel)
    assertNull(rebound.registration)
  }


  @Test
  fun `Forget through the owner keeps Activity and a Promoted command running with selection gone`() = runBlocking<Unit> {
    val workspace = core.perform(ManagementAct.Register(Files.createDirectory(temporary.resolve("scripts")).toString()))
    core.perform(ManagementAct.SetLevel(workspace.id, AccessLevel.Command))
    core.perform(ManagementAct.TryOperation(Operation.RunCommand(workspace.name, "sleep 30", deliveryKey = "gui-forget")))
    val gui = owner()
    val before = gui.until { it.snapshot?.running?.singleOrNull()?.promoted == true }.snapshot!!
    gui.accept(GuiIntent.SelectWorkspace(workspace.id))
    gui.until { it.workspace == workspace.id }
    gui.accept(GuiIntent.AskForget)
    gui.until { it.registration == Registration.Forget }
    gui.accept(GuiIntent.ConfirmForget)
    val gone = gui.until { it.snapshot?.workspaces?.isEmpty() == true && it.inFlight == null }
    assertEquals(workspace.id, gone.workspace)
    assertNull(gone.selectedWorkspace)
    assertEquals(before.running, gone.snapshot!!.running)
    assertEquals(before.activity, gone.snapshot!!.activity)
    assertNull(gone.registration)
  }

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
  fun `Try at None is answered as no such Workspace, answers at Read in the pipeline's words, and keys each write afresh`() = runBlocking<Unit> {
    val root = Files.createDirectory(temporary.resolve("notes"))
    Files.writeString(root.resolve("hello.txt"), "hello\n")
    val workspace = core.perform(ManagementAct.Register(root.toString()))
    core.perform(ManagementAct.SetLevel(workspace.id, AccessLevel.None))
    val gui = owner()
    gui.until { it.snapshot?.workspaces?.size == 1 }
    gui.accept(GuiIntent.SelectWorkspace(workspace.id))
    gui.until { it.workspace == workspace.id }

    suspend fun tried(tool: String, given: Map<String, String>): Tried {
      gui.accept(GuiIntent.AskTry(tool))
      gui.until { it.trying?.tool == tool && it.trying.result == null }
      gui.accept(GuiIntent.Try(given))
      return gui.until { it.trying?.result != null && it.inFlight == null }.trying!!.result!!
    }
    suspend fun shown(): ActivityEntry {
      gui.until { it.triedEntry != null }
      gui.accept(GuiIntent.ShowTried)
      val id = gui.until { it.destination == Destination.Activity && it.trying == null }.activity
      val entry = gui.until { state -> state.snapshot!!.activity.single { it.id == id }.outcome !is ActivityOutcome.InFlight }
        .snapshot!!.activity.single { it.id == id }
      gui.accept(GuiIntent.Show(Destination.Workspaces))
      gui.until { it.destination == Destination.Workspaces }
      return entry
    }

    val withheld = tried("read_file", mapOf("path" to "hello.txt"))
    assertTrue(withheld.words.startsWith("Tried read_file against 'notes': failed. No such Workspace."), withheld.words)
    assertEquals(Tone.Warn, withheld.tone)
    gui.accept(GuiIntent.CancelTry)
    gui.until { it.trying == null }

    core.perform(ManagementAct.SetLevel(workspace.id, AccessLevel.Read))
    gui.until { it.selectedWorkspace?.workspace?.accessLevel == AccessLevel.Read }
    val read = tried("read_file", mapOf("path" to "hello.txt"))
    assertEquals("Tried read_file against 'notes': ok. What it returned is in Activity.", read.words)
    val readEntry = shown()
    assertEquals(Origin.Frontend, readEntry.origin)
    assertEquals("read_file", readEntry.tool)
    assertIs<ActivityOutcome.Ok>(readEntry.outcome)

    core.perform(ManagementAct.SetLevel(workspace.id, AccessLevel.Write))
    gui.until { it.selectedWorkspace?.workspace?.accessLevel == AccessLevel.Write }
    val writes = List(2) {
      val write = tried("write_file", mapOf("path" to "hello.txt", "content" to "again\n"))
      assertEquals(Tone.Ok, write.tone, write.words)
      shown()
    }
    val keys = writes.map { it.arguments.substringAfter("request_id=") }
    assertTrue(keys.all { it.startsWith("gui-") }, keys.toString())
    assertEquals(2, keys.toSet().size, "each try is a new execution with its own request_id")
    assertTrue(writes.none { it.deliveries > 0 })
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

  private fun script(name: String, text: String): Path =
    Files.writeString(temporary.resolve(name), text).also { it.toFile().setExecutable(true) }

  private suspend fun commandWorkspace(): Workspace {
    val workspace = core.perform(ManagementAct.Register(Files.createDirectory(temporary.resolve("scripts")).toString()))
    return core.perform(ManagementAct.SetLevel(workspace.id, AccessLevel.Command))
  }

  /** Counts ReadOutput; [nullReads] answers each as a command that is no longer running would be. */
  private class Reads(private val core: WorkspaceManagement, private val nullReads: Boolean) : WorkspaceManagement by core {
    val count = AtomicInteger()
    @Suppress("UNCHECKED_CAST")
    override suspend fun <R> perform(act: ManagementAct<R>): R =
      if (act is ManagementAct.ReadOutput) {
        count.incrementAndGet()
        if (nullReads) null as R else core.perform(act)
      } else core.perform(act)
  }

  @Test
  fun `Stop command shows TERM then SIGKILL, stays until reaped, and its entry names the survivor`() = runBlocking<Unit> {
    // G13. The command traps TERM and starts a setsid helper whose own TERM handler forks a
    // process after the tree was snapshotted, outside the group: no arm of the kill reaches it.
    val late = script("late.sh", "printf 'late %s\\n' \"${'$'}${'$'}\"\nexec sleep 30\n")
    val helper = script("helper.sh", "trap \"'$late' &\" TERM\nsleep 30 &\necho ready\nwhile true; do wait; done\n")
    val command = script("forks-late.sh", "trap : TERM\nsetsid '$helper' &\nwhile true; do wait; done\n")
    val workspace = commandWorkspace()
    val gui = owner()
    gui.until { it.snapshot != null }
    gui.accept(GuiIntent.Show(Destination.Activity))
    core.perform(ManagementAct.TryOperation(Operation.RunCommand(workspace.name, "exec '$command'", deliveryKey = "gui-g13")))
    val entry = gui.until { it.running.singleOrNull()?.promoted == true }.running.single().entry
    // Polled while Activity shows: the helper has its trap in place once it has said so.
    gui.until { it.outputs[entry]?.outputSoFar?.contains("ready") == true }
    gui.accept(GuiIntent.ShowActivity(entry))
    gui.until { it.activity == entry }
    gui.accept(GuiIntent.AskStopCommand)
    gui.until { it.stopCommandConfirmation == entry }
    gui.accept(GuiIntent.ConfirmStopCommand)

    val terminating = gui.until { it.selectedRunning?.stopping == StopPhase.Terminating }
    assertNull(terminating.stopCommandConfirmation)
    assertNull(terminating.after(GuiIntent.AskStopCommand).stopCommandConfirmation, "there is no second, harder stop")
    gui.until { it.selectedRunning?.stopping == StopPhase.Killing }
    val reaped = gui.until { it.running.isEmpty() && it.inFlight == null && it.notice != null }
    var survivor: Long? = null
    try {
      assertEquals(Wording.stopped("exec '$command'"), reaped.notice)
      assertEquals(entry, reaped.noticeEntry)
      // The running item turned into its entry, under the same selection.
      assertNull(reaped.selectedRunning)
      val ended = assertNotNull(reaped.selectedEntry)
      assertEquals(entry, ended.id)
      val detail = assertIs<ActivityOutcome.Uncertain>(ended.outcome).detail
      survivor = Regex("pid (\\d+) \\(never signalled").find(detail)?.groupValues?.get(1)?.toLong()
      assertNotNull(survivor, detail)
      assertTrue(ProcessHandle.of(survivor).isPresent, "the survivor named is still running")
      assertTrue(reaped.polled.isEmpty())
    } finally {
      survivor?.let { pid -> ProcessHandle.of(pid).ifPresent { it.destroyForcibly() } }
    }
  }

  @Test
  fun `output is read only while Activity shows, and never again after a null read`() = runBlocking<Unit> {
    val workspace = commandWorkspace()
    val reads = Reads(core, nullReads = true)
    val gui = owner(reads)
    gui.until { it.snapshot != null }
    core.perform(ManagementAct.TryOperation(Operation.RunCommand(workspace.name, "sleep 30", deliveryKey = "gui-null")))
    gui.until { it.running.isNotEmpty() }
    delay(1500)
    assertEquals(0, reads.count.get(), "nothing is read while Workspaces shows")
    gui.accept(GuiIntent.Show(Destination.Activity))
    gui.until { it.polled.isEmpty() && it.destination == Destination.Activity }
    delay(2500)
    assertEquals(1, reads.count.get())
    assertTrue(gui.state.value.running.isNotEmpty(), "the stream still lists it; the null read alone stopped the polling")
  }

  @Test
  fun `output polling stops when the command ends, which leaves its entry`() = runBlocking<Unit> {
    val workspace = commandWorkspace()
    val reads = Reads(core, nullReads = false)
    val gui = owner(reads)
    gui.until { it.snapshot != null }
    gui.accept(GuiIntent.Show(Destination.Activity))
    core.perform(ManagementAct.TryOperation(Operation.RunCommand(workspace.name, "echo started; sleep 2", deliveryKey = "gui-end")))
    val entry = gui.until { it.running.isNotEmpty() }.running.single().entry
    gui.accept(GuiIntent.ShowActivity(entry))
    assertEquals("started\n", gui.until { it.outputs[entry] != null }.outputs[entry]!!.outputSoFar)
    val ended = gui.until { it.running.isEmpty() }
    assertEquals(entry, ended.selectedEntry?.id)
    assertNotEquals(ActivityOutcome.InFlight, ended.selectedEntry?.outcome)
    val after = reads.count.get()
    delay(2500)
    assertEquals(after, reads.count.get())
  }

  @Test
  fun `Acknowledge settles an Uncertain entry through the stream`() = runBlocking<Unit> {
    val workspace = commandWorkspace()
    core.perform(ManagementAct.TryOperation(Operation.RunCommand(workspace.name, "sleep 30", deliveryKey = "gui-ack")))
    val entry = core.observe().first().let { (it as RuntimeEvent.Snapshot).running.single().entry }
    core.perform(ManagementAct.StopOperation(entry))
    val gui = owner()
    gui.until { state -> state.snapshot?.feed?.any { it.id == entry && it.needsAttention } == true }
    gui.accept(GuiIntent.ShowActivity(entry))
    val selected = gui.until { it.canAcknowledge }
    assertIs<ActivityOutcome.Uncertain>(selected.selectedEntry!!.outcome)
    gui.accept(GuiIntent.Acknowledge)
    val settled = gui.until { it.selectedEntry?.acknowledgedAt != null && it.inFlight == null }
    assertFalse(settled.selectedEntry!!.needsAttention)
    assertFalse(settled.canAcknowledge)
  }

  @Test
  fun `after a Runtime restart Activity shows this start only, newest first, with a get_result burst folded into one row`() = runBlocking<Unit> {
    // G15 through the owner and a real restart: the earlier start's entries stay in the account and out of the feed.
    val gui = owner()
    gui.until { it.snapshot != null }
    gui.accept(GuiIntent.Show(Destination.Activity))
    core.perform(ManagementAct.TryOperation(Operation.ListWorkspaces))
    gui.until { it.feedRows.size == 1 }
    RuntimeAttachment(socket, null).management.perform(ManagementAct.Stop)
    gui.until { it.attachment is Attachment.Absent }
    runtime()
    gui.accept(GuiIntent.StartRuntime)
    val restarted = gui.until { it.snapshot != null }
    assertEquals(1, restarted.snapshot!!.activity.size)
    assertTrue(restarted.feedRows.isEmpty())
    assertEquals(Destination.Activity, restarted.destination)

    val workspace = commandWorkspace()
    val promoted = core.perform(ManagementAct.TryOperation(Operation.RunCommand(workspace.name, "sleep 30", deliveryKey = "gui-burst")))
    val handle = assertIs<CommandReply.Promoted>(assertIs<Outcome.Ok<CommandReply>>(promoted).value).handle.value
    repeat(3) { core.perform(ManagementAct.TryOperation(Operation.GetResult(workspace.name, handle))) }
    val burst = gui.until { it.feedRows.firstOrNull()?.entries?.size == 3 }
    assertEquals(listOf("get_result", "run_command"), burst.feedRows.map { it.entries.last().tool })
    assertTrue(burst.feedRows.flatMap { it.entries }.none { it.outcome == ActivityOutcome.Lost })
  }
}
