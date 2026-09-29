package io.github.kzagoris.proxenos.runtime

import io.github.kzagoris.proxenos.core.RuntimeConfig
import io.github.kzagoris.proxenos.core.TunnelCredentials
import io.github.kzagoris.proxenos.mcp.LogicalHost
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * What `main()` starts from: the core's [RuntimeConfig], the logical host the MCP server and
 * the tunnel child are both derived from, and the credential, which travels beside the config
 * and never inside it.
 */
data class Configuration(
  val config: RuntimeConfig,
  val logicalHost: LogicalHost,
  val credentials: TunnelCredentials,
)

/**
 * Sources the Runtime's configuration (SPEC §11), and **the user supplies exactly one thing**:
 * the credentials file. Everything else has a default.
 *
 * Precedence, lowest first: the defaults in §11's table, then `config.toml` beside the
 * credentials file, then environment variables. `config.toml` may override any path or tunable
 * and never the credential. The environment may override paths only, and never the credential
 * either — a key in a shell profile or a unit file is a key in a backup — so
 * `CONTROL_PLANE_API_KEY` in the Runtime's own environment is not read here at all.
 *
 * [environment] is taken rather than reached for, so a test can build a machine without
 * touching the one it runs on.
 */
fun sourceConfiguration(environment: Map<String, String>): Configuration {
  val home = environment.absolutePath("HOME") ?: Path.of(System.getProperty("user.home"))
  val configDirectory = (environment.absolutePath("XDG_CONFIG_HOME") ?: home.resolve(".config")).resolve(APP)
  // Read first, and from the one place the wizard writes: nothing overrides where it lives.
  val credentialsFile = configDirectory.resolve("credentials")
  val credentials = readCredentials(credentialsFile)

  val tomlFile = configDirectory.resolve("config.toml")
  val toml = TomlSettings(tomlFile, readConfigToml(tomlFile, credentialsFile), home)

  fun path(tomlKey: String, environmentKey: String, default: () -> Path?): Path? =
    toml.path(tomlKey, environment, environmentKey) ?: default()

  val stateDirectory = stateDirectory(environment, home, toml)
  val runtimeDirectory = environment.absolutePath("XDG_RUNTIME_DIR")?.resolve(APP)
  fun socket(name: String, tomlKey: String, environmentKey: String): Path =
    path(tomlKey, environmentKey) { runtimeDirectory?.resolve(name) } ?: throw StartRefused(
      "XDG_RUNTIME_DIR is not set, and it is where the Runtime puts its sockets by default. Start " +
        "it from a login session, or set mcp_socket and control_socket in $tomlFile.",
    )
  val mcpSocket = socket("mcp.sock", "mcp_socket", "PROXENOS_MCP_SOCKET")
  // The tunnel child is told the MCP socket inside a comma-separated flag (SPEC §11.2), so a
  // comma in the path would be read as the start of another field.
  if (',' in mcpSocket.toString()) throw StartRefused("The MCP socket path $mcpSocket contains a comma, which the tunnel child's --mcp.server-url flag cannot carry.")
  val controlSocket = socket("control.sock", "control_socket", "PROXENOS_CONTROL_SOCKET")
  val tunnelExecutable = path("tunnel_client", "PROXENOS_TUNNEL_CLIENT") { null }
    ?.also { if (!Files.isExecutable(it)) throw StartRefused("The tunnel executable $it does not exist or cannot be run.") }
    ?: findTunnelClient(environment["PATH"].orEmpty(), stateDirectory.resolve("tools"))

  val defaults = RuntimeConfig(stateDirectory, mcpSocket, controlSocket, tunnelExecutable = tunnelExecutable)
  val config = defaults.copy(
    tunnelHealthSocket = toml.path("tunnel_health_socket") ?: defaults.tunnelHealthSocket,
    tunnelHealthUrlFile = toml.path("tunnel_health_url_file") ?: defaults.tunnelHealthUrlFile,
    commandBudget = toml.duration("command_budget_seconds") { it.seconds } ?: defaults.commandBudget,
    killGrace = toml.duration("kill_grace_seconds") { it.seconds } ?: defaults.killGrace,
    commandConcurrency = toml.count("command_concurrency") ?: defaults.commandConcurrency,
    deliveryRetention = toml.duration("delivery_retention_minutes") { it.minutes } ?: defaults.deliveryRetention,
    deliveryRecordQuota = toml.count("delivery_record_quota") ?: defaults.deliveryRecordQuota,
    deliveryKeyQuota = toml.count("delivery_key_quota") ?: defaults.deliveryKeyQuota,
    activityRetention = toml.duration("activity_retention_days") { it.days } ?: defaults.activityRetention,
    tunnelPollTimeout = toml.duration("tunnel_poll_timeout_seconds") { it.seconds }
      ?.also { if (it > MAX_POLL_TIMEOUT) throw StartRefused("tunnel_poll_timeout_seconds in $tomlFile is $it; tunnel-client accepts at most $MAX_POLL_TIMEOUT.") }
      ?: defaults.tunnelPollTimeout,
    tunnelPollGuardrail = toml.duration("tunnel_poll_deadline_guardrail_seconds") { it.seconds } ?: defaults.tunnelPollGuardrail,
  )
  listOf(config.mcpSocket, config.controlSocket, config.tunnelHealthSocket).forEach(::refuseUnbindable)
  val logicalHost = toml.text("logical_host")?.let { logicalHost(it, tomlFile) } ?: LogicalHost()
  toml.refuseUnread()
  return Configuration(config, logicalHost, credentials)
}

/**
 * The state directory and nothing else, for `runtime register` (Register.kt): registering a
 * Workspace touches only the registry, so it needs neither the credential nor a tunnel-client.
 * It resolves exactly as [sourceConfiguration] does, so the Runtime reads the registry written.
 */
fun sourceStateDirectory(environment: Map<String, String>): Path {
  val home = environment.absolutePath("HOME") ?: Path.of(System.getProperty("user.home"))
  val configDirectory = (environment.absolutePath("XDG_CONFIG_HOME") ?: home.resolve(".config")).resolve(APP)
  val tomlFile = configDirectory.resolve("config.toml")
  return stateDirectory(environment, home, TomlSettings(tomlFile, readConfigToml(tomlFile, configDirectory.resolve("credentials")), home))
}

private fun stateDirectory(environment: Map<String, String>, home: Path, toml: TomlSettings): Path =
  toml.path("state_dir", environment, "PROXENOS_STATE_DIR")
    ?: (environment.absolutePath("XDG_STATE_HOME") ?: home.resolve(".local/state")).resolve(APP)

/** The directory name under every XDG base directory. */
private const val APP = "proxenos"

private const val TUNNEL_CLIENT = "tunnel-client"

/** tunnel-client's own cap on its long-poll wait (ADR 0005). */
private val MAX_POLL_TIMEOUT = 10.minutes

/** A hostname and nothing else: no scheme, no port, no path. */
private val HOSTNAME = Regex("[A-Za-z0-9]([A-Za-z0-9-]*[A-Za-z0-9])?(\\.[A-Za-z0-9]([A-Za-z0-9-]*[A-Za-z0-9])?)*")

/**
 * The one value both ends of the tunnel coupling are derived from (SPEC §11.2). An override
 * replaces the host and nothing else: the scheme stays `http://`, because the tunnel child
 * terminates no TLS on the way to a Unix socket.
 */
private fun logicalHost(value: String, tomlFile: Path): LogicalHost {
  if (!HOSTNAME.matches(value)) throw StartRefused(
    "logical_host in $tomlFile must be a bare hostname. The Runtime builds http://<host>/mcp " +
      "from it for the tunnel child and allows exactly <host> on the MCP server, so a scheme, " +
      "port or path here would be the one thing that makes the two disagree.",
  )
  return LogicalHost(value)
}

/**
 * Linux's `sun_path` is 108 bytes including the terminating NUL. A longer socket path is not
 * refused until something binds it, and then it arrives as a stack trace from inside Ktor.
 */
private fun refuseUnbindable(socket: Path) {
  if (socket.toString().toByteArray().size >= SUN_PATH_BYTES) throw StartRefused(
    "The socket path $socket is longer than the $SUN_PATH_BYTES bytes Linux allows a Unix socket " +
      "path, so it cannot be bound. Move it somewhere shorter in config.toml.",
  )
}

private const val SUN_PATH_BYTES = 108

/** SPEC §11: `tunnel-client` on `PATH`, else the state directory's `tools/`. */
private fun findTunnelClient(searchPath: String, tools: Path): Path {
  // `+ listOf(...)`, not `+ path`: a Path is an Iterable of its own name segments.
  val candidates = searchPath.split(':').filter { it.isNotEmpty() }.map { Path.of(it).resolve(TUNNEL_CLIENT) } +
    listOf(tools.resolve(TUNNEL_CLIENT))
  return candidates.firstOrNull { Files.isRegularFile(it) && Files.isExecutable(it) } ?: throw StartRefused(
    "No $TUNNEL_CLIENT on PATH and none at ${tools.resolve(TUNNEL_CLIENT)}. Install the official " +
      "executable in either place, or name it with tunnel_client in config.toml.",
  )
}

private fun readConfigToml(file: Path, credentialsFile: Path): Map<String, TomlValue> = try {
  parseConfigToml(Files.readString(file), file, credentialsFile)
} catch (_: NoSuchFileException) {
  emptyMap()
}

private fun Map<String, String>.absolutePath(key: String): Path? =
  this[key]?.takeIf { it.isNotEmpty() }?.let(Path::of)?.takeIf { it.isAbsolute }

/**
 * `config.toml`, read key by key. Each key is taken once by name, and whatever is left at the
 * end is a key nothing took — a typo, most likely, and refused rather than ignored.
 */
private class TomlSettings(private val file: Path, values: Map<String, TomlValue>, private val home: Path) {
  private val unread = values.toMutableMap()

  fun text(key: String): String? = when (val value = unread.remove(key)) {
    null -> null
    is TomlValue.Text -> value.value
    is TomlValue.Integer -> refuse(value, "$key must be a quoted string.")
  }

  fun path(key: String): Path? = text(key)?.let { absolute(it, "$key in $file") }

  /**
   * [key] from the file, overridden by [environmentKey]. The file's value is taken even when the
   * environment wins, so it is not later mistaken for a key nothing reads.
   */
  fun path(key: String, environment: Map<String, String>, environmentKey: String): Path? {
    val fromToml = path(key)
    val fromEnvironment = environment[environmentKey]?.takeIf { it.isNotEmpty() }
      ?.let { absolute(it, "The environment variable $environmentKey") }
    return fromEnvironment ?: fromToml
  }

  fun count(key: String): Int? = when (val value = unread.remove(key)) {
    null -> null
    is TomlValue.Integer -> value.value.takeIf { it in 1..Int.MAX_VALUE }?.toInt()
      ?: refuse(value, "$key must be a whole number of at least 1.")
    is TomlValue.Text -> refuse(value, "$key must be a whole number, not a quoted string.")
  }

  fun duration(key: String, unit: (Int) -> Duration): Duration? = count(key)?.let(unit)

  fun absolute(value: String, where: String): Path {
    val expanded = if (value == "~" || value.startsWith("~/")) home.resolve(value.removePrefix("~").removePrefix("/")) else Path.of(value)
    if (!expanded.isAbsolute) throw StartRefused("$where must be an absolute path; it is resolved before anything knows what directory the Runtime was started in.")
    return expanded.normalize()
  }

  fun refuseUnread() {
    val (key, value) = unread.entries.firstOrNull() ?: return
    refuse(value, "$key is not a setting the Runtime reads. It reads: ${KNOWN.joinToString()}.")
  }

  private fun refuse(value: TomlValue, why: String): Nothing = throw StartRefused("$file line ${value.line}: $why")

  companion object {
    val KNOWN = listOf(
      "state_dir", "mcp_socket", "control_socket", "tunnel_health_socket", "tunnel_health_url_file",
      "tunnel_client", "logical_host", "command_budget_seconds", "kill_grace_seconds",
      "command_concurrency", "delivery_retention_minutes", "delivery_record_quota",
      "delivery_key_quota", "activity_retention_days", "tunnel_poll_timeout_seconds",
      "tunnel_poll_deadline_guardrail_seconds",
    )
  }
}
