package io.github.kzagoris.proxenos.runtime

import io.github.kzagoris.proxenos.mcp.LogicalHost
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import kotlin.time.Duration.Companion.seconds
import org.junit.jupiter.api.io.TempDir
import kotlin.test.*

class ConfigurationTest {
  @TempDir
  lateinit var home: Path

  private val configDirectory: Path get() = home.resolve(".config/proxenos")
  private val credentialsFile: Path get() = configDirectory.resolve("credentials")
  private val runtimeDirectory: Path get() = home.resolve("run")
  private val tools: Path get() = home.resolve("bin")

  /** A machine where the user has done the one thing asked of them, and nothing else. */
  private fun environment(vararg extra: Pair<String, String>): Map<String, String> =
    mapOf("HOME" to home.toString(), "XDG_RUNTIME_DIR" to runtimeDirectory.toString(), "PATH" to tools.toString()) + extra

  @BeforeTest
  fun `a tunnel-client on PATH`() {
    executable(tools.resolve("tunnel-client"))
  }

  @Test
  fun `a credentials file alone is enough, and everything else takes its default`() {
    credentials()
    val sourced = sourceConfiguration(environment())
    val config = sourced.config

    assertEquals("tunnel_abc", sourced.credentials.tunnelId)
    assertEquals("sk-secret", sourced.credentials.runtimeKey)
    assertEquals(home.resolve(".local/state/proxenos"), config.stateDirectory)
    assertEquals(runtimeDirectory.resolve("proxenos/mcp.sock"), config.mcpSocket)
    assertEquals(runtimeDirectory.resolve("proxenos/control.sock"), config.controlSocket)
    assertEquals(runtimeDirectory.resolve("proxenos/tunnel-health.sock"), config.tunnelHealthSocket)
    assertEquals(runtimeDirectory.resolve("proxenos/tunnel-health.url"), config.tunnelHealthUrlFile)
    assertEquals(tools.resolve("tunnel-client"), config.tunnelExecutable)
    assertEquals(LogicalHost(), sourced.logicalHost)
  }

  @Test
  fun `the XDG base directories are honoured`() {
    val configHome = home.resolve("elsewhere/config")
    credentials(configHome.resolve("proxenos/credentials"))
    val config = sourceConfiguration(
      environment("XDG_CONFIG_HOME" to configHome.toString(), "XDG_STATE_HOME" to home.resolve("elsewhere/state").toString()),
    ).config
    assertEquals(home.resolve("elsewhere/state/proxenos"), config.stateDirectory)
  }

  @Test
  fun `with no tunnel-client on PATH, the state directory's tools are used`() {
    credentials()
    val installed = executable(home.resolve(".local/state/proxenos/tools/tunnel-client"))
    val config = sourceConfiguration(environment("PATH" to home.resolve("empty").toString())).config
    assertEquals(installed, config.tunnelExecutable)
  }

  @Test
  fun `with no tunnel-client anywhere, it refuses and says where it looked`() {
    credentials()
    val refused = assertFailsWith<StartRefused> { sourceConfiguration(environment("PATH" to home.resolve("empty").toString())) }
    assertContains(refused.message!!, "PATH")
    assertContains(refused.message!!, home.resolve(".local/state/proxenos/tools/tunnel-client").toString())
  }

  // Scenario 13, first part.
  @Test
  fun `with no credentials file it refuses, naming the file and the wizard`() {
    val refused = assertFailsWith<StartRefused> { sourceConfiguration(environment()) }
    assertContains(refused.message!!, credentialsFile.toString())
    assertContains(refused.message!!, "wizard")
  }

  // Scenario 13, second part.
  @Test
  fun `a credentials file readable by anyone else is refused`() {
    for (mode in listOf("rw-r--r--", "rw-rw----", "rw-----w-", "rw----r--")) {
      credentials(mode = mode)
      val refused = assertFailsWith<StartRefused>(mode) { sourceConfiguration(environment()) }
      assertContains(refused.message!!, credentialsFile.toString())
      assertContains(refused.message!!, "chmod 600")
    }
    credentials(mode = "r--------")
    assertEquals("sk-secret", sourceConfiguration(environment()).credentials.runtimeKey)
  }

  @Test
  fun `the refusal names the mode it found`() {
    credentials(mode = "rw-r--r--")
    assertContains(assertFailsWith<StartRefused> { sourceConfiguration(environment()) }.message!!, "0644")
  }

  @Test
  fun `a credentials file missing a key is refused without echoing what it does hold`() {
    credentials(text = "TUNNEL_ID=tunnel_abc\n")
    val missing = assertFailsWith<StartRefused> { sourceConfiguration(environment()) }
    assertContains(missing.message!!, "RUNTIME_KEY")

    credentials(text = "TUNNEL_ID=tunnel_abc\nRUNTIME_KEY=sk-secret\nAPI_KEY=sk-other\n")
    val unknown = assertFailsWith<StartRefused> { sourceConfiguration(environment()) }
    assertFalse("sk-" in unknown.message!!, unknown.message)
  }

  @Test
  fun `the credential in the Runtime's own environment is never used`() {
    credentials()
    val sourced = sourceConfiguration(
      environment("CONTROL_PLANE_TUNNEL_ID" to "tunnel_from_shell", "CONTROL_PLANE_API_KEY" to "sk-from-shell"),
    )
    assertEquals("tunnel_abc", sourced.credentials.tunnelId)
    assertEquals("sk-secret", sourced.credentials.runtimeKey)
  }

  @Test
  fun `config toml overrides a path and a tunable`() {
    credentials()
    val socket = home.resolve("sockets/mcp.sock")
    configToml(
      """
      # Moved, because this machine's runtime directory is small.
      mcp_socket = "$socket"
      command_budget_seconds = 90
      command_concurrency = 2
      """,
    )
    val config = sourceConfiguration(environment()).config
    assertEquals(socket, config.mcpSocket)
    assertEquals(home.resolve("sockets/tunnel-health.sock"), config.tunnelHealthSocket, "still derived from the MCP socket")
    assertEquals(90.seconds, config.commandBudget)
    assertEquals(2, config.commandConcurrency)
  }

  /** The poll cycle the tunnel child is launched with is the one Connected is judged against (§8.4). */
  @Test
  fun `config toml sets the tunnel's poll wait, up to the ten minutes tunnel-client accepts`() {
    credentials()
    configToml("tunnel_poll_timeout_seconds = 120\ntunnel_poll_deadline_guardrail_seconds = 10")
    val config = sourceConfiguration(environment()).config
    assertEquals(120.seconds, config.tunnelPollTimeout)
    assertEquals(10.seconds, config.tunnelPollGuardrail)
    configToml("tunnel_poll_timeout_seconds = 601")
    assertContains(assertFailsWith<StartRefused> { sourceConfiguration(environment()) }.message!!, "tunnel_poll_timeout_seconds")
  }

  @Test
  fun `a credential in config toml is refused, and its value is not repeated`() {
    credentials()
    for (key in listOf("runtime_key", "tunnel_id", "RUNTIME_KEY", "CONTROL_PLANE_API_KEY", "api_key")) {
      configToml("""$key = "sk-in-the-wrong-file"""")
      val refused = assertFailsWith<StartRefused>(key) { sourceConfiguration(environment()) }
      assertContains(refused.message!!, credentialsFile.toString())
      assertFalse("sk-in-the-wrong-file" in refused.message!!, refused.message)
    }
  }

  @Test
  fun `config toml refuses what it does not recognise rather than ignoring a typo`() {
    credentials()
    configToml("comand_budget_seconds = 90")
    assertContains(assertFailsWith<StartRefused> { sourceConfiguration(environment()) }.message!!, "comand_budget_seconds")
    configToml("command_budget_seconds = \"ninety\"")
    assertFailsWith<StartRefused> { sourceConfiguration(environment()) }
    configToml("command_concurrency = 0")
    assertFailsWith<StartRefused> { sourceConfiguration(environment()) }
    configToml("state_dir = \"relative/path\"")
    assertFailsWith<StartRefused> { sourceConfiguration(environment()) }
  }

  @Test
  fun `environment variables override paths, over config toml`() {
    credentials()
    configToml("""state_dir = "${home.resolve("from-toml")}"""")
    val config = sourceConfiguration(
      environment(
        "PROXENOS_STATE_DIR" to home.resolve("from-env").toString(),
        "PROXENOS_CONTROL_SOCKET" to home.resolve("control.sock").toString(),
      ),
    ).config
    assertEquals(home.resolve("from-env"), config.stateDirectory)
    assertEquals(home.resolve("control.sock"), config.controlSocket)
  }

  @Test
  fun `the logical host override moves both ends of the coupling at once`() {
    credentials()
    configToml("""logical_host = "dashboard.example.internal"""")
    val host = sourceConfiguration(environment()).logicalHost
    assertEquals("http://dashboard.example.internal/mcp", host.url)
    assertEquals(listOf("dashboard.example.internal"), host.allowedHosts)
  }

  @Test
  fun `a logical host carrying a scheme or a path is refused`() {
    credentials()
    for (value in listOf("https://dashboard.internal", "dashboard.internal/mcp", "")) {
      configToml("""logical_host = "$value"""")
      assertFailsWith<StartRefused>(value) { sourceConfiguration(environment()) }
    }
  }

  @Test
  fun `a socket path too long for the kernel is refused before anything tries to bind it`() {
    credentials()
    val deep = runtimeDirectory.resolve("d".repeat(120))
    val refused = assertFailsWith<StartRefused> { sourceConfiguration(environment("XDG_RUNTIME_DIR" to deep.toString())) }
    assertContains(refused.message!!, deep.resolve("proxenos/mcp.sock").toString())
    assertContains(refused.message!!, "108")
  }

  @Test
  fun `with no runtime directory and no socket override it refuses and says why`() {
    credentials()
    val refused = assertFailsWith<StartRefused> { sourceConfiguration(environment() - "XDG_RUNTIME_DIR") }
    assertContains(refused.message!!, "XDG_RUNTIME_DIR")
  }

  private fun credentials(
    file: Path = credentialsFile,
    text: String = "TUNNEL_ID=tunnel_abc\nRUNTIME_KEY=sk-secret\n",
    mode: String = "rw-------",
  ) {
    Files.createDirectories(file.parent)
    Files.writeString(file, text)
    Files.setPosixFilePermissions(file, PosixFilePermissions.fromString(mode))
  }

  private fun configToml(text: String) {
    Files.createDirectories(configDirectory)
    Files.writeString(configDirectory.resolve("config.toml"), text.trimIndent())
  }

  private fun executable(file: Path): Path {
    Files.createDirectories(file.parent)
    Files.writeString(file, "#!/bin/sh\n")
    Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rwx------"))
    return file
  }
}
