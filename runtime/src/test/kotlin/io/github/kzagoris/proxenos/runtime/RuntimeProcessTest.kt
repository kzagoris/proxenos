package io.github.kzagoris.proxenos.runtime

import io.github.kzagoris.proxenos.control.ManagementClient
import io.github.kzagoris.proxenos.coreapi.ManagementAct
import io.github.kzagoris.proxenos.coreapi.RuntimeEvent
import io.github.kzagoris.proxenos.coreapi.RuntimeState
import io.github.kzagoris.proxenos.coreapi.WorkspaceManagement
import io.github.kzagoris.proxenos.coreapi.WorkspaceState
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import io.github.kzagoris.proxenos.core.WorkspaceRegistry
import io.github.kzagoris.proxenos.coreapi.AccessLevel
import java.net.UnixDomainSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.SocketChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.util.concurrent.TimeUnit
import kotlin.text.Charsets.UTF_8
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource
import org.junit.jupiter.api.io.TempDir
import kotlin.test.*

/**
 * The `runtime` artifact as it is actually run: its own JVM, started from `main()`, with a stub
 * script standing in for `tunnel-client`. Nothing here can be asked of an object
 * in this test's JVM — whose environment the Runtime has, what its shutdown leaves behind,
 * whether a second one starts — so each test asks it of a real process.
 *
 * The stub records what it was started with — its arguments, its environment, its parent's
 * environment read out of `/proc` — starts a grandchild of its own, and then waits, as the real
 * one does.
 */
class RuntimeProcessTest {
  @TempDir
  lateinit var home: Path

  private val out: Path get() = home.resolve("stub")
  private val credentialsFile: Path get() = home.resolve(".config/proxenos/credentials")
  private val mcpSocket: Path get() = home.resolve("run/proxenos/mcp.sock")
  private val started = mutableListOf<Process>()

  @BeforeTest
  fun `a machine with a tunnel-client and nothing else`() {
    Files.createDirectories(out)
    val stub = home.resolve("bin/tunnel-client")
    Files.createDirectories(stub.parent)
    Files.writeString(
      stub,
      """
      #!/bin/sh
      out="${'$'}STUB_OUT"
      n=${'$'}(( ${'$'}(cat "${'$'}out/starts" 2>/dev/null || echo 0) + 1 ))
      echo "${'$'}n" > "${'$'}out/starts"
      printf '%s\n' "${'$'}@" > "${'$'}out/args"
      env > "${'$'}out/env"
      tr '\0' '\n' < /proc/${'$'}PPID/environ > "${'$'}out/parent-env"
      echo "${'$'}PPID" > "${'$'}out/parent"
      sleep 300 &
      echo "${'$'}!" > "${'$'}out/grandchild.${'$'}n"
      echo "${'$'}${'$'}" > "${'$'}out/pid.${'$'}n"
      echo "stub tunnel-client up"
      if [ "${'$'}n" = 1 ] && [ -n "${'$'}STUB_FIRST_EXIT" ]; then kill ${'$'}!; exit "${'$'}STUB_FIRST_EXIT"; fi
      wait
      """.trimIndent() + "\n",
    )
    Files.setPosixFilePermissions(stub, PosixFilePermissions.fromString("rwx------"))
  }

  @AfterTest
  fun `nothing this test started outlives it`() {
    started.forEach { runtime ->
      runtime.descendants().forEach { it.destroyForcibly() }
      runtime.destroyForcibly()
    }
    Files.list(out).use { files ->
      files.filter { it.fileName.toString().matches(Regex("(pid|grandchild)\\.\\d+")) }.forEach {
        ProcessHandle.of(Files.readString(it).trim().toLong()).ifPresent(ProcessHandle::destroyForcibly)
      }
    }
  }

  @Test
  fun `it starts with only a credentials file, and the credential reaches the tunnel child alone`() {
    credentials()
    // A key left in the shell the Runtime was started from is not the one it uses.
    val runtime = start("CONTROL_PLANE_API_KEY" to "sk-from-the-shell")
    awaitFile("pid.1")

    val child = lines("env")
    assertContains(child, "CONTROL_PLANE_TUNNEL_ID=tunnel_abc")
    assertContains(child, "CONTROL_PLANE_API_KEY=sk-secret")
    assertEquals(runtime.pid().toString(), Files.readString(out.resolve("parent")).trim(), "the tunnel is the Runtime's own child")
    val parent = Files.readString(out.resolve("parent-env"))
    assertFalse("CONTROL_PLANE_TUNNEL_ID" in parent, "the Runtime's own environment holds the tunnel ID")
    assertFalse("sk-secret" in parent, "the Runtime's own environment holds the runtime key")

    assertEquals(
      listOf(
        "run",
        "--mcp.server-url", "url=http://proxenos.internal/mcp,unix-socket=$mcpSocket",
        "--health.unix-socket", mcpSocket.resolveSibling("tunnel-health.sock").toString(),
        "--health.url-file", mcpSocket.resolveSibling("tunnel-health.url").toString(),
        "--control-plane.poll-timeout", "30s",
        "--control-plane.poll-deadline-guardrail", "5s",
        "--log.format", "json",
      ),
      lines("args"),
    )
    assertTrue(runtime.isAlive, output())
  }

  @Test
  fun `the MCP server allows exactly the host the tunnel child was told to send`() {
    credentials()
    Files.writeString(credentialsFile.resolveSibling("config.toml"), "logical_host = \"elsewhere.internal\"\n")
    start()
    awaitFile("pid.1")

    val flag = lines("args").let { it[it.indexOf("--mcp.server-url") + 1] }
    val (url, socket) = flag.split(',').map { it.substringAfter('=') }
    val host = url.removePrefix("http://").substringBefore('/')
    assertEquals("http://elsewhere.internal/mcp", url)
    assertEquals(200, httpStatus(Path.of(socket), host), output())
    assertEquals(403, httpStatus(Path.of(socket), "proxenos.internal"), "the guard is on, so the 200 above means something")
  }

  @Test
  fun `a second Runtime refuses to start and says one is already running`() {
    credentials()
    val first = start()
    awaitFile("pid.1")

    val second = start()
    assertTrue(second.waitFor(30, TimeUnit.SECONDS), "the second Runtime is still running")
    assertNotEquals(0, second.exitValue())
    assertContains(output(second), "already running")
    assertTrue(first.isAlive, "the first Runtime survived the second")
    assertEquals("1", Files.readString(out.resolve("starts")).trim(), "the second Runtime started a tunnel of its own")
  }

  /** Two Runtimes on one registry and one Activity would each write an account the other cannot see. */
  @Test
  fun `a second Runtime on another socket but the same state directory refuses too`() {
    credentials()
    start()
    awaitFile("pid.1")

    val second = start("PROXENOS_MCP_SOCKET" to home.resolve("run/other.sock").toString())
    assertTrue(second.waitFor(30, TimeUnit.SECONDS), "the second Runtime is still running")
    assertNotEquals(0, second.exitValue())
    assertContains(output(second), "already running")
    assertEquals("1", Files.readString(out.resolve("starts")).trim())
  }

  @Test
  fun `when the tunnel child dies the Runtime says how, stays up, and starts it again`() {
    credentials()
    val runtime = start("STUB_FIRST_EXIT" to "7")
    awaitFile("pid.2")
    assertContains(output(), "exited with code 7")
    assertTrue(runtime.isAlive)
  }

  @Test
  fun `stopping the Runtime leaves nothing of the tunnel child's tree behind`() {
    credentials()
    val runtime = start()
    awaitFile("pid.1")
    val tree = listOf("pid.1", "grandchild.1").map { Files.readString(out.resolve(it)).trim().toLong() }
    assertTrue(tree.all(::alive))

    runtime.destroy() // SIGTERM, which runs the shutdown hook.
    assertTrue(runtime.waitFor(30, TimeUnit.SECONDS), "the Runtime did not stop")
    // Briefly, because an orphan is reaped by whoever inherits it rather than at once.
    assertTrue(eventually(5.seconds) { tree.none(::alive) }, "orphaned: ${tree.filter(::alive)}\n${output()}")
  }

  @Test
  fun `a frontend reaches the Runtime over the control socket, and its Stop ends the Runtime and the tunnel child`() = runBlocking {
    credentials()
    val runtime = start()
    awaitFile("pid.1")
    val controlSocket = home.resolve("run/proxenos/control.sock")
    assertTrue(eventually(30.seconds) { Files.exists(controlSocket) }, "no control socket:\n${output()}")
    val frontend: WorkspaceManagement = ManagementClient(controlSocket)
    val project = Files.createDirectory(home.resolve("project"))

    val workspace = frontend.perform(ManagementAct.Register(project.toString(), "project"))
    val snapshot = assertIs<RuntimeEvent.Snapshot>(frontend.observe().first())
    assertEquals(listOf(WorkspaceState(workspace, broken = false)), snapshot.workspaces)
    assertEquals(RuntimeState.Connecting, snapshot.runtime.state)

    val tree = listOf("pid.1", "grandchild.1").map { Files.readString(out.resolve(it)).trim().toLong() }
    frontend.perform(ManagementAct.Stop)
    assertTrue(runtime.waitFor(30, TimeUnit.SECONDS), "Stop did not end the Runtime:\n${output()}")
    assertTrue(eventually(5.seconds) { tree.none(::alive) }, "orphaned: ${tree.filter(::alive)}\n${output()}")
    assertFalse(Files.exists(controlSocket), "the control socket outlived the Runtime")
  }

  private fun alive(pid: Long): Boolean = ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false)

  // Scenario 13, first part. Standard input is a pipe nobody writes to or closes: a Runtime that
  // prompted would sit on it until the timeout below.
  @Test
  fun `with no credentials file it refuses to start, naming the file and the wizard, without prompting`() {
    val runtime = start()
    assertTrue(runtime.waitFor(30, TimeUnit.SECONDS), "the Runtime is waiting for something")
    assertNotEquals(0, runtime.exitValue())
    assertContains(output(), credentialsFile.toString())
    assertContains(output(), "wizard")
    assertFalse(Files.exists(out.resolve("starts")), "a tunnel was started without a credential")
  }

  // Scenario 13, second part.
  @Test
  fun `a credentials file at mode 0644 is refused`() {
    credentials(mode = "rw-r--r--")
    val runtime = start()
    assertTrue(runtime.waitFor(30, TimeUnit.SECONDS))
    assertNotEquals(0, runtime.exitValue())
    assertContains(output(), "0644")
    assertFalse(Files.exists(out.resolve("starts")))
  }

  @Test
  fun `a directory registered from the command line is one the Runtime's registry exposes`() {
    val project = Files.createDirectories(home.resolve("projects/notes"))
    val register = start(arguments = listOf("register", project.toString(), "--name", "notes"))
    assertTrue(register.waitFor(30, TimeUnit.SECONDS), "register is waiting for something")
    assertEquals(0, register.exitValue(), output(register))

    val listed = WorkspaceRegistry(home.resolve(".local/state/proxenos/registry.properties")).listings()
    assertEquals(listOf("notes" to project.toString()), listed.map { it.name to it.root })
    assertEquals(AccessLevel.Read, listed.single().accessLevel)
  }

  @Test
  fun `registering is refused while a Runtime is running, and the registry is left alone`() {
    credentials()
    start()
    awaitFile("pid.1")

    val register = start(arguments = listOf("register", Files.createDirectories(home.resolve("notes")).toString()))
    assertTrue(register.waitFor(30, TimeUnit.SECONDS), "register is waiting for something")
    assertNotEquals(0, register.exitValue())
    assertContains(output(register), "already running")
    assertFalse(Files.exists(home.resolve(".local/state/proxenos/registry.properties")))
  }

  private fun credentials(mode: String = "rw-------") {
    Files.createDirectories(credentialsFile.parent)
    Files.writeString(credentialsFile, "TUNNEL_ID=tunnel_abc\nRUNTIME_KEY=sk-secret\n")
    Files.setPosixFilePermissions(credentialsFile, PosixFilePermissions.fromString(mode))
  }

  /** `main()` in a JVM of its own, given [arguments], with an environment built from nothing. */
  private fun start(vararg extra: Pair<String, String>, arguments: List<String> = emptyList()): Process {
    val java = ProcessHandle.current().info().command().get()
    val builder = ProcessBuilder(listOf(java, "-cp", System.getProperty("java.class.path"), "io.github.kzagoris.proxenos.runtime.MainKt") + arguments)
      .redirectErrorStream(true)
      .redirectOutput(home.resolve("runtime-${started.size}.log").toFile())
    builder.environment().apply {
      clear()
      put("HOME", home.toString())
      put("XDG_RUNTIME_DIR", home.resolve("run").toString())
      put("PATH", "${home.resolve("bin")}:/usr/bin:/bin")
      put("STUB_OUT", out.toString())
      putAll(extra)
    }
    return builder.start().also { started += it }
  }

  private fun output(process: Process = started.first()): String =
    Files.readString(home.resolve("runtime-${started.indexOf(process)}.log"))

  private fun lines(name: String): List<String> = Files.readAllLines(out.resolve(name))

  private fun awaitFile(name: String) {
    assertTrue(eventually(30.seconds) { Files.exists(out.resolve(name)) }, "no $name from the stub:\n${output()}")
  }

  private fun eventually(within: Duration, condition: () -> Boolean): Boolean {
    val deadline = TimeSource.Monotonic.markNow() + within
    while (!condition()) {
      if (deadline.hasPassedNow()) return false
      Thread.sleep(50)
    }
    return true
  }

  /** One `initialize` over the Unix socket, carrying [host] as the tunnel would. */
  private fun httpStatus(socket: Path, host: String): Int =
    SocketChannel.open(UnixDomainSocketAddress.of(socket)).use { channel ->
      val body = """{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18","capabilities":{},"clientInfo":{"name":"probe","version":"1"}}}"""
      val request = "POST /mcp HTTP/1.1\r\nHost: $host\r\nContent-Type: application/json\r\n" +
        "Accept: application/json, text/event-stream\r\nContent-Length: ${body.toByteArray(UTF_8).size}\r\n" +
        "Connection: close\r\n\r\n$body"
      channel.write(ByteBuffer.wrap(request.toByteArray(UTF_8)))
      val received = StringBuilder()
      val buffer = ByteBuffer.allocate(1024)
      while ("\r\n" !in received && channel.read(buffer.clear()) > 0) {
        received.append(UTF_8.decode(buffer.flip()))
      }
      received.lineSequence().first().split(' ')[1].toInt()
    }
}
