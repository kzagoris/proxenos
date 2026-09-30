package io.github.kzagoris.proxenos.control

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
import kotlin.io.path.readText
import kotlin.test.*
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.io.TempDir

/**
 * The named harness for the control protocol: the management client driven against an
 * in-process core over a real Unix socket, so that the client and the core are proven
 * interchangeable — the design's one real seam, and the only place two adapters exist.
 *
 * The tunnel is never started. Connect and Disconnect are the user's intent, which the tunnel
 * holds whether or not a child is running, and a child here would be a second harness's work
 * (`TunnelTest` in core is that harness).
 */
class ManagementClientTest {
  @TempDir
  lateinit var temporary: Path

  private lateinit var config: RuntimeConfig
  private lateinit var activity: Activity
  private lateinit var pipeline: WorkspaceOperationsPipeline
  private lateinit var tunnel: Tunnel
  private lateinit var core: RuntimeManagement
  private lateinit var server: ControlServer
  private lateinit var client: WorkspaceManagement
  private var exited = false

  @BeforeTest
  fun `a core served on the control socket`() {
    config = RuntimeConfig(
      stateDirectory = Files.createDirectory(temporary.resolve("state")),
      mcpSocket = temporary.resolve("mcp.sock"),
      controlSocket = temporary.resolve("control.sock"),
      tunnelExecutable = temporary.resolve("tunnel-client"),
      killGrace = 200.milliseconds,
      commandBudget = 30.seconds,
    )
    val feed = RuntimeFeed()
    val registry = WorkspaceRegistry(config.registryFile, feed)
    activity = Activity(config.activityFile, config.activityRetention, feed)
    pipeline = WorkspaceOperationsPipeline(registry, activity, config)
    tunnel = Tunnel(config, "http://runtime.invalid/mcp", TunnelCredentials("tunnel", "key")) {}
    core = RuntimeManagement(
      registry, activity, tunnel, pipeline,
      ConnectorAcknowledgement(config.connectorFile, feed), feed,
    ) { exited = true }
    server = ControlServer(core, config.controlSocket).apply { start() }
    client = ManagementClient(config.controlSocket)
  }

  @AfterTest
  fun `nothing outlives the test`() = runBlocking {
    server.stop()
    pipeline.stop()
  }

  private fun project(name: String): Path = Files.createDirectories(temporary.resolve(name))

  @Test
  fun `each registry act round-trips and answers what the core answers`() = runBlocking {
    val root = project("api")
    val registered = client.perform(ManagementAct.Register(root.toString(), "api"))
    assertEquals(Workspace(registered.id, "api", root.toString(), AccessLevel.Read), registered)

    val renamed = client.perform(ManagementAct.Rename(registered.id, "backend"))
    assertEquals("backend", renamed.name)
    val raised = client.perform(ManagementAct.SetLevel(registered.id, AccessLevel.Write))
    assertEquals(AccessLevel.Write, raised.accessLevel)
    // Re-confirming is a fresh decision, so it comes back at Read — as the core says, not as the client guesses.
    val moved = project("moved")
    val reconfirmed = client.perform(ManagementAct.Reconfirm(registered.id, moved.toString()))
    assertEquals(Workspace(registered.id, "backend", moved.toString(), AccessLevel.Read), reconfirmed)

    client.perform(ManagementAct.Forget(registered.id))
    assertFalse(config.registryFile.readText().contains(registered.id.value), "Forget left the registration on disk")
    // A refusal crosses the wire as the exception the core throws for it.
    assertFailsWith<IllegalArgumentException> { client.perform(ManagementAct.Rename(registered.id, "gone")) }
    assertFailsWith<IllegalArgumentException> { core.perform(ManagementAct.Rename(registered.id, "gone")) }
  }

  @Test
  fun `Disconnect and Connect round-trip as the user's intent`() = runBlocking {
    client.perform(ManagementAct.Disconnect)
    assertEquals(RuntimeState.Disconnected, tunnel.status().state)
    client.perform(ManagementAct.Connect)
    assertEquals(RuntimeState.Connecting, tunnel.status().state)
  }

  @Test
  fun `acknowledging the connector round-trips, and a frontend attaching after it is not shown the banner`() = runBlocking {
    val before = assertIs<RuntimeEvent.Snapshot>(withTimeout(5.seconds) { client.observe().first() })
    assertTrue(before.connectorUnconfirmed, "a fresh state directory has no acknowledged fingerprint")

    client.perform(ManagementAct.AcknowledgeConnector)

    val after = assertIs<RuntimeEvent.Snapshot>(withTimeout(5.seconds) { client.observe().first() })
    assertFalse(after.connectorUnconfirmed)
  }

  @Test
  fun `a frontend attaching after a level change learns the level from its snapshot`() = runBlocking {
    val workspace = client.perform(ManagementAct.Register(project("api").toString(), "api"))
    client.perform(ManagementAct.SetLevel(workspace.id, AccessLevel.Command))

    val snapshot = assertIs<RuntimeEvent.Snapshot>(withTimeout(5.seconds) { client.observe().first() })
    assertEquals(listOf(WorkspaceState(workspace.copy(accessLevel = AccessLevel.Command), broken = false)), snapshot.workspaces)
    assertEquals(tunnel.status(), snapshot.runtime)
    assertEquals(emptyList(), snapshot.running)
  }

  @Test
  fun `changes landing while frontends attach are not lost, and two frontends both see every one`() = runBlocking {
    val workspace = client.perform(ManagementAct.Register(project("api").toString(), "api"))
    val levels = List(40) { AccessLevel.entries[it % AccessLevel.entries.size] }
    // The acts race the attaching: some land before a snapshot, some during, some after.
    val acting = launch(Dispatchers.IO) {
      levels.forEach { client.perform(ManagementAct.SetLevel(workspace.id, it)) }
      // The last act, so a frontend knows from its own state when it has heard everything.
      client.perform(ManagementAct.Rename(workspace.id, "settled"))
    }
    val frontends = List(2) {
      async(Dispatchers.IO) {
        var state: RuntimeEvent.Snapshot? = null
        withTimeout(10.seconds) {
          client.observe().first { event ->
            state = when (event) {
              is RuntimeEvent.Snapshot -> event
              is RuntimeEvent.Change -> assertNotNull(state, "a change arrived before the snapshot").after(event)
            }
            state!!.workspaces.single().workspace.name == "settled"
          }
        }
        state!!
      }
    }
    acting.join()
    frontends.forEach { assertEquals(levels.last(), it.await().workspaces.single().workspace.accessLevel) }

    // And once attached, each sees every change, in order.
    val attached = List(2) { CompletableDeferred<Unit>() }
    val watched = attached.map { snapshotted ->
      async(Dispatchers.IO) { withTimeout(10.seconds) { client.observe().onEach { snapshotted.complete(Unit) }.take(1 + 3).toList() } }
    }
    // A snapshot is sent only to a subscriber already added, so one that has its snapshot sees the rest.
    attached.awaitAll()
    listOf(AccessLevel.None, AccessLevel.Read, AccessLevel.Write).forEach { client.perform(ManagementAct.SetLevel(workspace.id, it)) }
    watched.forEach { frontend ->
      val changes = frontend.await().drop(1).map { assertIs<RuntimeEvent.Change.WorkspaceChanged>(it).state.workspace.accessLevel }
      assertEquals(listOf(AccessLevel.None, AccessLevel.Read, AccessLevel.Write), changes)
    }
  }

  @Test
  fun `TryOperation at None is refused as ChatGPT's call is, and recorded as the frontend's with its own request_id`() = runBlocking {
    val root = project("api")
    val workspace = client.perform(ManagementAct.Register(root.toString(), "api"))
    client.perform(ManagementAct.SetLevel(workspace.id, AccessLevel.None))
    val write = Operation.WriteFile("api", "notes.txt", "hello", deliveryKey = "frontend-1")

    val tried = client.perform(ManagementAct.TryOperation(write))
    val chatGpt = pipeline.operationsFor(Origin.ChatGpt).perform(write.copy(deliveryKey = "chatgpt-1"))

    assertEquals(chatGpt, tried)
    assertIs<Failure.NoSuchWorkspace>(assertIs<Outcome.Failed>(tried).reason)
    assertFalse(Files.exists(root.resolve("notes.txt")))
    val entries = activity.entries().filter { it.tool == "write_file" }
    assertEquals(listOf(Origin.Frontend, Origin.ChatGpt), entries.map { it.origin })
    assertContains(entries.first().arguments, "frontend-1")
  }

  @Test
  fun `TryOperation returns each Operation's own result across the wire`() = runBlocking {
    val root = project("api")
    Files.writeString(root.resolve("README"), "hello\n")
    val workspace = client.perform(ManagementAct.Register(root.toString(), "api"))
    client.perform(ManagementAct.SetLevel(workspace.id, AccessLevel.Command))
    val operations = listOf(
      Operation.ListWorkspaces,
      Operation.ReadFile("api", "README"),
      Operation.ListDirectory("api"),
      Operation.Search("api", "hello"),
      Operation.GitStatus("api"),
      Operation.WriteFile("api", "notes.txt", "one\n", deliveryKey = "w"),
      Operation.EditFile("api", "notes.txt", "one", "two", deliveryKey = "e"),
      Operation.RunCommand("api", "echo hi", deliveryKey = "r"),
      Operation.GetResult("api", "no-such-handle"),
    )
    operations.forEach { op ->
      val outcome = client.perform(ManagementAct.TryOperation(op))
      // A repeat Delivery answers with the recorded first reply, which is the core's own answer.
      assertEquals(pipeline.operationsFor(Origin.Frontend).perform(op).fresh(), outcome.fresh(), "$op")
    }
  }

  @Test
  fun `Disconnect and Revoke stop no running work, StopOperation ends one command, and Stop ends the Runtime`() = runBlocking {
    val workspace = client.perform(ManagementAct.Register(project("api").toString(), "api"))
    client.perform(ManagementAct.SetLevel(workspace.id, AccessLevel.Command))

    // Disconnect, then Revoke, each with a command running: both finish.
    for (act in listOf(ManagementAct.Disconnect, ManagementAct.SetLevel(workspace.id, AccessLevel.None))) {
      client.perform(ManagementAct.SetLevel(workspace.id, AccessLevel.Command))
      val running = async(Dispatchers.IO) {
        client.perform(ManagementAct.TryOperation(Operation.RunCommand("api", "sleep 0.5; echo finished", deliveryKey = "$act")))
      }
      awaitRunning("$act")
      client.perform(act)
      val finished = assertIs<CommandReply.Finished>(assertIs<Outcome.Ok<CommandReply>>(running.await()).value)
      assertEquals("finished\n", finished.result.output, "$act stopped running work")
    }
    assertFalse(exited)

    // StopOperation: that one command, named by the entry a frontend sees it running as.
    client.perform(ManagementAct.SetLevel(workspace.id, AccessLevel.Command))
    val stopped = async(Dispatchers.IO) {
      client.perform(ManagementAct.TryOperation(Operation.RunCommand("api", "sleep 30", deliveryKey = "long")))
    }
    client.perform(ManagementAct.StopOperation(awaitRunning("long").entry))
    val ended = assertIs<Outcome.Uncertain>(stopped.await())
    assertIs<Uncertainty.Stopped>(ended.reason)
    assertContains(ended.message, "the user stopped it")
    // Acknowledge closes the loop on it: the Uncertain entry is settled by an appended fact.
    val entry = activity.entries().single { it.needsAttention }
    client.perform(ManagementAct.Acknowledge(entry.id))
    assertFalse(activity.entries().single { it.id == entry.id }.needsAttention)
    assertFailsWith<IllegalArgumentException> { client.perform(ManagementAct.StopOperation(entry.id)) }

    // Stop: what is running is ended, new calls are refused, and the process is told to exit.
    val last = async(Dispatchers.IO) {
      client.perform(ManagementAct.TryOperation(Operation.RunCommand("api", "sleep 30", deliveryKey = "last")))
    }
    awaitRunning("last")
    client.perform(ManagementAct.Stop)
    assertIs<Uncertainty.Stopped>(assertIs<Outcome.Uncertain>(last.await()).reason)
    assertTrue(exited)
    val refused = client.perform(ManagementAct.TryOperation(Operation.ListWorkspaces))
    assertEquals(Failure.RuntimeStopping, assertIs<Outcome.Failed>(refused).reason)
  }

  @Test
  fun `a process running as another Linux user cannot use the socket`() = runBlocking {
    server.stop()
    // The kernel's word for who dialed is this user, and the server serves somebody else: the
    // same refusal a different uid gets, without needing a second account on the machine.
    val socket = temporary.resolve("elsewhere.sock")
    server = ControlServer(core, socket, owner = "somebody-else").apply { start() }
    val stranger = ManagementClient(socket)

    val refused = assertFailsWith<IllegalStateException> { stranger.perform(ManagementAct.Register(project("api").toString())) }
    assertContains(refused.message!!, "somebody-else")
    assertFailsWith<IllegalStateException> { withTimeout(5.seconds) { stranger.observe().first() } }
    assertEquals(emptyList(), core.observe().first().let { (it as RuntimeEvent.Snapshot).workspaces })
  }

  /**
   * The in-flight command sent with [deliveryKey], as a frontend sees it through its own snapshot.
   * Its own, not merely one: a command that has just answered can still be listed for a moment,
   * and stopping that one is rightly refused.
   */
  private suspend fun awaitRunning(deliveryKey: String): RunningOperation = withTimeout(10.seconds) {
    var state: RuntimeEvent.Snapshot? = null
    val mine = { operation: RunningOperation -> operation.arguments.endsWith("request_id=$deliveryKey") }
    client.observe().first { event ->
      val next = if (event is RuntimeEvent.Snapshot) event else state!!.after(event as RuntimeEvent.Change)
      state = next
      next.running.any(mine)
    }
    state!!.running.single(mine)
  }

  private fun Outcome<*>.fresh(): Outcome<*> = when (this) {
    is Outcome.Ok<*> -> copy(recorded = false)
    else -> this
  }
}
