package io.github.kzagoris.proxenos.mcp

import io.github.kzagoris.proxenos.coreapi.Operation
import io.github.kzagoris.proxenos.coreapi.OperationSpec
import io.github.kzagoris.proxenos.coreapi.Outcome
import io.github.kzagoris.proxenos.coreapi.WorkspaceOperations
import io.ktor.server.cio.CIO
import io.ktor.server.cio.unixConnector
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.ServerOptions
import io.modelcontextprotocol.kotlin.sdk.server.mcpStreamableHttp
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.ServerCapabilities
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.serialization.json.JsonObject

/**
 * The logical host the tunnel dials this Runtime by (SPEC §11.2). It is never registered with
 * OpenAI and the user never sees it: it is a local flag the Runtime passes to the child it
 * launched, so the Runtime owns both ends of the coupling §7 found and **one value seeds
 * them**. Two fields that can disagree is exactly how the bare
 * `403 {"code":-32000,"message":"Invalid Host: ..."}` gets built.
 *
 * The scheme is fixed here rather than configured, because a user-editable `https://` is how
 * `http: server gave HTTP response to HTTPS client` gets built: `tunnel-client` terminates no
 * TLS on the way to a Unix socket, so it would be speaking TLS at a plaintext server. Neither
 * failure explains itself.
 */
data class LogicalHost(val name: String = DEFAULT) {
  /** The `--mcp.server-url` flag's URL half, which the Runtime hands the tunnel child. */
  val url: String get() = "http://$name$PATH"

  /**
   * What `mcpStreamableHttp` is given. The SDK's DNS-rebinding guard rejects any Host header
   * outside this list, and the tunnel forwards [name] in that header across the socket.
   */
  val allowedHosts: List<String> get() = listOf(name)

  companion object {
    /** SPEC §11.2: the compiled-in constant. A `config.toml` override replaces this one value. */
    const val DEFAULT: String = "proxenos.internal"

    /** The SDK's own default endpoint path. `/`, `/api/mcp`, `/message` and `/sse` are 404. */
    const val PATH: String = "/mcp"
  }
}

/**
 * The catalog, served over a Unix domain socket (SPEC §3, §7). One of the two seams: this is
 * what the tunnel child dials into, and it carries the eleven and nothing else — the
 * management acts live behind the separate control socket, so raising an Access Level is not
 * something a conversation can attempt.
 *
 * [operations] arrives with its Origin already stamped in by the composition root (§9), so
 * nothing here can claim an Origin that is not its own.
 */
class McpEndpoint(
  private val operations: WorkspaceOperations,
  /**
   * Where the socket is bound. A stale file left behind by a Runtime the machine took — the
   * case that leaves an Operation Lost — needs no unlinking by the caller: Ktor unlinks and
   * rebinds over it, which was measured rather than assumed.
   */
  private val socket: Path,
  private val logicalHost: LogicalHost = LogicalHost(),
) {
  private var engine: EmbeddedServer<*, *>? = null

  /** Binds the socket and returns; the calls arrive on the engine's own coroutines. */
  fun start() {
    check(engine == null) { "This endpoint is already serving $socket" }
    socket.parent?.let { Files.createDirectories(it) }
    // `unixConnector` is engine configuration, not an application module: it belongs in the
    // `configure` block, and the trailing lambda is the module. Ktor logs
    // `Responding at unix://0.0.0.0:80`, which is cosmetic and not a TCP bind.
    val started = embeddedServer(CIO, configure = { unixConnector(socket.toString()) }) {
      mcpStreamableHttp(allowedHosts = logicalHost.allowedHosts) { server() }
    }
    engine = started
    started.start(wait = false)
  }

  fun stop(gracePeriodMillis: Long = 1_000, timeoutMillis: Long = 5_000) {
    engine?.stop(gracePeriodMillis, timeoutMillis)
    engine = null
  }

  /**
   * One MCP server per session, carrying the catalog as the core holds it. `listChanged` is
   * false and honest: the catalog is flat and static (§3), and a dynamic one would ride on
   * `tools/list_changed` reaching ChatGPT, which is not established.
   */
  private fun server(): Server {
    val server = Server(
      serverInfo = Implementation(name = SERVER_NAME, version = SERVER_VERSION),
      options = ServerOptions(
        capabilities = ServerCapabilities(tools = ServerCapabilities.Tools(listChanged = false)),
      ),
    )
    // Rendered from the catalog as data, entry by entry. A catalog entry added in the core
    // arrives here with nothing edited, which is the whole point of the core owning it.
    operations.catalog.forEach { spec ->
      server.addTool(name = spec.name, description = spec.description, inputSchema = spec.inputSchema()) { request ->
        answer(spec, request.arguments)
      }
    }
    return server
  }

  /**
   * An operation-level problem is a tool result, never a JSON-RPC protocol error (§5): the
   * model has to read it and act on it, and a protocol error is the client library's to
   * handle. Measured about this SDK, and why nothing here throws to make the distinction: a
   * handler that throws has its exception caught and returned as an `isError` result anyway,
   * and an unknown tool name is answered the same way before a handler is ever reached. So
   * the protocol-error lane is the SDK's own, for a malformed frame or an unknown method, and
   * this adapter never converts an Operation's outcome into one.
   */
  private suspend fun answer(spec: OperationSpec, arguments: JsonObject?): CallToolResult =
    when (val call = decode(spec, arguments)) {
      is ToolCall.Ready -> reply(call.operation, perform(call.operation))
      is ToolCall.Malformed -> text(call.complaint, isError = true)
      is ToolCall.Unbound -> text(
        "'${call.tool}' is in this connector's catalog, which is fixed when the connector is " +
          "created and cannot grow later, but the operation behind it is not in this build. " +
          NOTHING_ATTEMPTED,
        isError = true,
      )
    }

  /** Capture conversion, so the Operation's own result type survives the star projection. */
  private suspend fun <R> perform(operation: Operation<R>): Outcome<R> = operations.perform(operation)

  private companion object {
    const val SERVER_NAME = "proxenos"

    /** What the handshake reports. Packaging (SPEC §14 item 15) is what makes this a build fact. */
    const val SERVER_VERSION = "0.1.0"
  }
}
