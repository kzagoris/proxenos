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
import io.github.kzagoris.proxenos.coreapi.AccessLevel
import io.github.kzagoris.proxenos.coreapi.ManagementAct
import io.github.kzagoris.proxenos.coreapi.Operation
import io.github.kzagoris.proxenos.coreapi.Origin
import io.github.kzagoris.proxenos.frontend.Attachment
import io.github.kzagoris.proxenos.frontend.LocalSetup
import io.github.kzagoris.proxenos.frontend.RuntimeAttachment
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.io.TempDir
import kotlin.test.*
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * The commands an agent drives setup with, run as `bin/tui` runs them: over the control socket to
 * a real core, or to nothing at all where the Runtime is absent.
 */
class CommandsTest {
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

  private fun runtime() {
    val config = RuntimeConfig(
      stateDirectory = Files.createDirectories(temporary.resolve("state")),
      mcpSocket = temporary.resolve("mcp.sock"),
      controlSocket = Files.createDirectories(socket.parent).resolve(socket.fileName),
      tunnelExecutable = temporary.resolve("tunnel-client"),
    )
    val feed = RuntimeFeed()
    val registry = WorkspaceRegistry(config.registryFile, feed)
    val activity = Activity(config.activityFile, config.activityRetention, feed)
    val operations = WorkspaceOperationsPipeline(registry, activity, config).also { pipeline = it }
    val tunnel = Tunnel(config, "http://runtime.invalid/mcp", TunnelCredentials("tunnel", "key")) {}
    val core = RuntimeManagement(
      registry, activity, tunnel, operations,
      ConnectorAcknowledgement(config.connectorFile, feed), feed,
    ) { Thread { server?.stop() }.start() }
    server = ControlServer(core, socket).apply { start() }
  }

  private val attachment get() = RuntimeAttachment(socket, executable = null)

  private val environment get() = mapOf(
    "XDG_CONFIG_HOME" to temporary.resolve("config").toString(),
    "XDG_STATE_HOME" to temporary.resolve("xdg-state").toString(),
    "PATH" to "",
  )

  private fun local() = LocalSetup(environment)

  private fun executable(path: Path): Path = Files.createDirectories(path.parent).let {
    Files.writeString(path, "#!/bin/sh\n").also { Files.setPosixFilePermissions(it, PosixFilePermissions.fromString("rwx------")) }
  }

  private fun credentials(mode: String = "rw-------"): Path {
    val file = Files.createDirectories(temporary.resolve("config/proxenos")).resolve("credentials")
    Files.writeString(file, "TUNNEL_ID=tunnel_fixture\nRUNTIME_KEY=sk-fixture-secret\n")
    Files.setPosixFilePermissions(file, PosixFilePermissions.fromString(mode))
    return file
  }

  private fun json(text: String): JsonObject = Json.parseToJsonElement(text).jsonObject

  private fun JsonObject.text(key: String): String? = this[key]?.takeIf { it != JsonNull }?.jsonPrimitive?.content

  private suspend fun register(root: String, name: String) =
    attachment.management.perform(ManagementAct.Register(Files.createDirectories(temporary.resolve(root)).toString(), name))

  @Test
  fun `status says an absent Runtime is absent, without starting one, and still reports what is local`() = runBlocking<Unit> {
    val out = StringBuilder()
    assertEquals(0, status(attachment, local(), runtime = null, out))
    val status = json(out.toString())
    assertEquals(1, status["version"]!!.jsonPrimitive.int)
    assertEquals("absent", status.text("runtime"))
    assertEquals(JsonNull, status["workspaces"])
    val requirements = status["requirements"]!!.jsonArray.associate { it.jsonObject.text("name") to it.jsonObject }
    assertEquals(setOf("runtime", "tunnel-client", "credentials"), requirements.keys)
    assertTrue(requirements.values.none { it["met"]!!.jsonPrimitive.content.toBoolean() })
    assertFalse(attachment.answering())
  }

  @Test
  fun `status reports the readings and every Workspace in the domain's terms`() = runBlocking<Unit> {
    runtime()
    val notes = register("notes", "notes")
    attachment.management.perform(ManagementAct.SetLevel(notes.id, AccessLevel.Write))
    val out = StringBuilder()
    assertEquals(0, status(attachment, local(), runtime = null, out))
    val status = json(out.toString())
    assertEquals("running", status.text("runtime"))
    assertEquals("Connecting", status["tunnel"]!!.jsonObject.text("state"))
    assertEquals("Unconfirmed", status.text("connector"))
    val workspace = status["workspaces"]!!.jsonArray.single().jsonObject
    assertEquals("notes", workspace.text("name"))
    assertEquals(notes.root, workspace.text("root"))
    assertEquals("Write", workspace.text("accessLevel"))
    assertEquals("false", workspace.text("broken"))
  }

  @Test
  fun `status names the saved tunnel ID and never the runtime key`() = runBlocking<Unit> {
    credentials()
    val out = StringBuilder()
    status(attachment, local(), runtime = null, out)
    assertFalse("sk-fixture-secret" in out)
    val status = json(out.toString())
    assertEquals("tunnel_fixture", status.text("tunnelId"))
    val saved = status["requirements"]!!.jsonArray.map { it.jsonObject }.single { it.text("name") == "credentials" }
    assertEquals("true", saved.text("met"))
  }

  @Test
  fun `credentials other users can read are not met, and their tunnel ID is not trusted`() = runBlocking<Unit> {
    credentials(mode = "rw-r--r--")
    val out = StringBuilder()
    status(attachment, local(), runtime = null, out)
    val status = json(out.toString())
    assertNull(status.text("tunnelId"))
    val saved = status["requirements"]!!.jsonArray.map { it.jsonObject }.single { it.text("name") == "credentials" }
    assertEquals("false", saved.text("met"))
    assertTrue("chmod 600" in saved.text("words")!!)
  }

  @Test
  fun `check fails naming what is missing, and passes once everything is in place`() {
    val missing = StringBuilder()
    assertEquals(1, check(local(), runtime = null, missing))
    assertTrue("bin/install-tunnel-client" in missing, missing.toString())
    assertTrue("bin/wizard" in missing, missing.toString())

    val runtime = executable(temporary.resolve("bin/runtime"))
    executable(temporary.resolve("xdg-state/proxenos/tools/tunnel-client"))
    credentials()
    val met = StringBuilder()
    assertEquals(0, check(local(), runtime, met), met.toString())
  }

  @Test
  fun `await-listing returns on ChatGPT's listing of the Root, and not on a frontend's Try`() = runBlocking<Unit> {
    runtime()
    register("notes", "notes")
    val out = StringBuilder()
    val waiting = scope.async { awaitListing(attachment, "notes", 10.seconds, out) }
    withTimeout(5.seconds) { while ("Waiting" !in out) delay(20.milliseconds) }
    attachment.management.perform(ManagementAct.TryOperation(Operation.ListDirectory("notes")))
    delay(300.milliseconds)
    assertFalse(waiting.isCompleted, "a frontend's Try is not ChatGPT's request")
    pipeline!!.operationsFor(Origin.ChatGpt).perform(Operation.ListDirectory("notes"))
    assertEquals(0, withTimeout(5.seconds) { waiting.await() }, out.toString())
    assertTrue("list_directory" in out, out.toString())
  }

  @Test
  fun `await-listing does not count a listing from before it began`() = runBlocking<Unit> {
    runtime()
    register("notes", "notes")
    pipeline!!.operationsFor(Origin.ChatGpt).perform(Operation.ListDirectory("notes"))
    val out = StringBuilder()
    assertEquals(TIMED_OUT, awaitListing(attachment, "notes", 500.milliseconds, out), out.toString())
  }

  @Test
  fun `await-listing gives up when the Workspace moves to another Root while it waits`() = runBlocking<Unit> {
    runtime()
    val notes = register("notes", "notes")
    val out = StringBuilder()
    val waiting = scope.async { awaitListing(attachment, "notes", 10.seconds, out) }
    withTimeout(5.seconds) { while ("Waiting" !in out) delay(20.milliseconds) }
    attachment.management.perform(ManagementAct.Reconfirm(notes.id, Files.createDirectories(temporary.resolve("elsewhere")).toString()))
    assertEquals(1, withTimeout(5.seconds) { waiting.await() }, out.toString())
    assertTrue("moved" in out, out.toString())
  }

  @Test
  fun `await-listing refuses a Workspace ChatGPT cannot list`() = runBlocking<Unit> {
    runtime()
    val withheld = register("withheld", "withheld")
    attachment.management.perform(ManagementAct.SetLevel(withheld.id, AccessLevel.None))
    val unknown = StringBuilder()
    assertEquals(1, awaitListing(attachment, "nothing-by-this-name", 10.seconds, unknown))
    assertTrue("nothing-by-this-name" in unknown, unknown.toString())
    val none = StringBuilder()
    assertEquals(1, awaitListing(attachment, "withheld", 10.seconds, none))
    assertTrue("None" in none, none.toString())
  }

  @Test
  fun `register adds a Workspace at Read to the running Runtime`() = runBlocking<Unit> {
    runtime()
    val root = Files.createDirectories(temporary.resolve("project"))
    val out = StringBuilder()
    assertEquals(0, register(attachment, root.toString(), "project", out), out.toString())
    val snapshot = attachment.attach(startIfAbsent = false)
      .first { it is Attachment.Attached } as Attachment.Attached
    val workspace = snapshot.snapshot.workspaces.single().workspace
    assertEquals("project" to AccessLevel.Read, workspace.name to workspace.accessLevel)
    assertEquals(root.toString(), workspace.root)
  }

  @Test
  fun `register refuses a Root that overlaps one already registered, and registers nothing`() = runBlocking<Unit> {
    runtime()
    register("project", "project")
    val inside = Files.createDirectories(temporary.resolve("project/backend"))
    val out = StringBuilder()
    assertEquals(1, register(attachment, inside.toString(), "backend", out))
    assertTrue("project" in out, out.toString())
    val snapshot = attachment.attach(startIfAbsent = false)
      .first { it is Attachment.Attached } as Attachment.Attached
    assertEquals(listOf("project"), snapshot.snapshot.workspaces.map { it.workspace.name })
  }

  @Test
  fun `register with no Runtime running says how to register instead`() = runBlocking<Unit> {
    val out = StringBuilder()
    assertEquals(1, register(attachment, temporary.toString(), null, out))
    assertTrue("runtime register" in out, out.toString())
  }
}
