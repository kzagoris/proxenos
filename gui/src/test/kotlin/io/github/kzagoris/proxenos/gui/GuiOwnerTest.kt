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
    return GuiOwner(attachment, management ?: attachment.management).also { owners += it }
  }

  private suspend fun GuiOwner.until(predicate: (GuiState) -> Boolean): GuiState =
    withTimeout(5.seconds) { state.first(predicate) }

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
    assertEquals("Stop Runtime", gui.state.value.inFlight)
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
