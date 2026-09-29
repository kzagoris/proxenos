package io.github.kzagoris.proxenos.mcp

import io.github.kzagoris.proxenos.core.Activity
import io.github.kzagoris.proxenos.core.WorkspaceOperationsPipeline
import io.github.kzagoris.proxenos.core.WorkspaceRegistry
import io.github.kzagoris.proxenos.coreapi.AccessLevel
import io.github.kzagoris.proxenos.coreapi.ArgumentSpec
import io.github.kzagoris.proxenos.coreapi.ArgumentType
import io.github.kzagoris.proxenos.coreapi.Collected
import io.github.kzagoris.proxenos.coreapi.CommandReply
import io.github.kzagoris.proxenos.coreapi.FileContent
import io.github.kzagoris.proxenos.coreapi.Handle
import io.github.kzagoris.proxenos.coreapi.RunningCommand
import io.github.kzagoris.proxenos.coreapi.ManagementAct
import io.github.kzagoris.proxenos.coreapi.Operation
import io.github.kzagoris.proxenos.coreapi.OperationSpec
import io.github.kzagoris.proxenos.coreapi.Origin
import io.github.kzagoris.proxenos.coreapi.Outcome
import io.github.kzagoris.proxenos.coreapi.Uncertainty
import io.github.kzagoris.proxenos.coreapi.WorkspaceOperations
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.sse.SSE
import io.ktor.client.request.unixSocket
import io.modelcontextprotocol.kotlin.sdk.client.Client
import io.modelcontextprotocol.kotlin.sdk.client.mcpStreamableHttp
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import io.modelcontextprotocol.kotlin.sdk.types.Tool
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.io.TempDir
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/**
 * The transport, tested as a transport. SPEC §13.1 exercises the eleven through
 * `WorkspaceOperations` with no transport at all, which is what leaves this file its subject:
 * a real MCP client over the real Unix socket, so the handshake, the rendered catalog and the
 * envelope around an Outcome are observed rather than inferred.
 */
class McpEndpointTest {
  @TempDir
  lateinit var temporary: Path

  @Test
  fun `the handshake completes over the socket, and read_file comes back through it`() = runBlocking {
    val root = Files.createDirectory(temporary.resolve("project"))
    Files.writeString(root.resolve("hello.txt"), "one\ntwo\nthree\n")
    val (registry, operations) = pipeline()
    registry.perform(ManagementAct.Register(root.toString(), "api"))

    served(operations) { client ->
      assertEquals(
        operations.catalog.map { it.name },
        client.listTools().tools.map { it.name },
        "the rendered list is the core's catalog, in the core's order",
      )
      val reply = client.callTool("read_file", mapOf("workspace" to "api", "path" to "hello.txt"))
      assertEquals(false, reply.isError)
      val said = reply.said()
      assertContains(said, "lines 1-3 of 3")
      assertContains(said, "one\ntwo\nthree")
    }
  }

  @Test
  fun `a repeat Delivery reads as the first reply verbatim, saying it is a recorded result`() = runBlocking {
    // SPEC §6.4: the transport repeats a call byte for byte. The second edit_file finds zero
    // matches if it runs, and zero matches is `failed` about a change already made.
    val root = Files.createDirectory(temporary.resolve("project"))
    Files.writeString(root.resolve("notes.md"), "alpha\n")
    val (registry, operations) = pipeline()
    val workspace = registry.perform(ManagementAct.Register(root.toString(), "api"))
    registry.perform(ManagementAct.SetLevel(workspace.id, AccessLevel.Write))
    val call = mapOf(
      "workspace" to "api", "path" to "notes.md", "old_text" to "alpha", "new_text" to "beta", "request_id" to "r-1",
    )

    served(operations) { client ->
      val first = client.callTool("edit_file", call)
      val repeat = client.callTool("edit_file", call)

      assertEquals(false, repeat.isError)
      assertTrue(repeat.said().startsWith(first.said()), "the first reply, verbatim: ${repeat.said()}")
      assertContains(repeat.said(), Outcome.RECORDED_RESULT)
      assertFalse(first.said().contains(Outcome.RECORDED_RESULT))
    }
  }

  @Test
  fun `a catalog entry the core adds is published with nothing edited in the adapter`() = runBlocking {
    val invented = OperationSpec(
      "count_the_stars", "An entry no adapter has ever heard of.", AccessLevel.Read,
      listOf(ArgumentSpec(Operation.WORKSPACE_ARGUMENT, ArgumentType.Text, true, "The Workspace.")),
    )
    val operations = stub(catalog = catalog() + invented)

    served(operations) { client ->
      val published = client.listTools().tools.single { it.name == "count_the_stars" }
      assertEquals(invented.description, published.description)
      assertEquals(listOf("workspace"), published.inputSchema.required)

      // Published, and answered: a catalog is fixed when the connector is created, so an entry
      // whose Operation has not landed is still enumerated and still has to say something.
      val reply = client.callTool("count_the_stars", mapOf("workspace" to "api"))
      assertEquals(true, reply.isError)
      assertContains(reply.said(), "not in this build")
    }
  }

  @Test
  fun `every scoped entry requires workspace, and the mutating three carry the repeat wording`() = runBlocking {
    val operations = pipeline().second
    val scoped = operations.catalog.filter { it.requiredLevel != null }.map { it.name }.toSet()

    served(operations) { client ->
      val tools = client.listTools().tools.associateBy { it.name }
      scoped.forEach { name ->
        assertContains(tools.getValue(name).inputSchema.required.orEmpty(), "workspace", "$name routes by workspace")
      }
      assertFalse("workspace" in tools.getValue("list_workspaces").inputSchema.required.orEmpty())

      val mutating = listOf("write_file", "edit_file", "run_command")
      mutating.forEach { name ->
        val declared = tools.getValue(name).describes("request_id")
        assertContains(tools.getValue(name).inputSchema.required.orEmpty(), "request_id")
        // Verbatim, because this description is the only lever on a repeat the model initiates
        // itself: an adapter that trimmed it would be editing the instruction that limits replays.
        assertEquals(
          operations.catalog.single { it.name == name }.arguments.single { it.name == "request_id" }.description,
          declared,
        )
        assertContains(declared, "Supply a fresh unique request_id for each operation you intend to perform.")
        assertContains(declared, "repeat it with the same request_id")
      }
      val unkeyed = operations.catalog.filter { it.name !in mutating }
      assertTrue(unkeyed.none { spec -> spec.arguments.any { it.name == "request_id" } })
    }
  }

  @Test
  fun `the tool list is the same before and after an Access Level change`() = runBlocking {
    val root = Files.createDirectory(temporary.resolve("project"))
    val (registry, operations) = pipeline()
    val workspace = registry.perform(ManagementAct.Register(root.toString(), "api"))

    served(operations) { client ->
      val before = client.listTools().tools
      registry.perform(ManagementAct.SetLevel(workspace.id, AccessLevel.None))
      val withheld = client.listTools().tools
      registry.perform(ManagementAct.SetLevel(workspace.id, AccessLevel.Command))
      val raised = client.listTools().tools

      assertEquals(before, withheld, "the catalog is static: withholding a Workspace does not reshape it")
      assertEquals(before, raised, "nor does raising one to Command")
      // Enforcement happens when a call arrives, not by hiding a tool the snapshot still holds.
      val refused = client.callTool("read_file", mapOf("workspace" to "gone", "path" to "x"))
      assertEquals(true, refused.isError)
    }
  }

  @Test
  fun `uncertain arrives with its wording intact and unparaphrased`() = runBlocking {
    val message = "This command was stopped after the budget ran out; " +
      "${Outcome.Uncertain.EFFECTS_UNCERTAIN}. Surviving processes: none."
    val operations = stub { Outcome.Uncertain(Uncertainty.TimedOut(emptyList()), message) }

    served(operations) { client ->
      val reply = client.callTool(
        "run_command",
        mapOf("workspace" to "api", "command" to "sleep 60", "request_id" to "r-1"),
      )
      assertEquals(true, reply.isError)
      assertEquals(message, reply.said(), "the core wrote this sentence; the adapter carries it")
      assertContains(reply.said(), Outcome.Uncertain.EFFECTS_UNCERTAIN)
    }
  }

  @Test
  fun `a Promoted reply carries its Handle and is not worded as success`() = runBlocking {
    // SPEC §6.2, ADR 0003: Promoted is a reply, not a fourth outcome, and a sentence that read
    // like a success is a model's cue to move on from a build it has not seen the end of.
    val handle = Handle.of("h-1")
    val running = RunningCommand("make", ".", 45.seconds, "compiling\n", 0)
    val operations = stub { Outcome.Ok(CommandReply.Promoted(handle, running)) }

    served(operations) { client ->
      val reply = client.callTool(
        "run_command",
        mapOf("workspace" to "api", "command" to "make", "request_id" to "r-1"),
      )
      val said = reply.said()

      assertEquals(false, reply.isError, "a command that is still running is not an error")
      assertContains(said, "has not finished")
      assertContains(said, "is not a result")
      assertContains(said, "get_result")
      assertContains(said, "h-1")
      assertContains(said, "compiling")
      assertFalse(
        listOf("succeeded", "success", "started successfully").any { it in said.lowercase() },
        "Promoted must never be worded as success: $said",
      )
    }
  }

  @Test
  fun `a collected outcome travels as the call that produced it would have`() = runBlocking {
    // ADR 0001: the distinction lives in words a model acts on, and lateness does not soften
    // it. `get_result` succeeded; what it collected is what the model has to act on.
    val message = "This command was stopped because the Runtime was stopped. It had already " +
      "begun, so what it did to this machine is unknown — ${Outcome.Uncertain.EFFECTS_UNCERTAIN}. " +
      "Nothing was left running."
    val collected = Collected.Reached(
      Handle.of("h-1"),
      Outcome.Uncertain(Uncertainty.Stopped(emptyList()), message),
    )
    val operations = stub { Outcome.Ok(collected) }

    served(operations) { client ->
      val reply = client.callTool("get_result", mapOf("workspace" to "api", "handle" to "h-1"))
      assertEquals(true, reply.isError, "an outcome whose effects are unknown is not a non-error")
      assertEquals(message, reply.said(), "the core wrote this sentence; the adapter carries it")
    }
  }

  @Test
  fun `an operation-level problem is a tool result, and an unknown tool does not end the session`() = runBlocking {
    val root = Files.createDirectory(temporary.resolve("project"))
    Files.writeString(root.resolve("hello.txt"), "one\n")
    val (registry, operations) = pipeline()
    registry.perform(ManagementAct.Register(root.toString(), "api"))

    served(operations) { client ->
      // A withheld or unregistered Workspace is answered the same way, and as a tool result:
      // the model is who has to read it.
      val refused = client.callTool("read_file", mapOf("workspace" to "nowhere", "path" to "hello.txt"))
      assertEquals(true, refused.isError)
      assertContains(refused.said(), "No such Workspace")

      // Nothing was attempted, so nothing can be uncertain about it.
      val malformed = client.callTool(
        "read_file",
        mapOf("workspace" to "api", "path" to "hello.txt", "offset" to "half past two"),
      )
      assertEquals(true, malformed.isError)
      assertContains(malformed.said(), "'offset'")
      assertContains(malformed.said(), "nothing changed")

      val unknown = client.callTool("summon_the_moon", mapOf("workspace" to "api"))
      assertEquals(true, unknown.isError)

      // The session survives all three, which is what makes them results rather than faults.
      val fine = client.callTool("read_file", mapOf("workspace" to "api", "path" to "hello.txt"))
      assertEquals(false, fine.isError)
    }
  }

  @Test
  fun `the endpoint binds over a stale socket file without the caller unlinking it`() = runBlocking {
    val root = Files.createDirectory(temporary.resolve("project"))
    Files.writeString(root.resolve("hello.txt"), "one\n")
    val (registry, operations) = pipeline()
    registry.perform(ManagementAct.Register(root.toString(), "api"))
    val socket = temporary.resolve("mcp.sock")

    served(operations, socket) { client -> client.listTools() }
    assertTrue(Files.exists(socket, java.nio.file.LinkOption.NOFOLLOW_LINKS), "the file outlives the server")

    // No unlink here on purpose: Ktor unlinks and rebinds, which is why a Runtime that was
    // killed does not need its socket file swept up before it can start again.
    served(operations, socket) { client ->
      assertEquals(false, client.callTool("read_file", mapOf("workspace" to "api", "path" to "hello.txt")).isError)
    }
  }

  @Test
  fun `calls after the handshake overlap rather than serializing`() = runBlocking {
    val inFlight = AtomicInteger()
    val peak = AtomicInteger()
    val operations = stub {
      val now = inFlight.incrementAndGet()
      peak.accumulateAndGet(now) { a, b -> maxOf(a, b) }
      try {
        delay(500)
        Outcome.Ok(FileContent("hello.txt", "one\n", 1, 1, 1, cappedByBytes = false))
      } finally {
        inFlight.decrementAndGet()
      }
    }

    served(operations) { client ->
      val started = TimeSource.Monotonic.markNow()
      val replies = coroutineScope {
        (1..3).map {
          async { client.callTool("read_file", mapOf("workspace" to "api", "path" to "hello.txt")) }
        }.awaitAll()
      }
      val elapsed = started.elapsedNow()

      assertTrue(replies.all { it.isError == false })
      assertEquals(3, peak.get(), "three calls were in flight at once")
      assertTrue(elapsed < 1.5.seconds, "three 500 ms calls took $elapsed; serial dispatch would take 1.5 s")
    }
  }

  /**
   * The same overlap through the whole stack rather than against a stub: three real commands,
   * each sleeping a second, down the real pipeline. It is a second measurement of the same
   * fact, and the one that would catch a core that blocked the dispatcher it was called on.
   */
  @Test
  fun `real work overlaps too, all the way down the pipeline`() = runBlocking {
    val root = Files.createDirectory(temporary.resolve("project"))
    val (registry, operations) = pipeline()
    val workspace = registry.perform(ManagementAct.Register(root.toString(), "api"))
    registry.perform(ManagementAct.SetLevel(workspace.id, AccessLevel.Command))

    served(operations) { client ->
      val started = TimeSource.Monotonic.markNow()
      val replies = coroutineScope {
        (1..3).map { call ->
          async {
            client.callTool(
              "run_command",
              mapOf("workspace" to "api", "command" to "sleep 1", "request_id" to "r-$call"),
            )
          }
        }.awaitAll()
      }
      val elapsed = started.elapsedNow()

      assertTrue(replies.all { it.isError == false }, "each command ran: ${replies.map { it.said() }}")
      assertTrue(elapsed < 2.5.seconds, "three 1 s commands took $elapsed; serial execution would take 3 s")
    }
  }

  @Test
  fun `the logical host is http, and it is the one Host header the guard allows`(): Unit = runBlocking {
    assertEquals("http://proxenos.internal/mcp", LogicalHost().url)
    assertEquals(listOf("proxenos.internal"), LogicalHost().allowedHosts)

    val operations = pipeline().second
    val socket = temporary.resolve("mcp.sock")
    val endpoint = McpEndpoint(operations, socket)
    endpoint.start()
    try {
      // Same socket, a Host header the allowlist does not carry. The SDK's DNS-rebinding guard
      // answers 403 `Invalid Host` with no hint about the allowlist, which is why the host and
      // the URL the tunnel child is given come from one value and cannot disagree.
      val refused = assertFails { connect(socket, "http://somewhere-else.invalid/mcp") }
      assertContains("${refused}${refused.cause}", "Invalid Host", ignoreCase = true)
    } finally {
      endpoint.stop()
    }
  }

  /** What the composition root does (SPEC §9): one pipeline, and the ChatGPT surface's view of it. */
  private fun pipeline(): Pair<WorkspaceRegistry, WorkspaceOperations> {
    val registry = WorkspaceRegistry(temporary.resolve("registry.properties"))
    val activity = Activity(temporary.resolve("activity"))
    return registry to WorkspaceOperationsPipeline(registry, activity).operationsFor(Origin.ChatGpt)
  }

  private fun catalog(): List<OperationSpec> = pipeline().second.catalog

  private fun stub(
    catalog: List<OperationSpec> = catalog(),
    answer: suspend (Operation<*>) -> Outcome<*> = { Outcome.Ok(Unit) },
  ): WorkspaceOperations = object : WorkspaceOperations {
    override val catalog: List<OperationSpec> = catalog

    @Suppress("UNCHECKED_CAST")
    override suspend fun <R> perform(op: Operation<R>): Outcome<R> = answer(op) as Outcome<R>
  }

  /** Binds the socket, dials it with a real MCP client, and takes both down afterwards. */
  private suspend fun <T> served(
    operations: WorkspaceOperations,
    socket: Path = temporary.resolve("mcp.sock"),
    body: suspend (Client) -> T,
  ): T {
    val endpoint = McpEndpoint(operations, socket)
    endpoint.start()
    try {
      val client = connect(socket, LogicalHost().url)
      try {
        return body(client)
      } finally {
        client.close()
      }
    } finally {
      endpoint.stop()
    }
  }

  /**
   * The client leg the tunnel child makes for real: an `http://` URL whose host is logical and
   * unresolvable, with the connection itself routed to the Unix socket.
   */
  private suspend fun connect(socket: Path, url: String): Client {
    val http = HttpClient(CIO) { install(SSE) }
    return http.mcpStreamableHttp(url) { unixSocket(socket.toString()) }
  }

  private fun CallToolResult.said(): String =
    content.filterIsInstance<TextContent>().joinToString("\n") { it.text.orEmpty() }

  private fun Tool.describes(argument: String): String =
    checkNotNull(inputSchema.properties).getValue(argument).jsonObject.getValue("description").jsonPrimitive.content
}
