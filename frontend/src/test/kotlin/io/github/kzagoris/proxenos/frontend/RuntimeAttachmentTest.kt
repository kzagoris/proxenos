package io.github.kzagoris.proxenos.frontend

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
import io.github.kzagoris.proxenos.coreapi.ManagementAct
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.ServerSocketChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.PosixFilePermissions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.io.TempDir
import kotlin.test.*
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Attaching as a frontend does it: over a real control socket, to a real core. Where a start is
 * asked for, a stub stands in for the `runtime` launcher, and the core it "starts" is the
 * in-process one, bound to the socket the stub was told to use.
 */
class RuntimeAttachmentTest {
  @TempDir
  lateinit var temporary: Path

  private lateinit var socket: Path
  private var server: ControlServer? = null
  private var pipeline: WorkspaceOperationsPipeline? = null
  private val scope = CoroutineScope(Dispatchers.IO)

  @BeforeTest
  fun `a control socket nothing answers on yet`() {
    socket = temporary.resolve("run/control.sock")
  }

  @AfterTest
  fun `nothing outlives the test`() = runBlocking<Unit> {
    scope.cancel()
    server?.stop()
    pipeline?.stop()
  }

  /** A core on [socket], answering until something stops it. */
  private fun runtime() {
    val config = RuntimeConfig(
      stateDirectory = Files.createDirectories(temporary.resolve("state")),
      mcpSocket = temporary.resolve("mcp.sock"),
      controlSocket = socket,
      tunnelExecutable = temporary.resolve("tunnel-client"),
      killGrace = 200.milliseconds,
      commandBudget = 300.milliseconds,
    )
    val feed = RuntimeFeed()
    val registry = WorkspaceRegistry(config.registryFile, feed)
    val activity = Activity(config.activityFile, config.activityRetention, feed)
    val pipeline = WorkspaceOperationsPipeline(registry, activity, config).also { pipeline = it }
    val tunnel = Tunnel(config, "http://runtime.invalid/mcp", TunnelCredentials("tunnel", "key")) {}
    // Stop ends the Runtime, and that is what closes the stream under every attached frontend:
    // here, the server stopping, on its own thread as the real exit is.
    val core = RuntimeManagement(
      registry, activity, tunnel, pipeline,
      ConnectorAcknowledgement(config.connectorFile, feed), feed,
    ) { Thread { server?.stop() }.start() }
    Files.createDirectories(socket.parent)
    server = ControlServer(core, socket).apply { start() }
  }

  private fun stub(script: String): Path {
    val stub = temporary.resolve("runtime")
    Files.writeString(stub, "#!/bin/sh\n$script\n")
    Files.setPosixFilePermissions(stub, PosixFilePermissions.fromString("rwx------"))
    return stub
  }

  private suspend fun Flow<Attachment>.settled(): List<Attachment> = withTimeout(10.seconds) { toList() }

  @Test
  fun `a Runtime that answers is attached to, and nothing is started`() = runBlocking<Unit> {
    runtime()
    val attachment = RuntimeAttachment(socket, stub("touch '${temporary.resolve("started")}'"))
    val attached = withTimeout(5.seconds) { attachment.open().first { it is Attachment.Attached } }
    assertIs<Attachment.Attached>(attached)
    assertFalse(Files.exists(temporary.resolve("started")), "a Runtime already answering is not started again")

    // Every event arrives folded onto the snapshot, not as a bare change.
    val next = scope.async { attachment.open().first { it is Attachment.Attached && it.snapshot.workspaces.isNotEmpty() } }
    val root = Files.createDirectories(temporary.resolve("notes"))
    attachment.management.perform(ManagementAct.Register(root.toString(), null))
    val registered = withTimeout(5.seconds) { next.await() }
    assertIs<Attachment.Attached>(registered)
    assertEquals("notes", registered.snapshot.workspaces.single().workspace.name)
  }

  @Test
  fun `a Runtime that is absent is started, then attached to, on the socket this frontend resolved`() = runBlocking<Unit> {
    // The stub is the Runtime's launcher: it says which socket it was told to bind, and the test
    // binds a real core there. It waits for the socket, as a real Runtime lives past its answer.
    val told = temporary.resolve("told")
    val attachment = RuntimeAttachment(
      socket,
      stub("echo \"\$PROXENOS_CONTROL_SOCKET\" > '$told.part' && mv '$told.part' '$told'\nwhile [ ! -S \"\$PROXENOS_CONTROL_SOCKET\" ]; do sleep 0.05; done"),
    )
    val bound = scope.async {
      while (!Files.exists(told)) delay(20)
      Files.readString(told).trim().also { runtime() }
    }
    val seen = withTimeout(10.seconds) {
      val seen = mutableListOf<Attachment>()
      attachment.open().first { seen += it; it is Attachment.Attached || it is Attachment.Absent }
      seen
    }
    assertEquals(socket.toString(), bound.await())
    assertEquals(listOf(Attachment.Starting, Attachment.Attaching), seen.dropLast(1))
    assertIs<Attachment.Attached>(seen.last())
  }

  @Test
  fun `the stream ending is Absent, NotAnswering, and the flow completes there`() = runBlocking<Unit> {
    runtime()
    val attachment = RuntimeAttachment(socket, null)
    val arrived = MutableStateFlow<List<Attachment>>(emptyList())
    val collected = scope.async { attachment.attach(startIfAbsent = false).onEach { arrived.value += it }.settled() }
    // Attached before it is stopped, so what ends is a stream that was open.
    withTimeout(5.seconds) { arrived.first { seen -> seen.any { it is Attachment.Attached } } }
    attachment.management.perform(ManagementAct.Stop)

    val seen = collected.await()
    assertEquals(Attachment.Attaching, seen.first())
    assertTrue(seen.drop(1).dropLast(1).all { it is Attachment.Attached }, "$seen")
    assertEquals(Attachment.Absent(Reason.NotAnswering), seen.last())
  }

  @Test
  fun `nothing answering, and no start asked for, is Absent at once with nothing started`() = runBlocking<Unit> {
    val started = temporary.resolve("started")
    val seen = RuntimeAttachment(socket, stub("touch '$started'")).attach(startIfAbsent = false).settled()
    assertEquals(listOf<Attachment>(Attachment.Absent(Reason.NotAnswering)), seen)
    assertFalse(Files.exists(started))
  }

  @Test
  fun `a Runtime that refuses to start is Absent in its own words`() = runBlocking<Unit> {
    // Only this launch's words matter, even after years of appended output. A sparse file
    // exercises a large offset without filling the test machine's disk.
    Files.createDirectories(socket.parent)
    Files.newByteChannel(socket.resolveSibling("runtime.log"), StandardOpenOption.CREATE, StandardOpenOption.WRITE).use {
      it.position(Int.MAX_VALUE.toLong())
      it.write(ByteBuffer.wrap(byteArrayOf(10)))
    }
    val refusing = stub("echo 'runtime: will not start. No credentials file at /x/credentials.' >&2\nexit 78")
    val seen = RuntimeAttachment(socket, refusing).open().settled()
    assertEquals(Attachment.Starting, seen.first())
    assertEquals(Attachment.Absent(Reason.StartFailed("will not start. No credentials file at /x/credentials.")), seen.last())
  }

  @Test
  fun `a Runtime that exits saying nothing is Absent with its exit status`() = runBlocking<Unit> {
    val seen = RuntimeAttachment(socket, stub("exit 3")).open().settled()
    val words = assertIs<Reason.StartFailed>(assertIs<Attachment.Absent>(seen.last()).reason).words
    assertTrue("status 3" in words, words)
  }

  @Test
  fun `a Runtime that never answers is Absent once the wait is over, naming the socket and the log`() = runBlocking<Unit> {
    val seen = RuntimeAttachment(socket, stub("exec sleep 5"), patience = 300.milliseconds).open().settled()
    val words = assertIs<Reason.StartFailed>(assertIs<Attachment.Absent>(seen.last()).reason).words
    assertTrue("$socket" in words, words)
    assertTrue("${socket.resolveSibling("runtime.log")}" in words, words)
  }

  @Test
  fun `a named executable that is not there is refused before anything is started`() = runBlocking<Unit> {
    val missing = temporary.resolve("gone/bin/runtime")
    val seen = RuntimeAttachment(socket, missing).open().settled()
    val words = assertIs<Reason.StartFailed>(assertIs<Attachment.Absent>(seen.last()).reason).words
    assertTrue("$missing" in words, words)
  }

  @Test
  fun `a bare name is looked up on PATH, as any exec does`() = runBlocking<Unit> {
    // env is on every PATH, and what it prints proves it ran with the socket it was told.
    val seen = RuntimeAttachment(socket, Path.of("env")).open().settled()
    val words = assertIs<Reason.StartFailed>(assertIs<Attachment.Absent>(seen.last()).reason).words
    assertTrue("PROXENOS_CONTROL_SOCKET=$socket" in words, words)
  }

  @Test
  fun `a socket whose directory cannot be made is Absent naming it, not a crash`() = runBlocking<Unit> {
    val file = Files.createFile(temporary.resolve("a-file"))
    val seen = RuntimeAttachment(file.resolve("run/control.sock"), stub("exit 0")).open().settled()
    val words = assertIs<Reason.StartFailed>(assertIs<Attachment.Absent>(seen.last()).reason).words
    assertTrue("$file" in words, words)
  }

  @Test
  fun `a start waits only for an answer, never for the Runtime to speak`() = runBlocking<Unit> {
    // Accepts, as a suspended Runtime's socket still does, and never says a word.
    Files.createDirectories(socket.parent)
    ServerSocketChannel.open(StandardProtocolFamily.UNIX).use { silent ->
      silent.bind(UnixDomainSocketAddress.of(socket))
      val started = temporary.resolve("started")
      assertNull(withTimeout(5.seconds) { RuntimeAttachment(socket, stub("touch '$started'")).start() })
      assertFalse(Files.exists(started), "a Runtime already answering is not started again")
    }
  }

  @Test
  fun `with neither the variable nor the property, the refusal names both`() = runBlocking<Unit> {
    val executable = RuntimeAttachment.executable(environment = emptyMap(), property = null)
    assertNull(executable)
    val seen = RuntimeAttachment(socket, executable).open().settled()
    val words = assertIs<Reason.StartFailed>(assertIs<Attachment.Absent>(seen.last()).reason).words
    assertTrue(RuntimeAttachment.VARIABLE in words, words)
    assertTrue(RuntimeAttachment.PROPERTY in words, words)
  }

  @Test
  fun `the variable wins over the property the launcher script sets`() {
    assertEquals(Path.of("/from/variable"), RuntimeAttachment.executable(mapOf(RuntimeAttachment.VARIABLE to "/from/variable"), "/from/property"))
    assertEquals(Path.of("/from/property"), RuntimeAttachment.executable(emptyMap(), "/from/property"))
  }
}
