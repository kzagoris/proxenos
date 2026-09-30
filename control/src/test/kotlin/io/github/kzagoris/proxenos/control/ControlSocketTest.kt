package io.github.kzagoris.proxenos.control

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*
import org.junit.jupiter.api.io.TempDir

/**
 * The one control-socket resolver, as every frontend and the Runtime call it: defaults <
 * `config.toml` < environment < `--control-socket`.
 */
class ControlSocketTest {
  @TempDir
  lateinit var home: Path

  private val configToml: Path get() = home.resolve(".config/proxenos/config.toml")

  private fun environment(vararg extra: Pair<String, String>): Map<String, String> =
    mapOf("HOME" to home.toString(), "XDG_RUNTIME_DIR" to home.resolve("run").toString()) + extra

  private fun configToml(text: String) {
    Files.createDirectories(configToml.parent)
    Files.writeString(configToml, text)
  }

  @Test
  fun `each source overrides the one below it`() {
    assertEquals(home.resolve("run/proxenos/control.sock"), ControlSocket.resolve(environment()))

    configToml("""control_socket = "${home.resolve("from-toml.sock")}"""")
    assertEquals(home.resolve("from-toml.sock"), ControlSocket.resolve(environment()))

    val fromEnvironment = environment("PROXENOS_CONTROL_SOCKET" to home.resolve("from-env.sock").toString())
    assertEquals(home.resolve("from-env.sock"), ControlSocket.resolve(fromEnvironment))

    assertEquals(home.resolve("from-flag.sock"), ControlSocket.resolve(fromEnvironment, flag = home.resolve("from-flag.sock")))
  }

  @Test
  fun `config toml is found under XDG_CONFIG_HOME, and a tilde in either source is the home directory`() {
    val configHome = home.resolve("elsewhere")
    Files.createDirectories(configHome.resolve("proxenos"))
    Files.writeString(configHome.resolve("proxenos/config.toml"), """control_socket = "~/sockets/control.sock"""")
    assertEquals(home.resolve("sockets/control.sock"), ControlSocket.resolve(environment("XDG_CONFIG_HOME" to configHome.toString())))
    assertEquals(home.resolve("from-env.sock"), ControlSocket.resolve(environment("PROXENOS_CONTROL_SOCKET" to "~/from-env.sock")))
  }

  @Test
  fun `with no runtime directory and nothing naming the socket it refuses and says where to name it`() {
    val refused = assertFailsWith<ConfigRefused> { ControlSocket.resolve(environment() - "XDG_RUNTIME_DIR") }
    assertContains(refused.message!!, "XDG_RUNTIME_DIR")
    assertContains(refused.message!!, configToml.toString())
  }

  @Test
  fun `a control_socket the Runtime would refuse is refused with the Runtime's words, unless the flag settles it`() {
    configToml("control_socket = relative.sock")
    assertContains(assertFailsWith<ConfigRefused> { ControlSocket.resolve(environment()) }.message!!, "$configToml line 1")
    assertEquals(home.resolve("flag.sock"), ControlSocket.resolve(environment(), flag = home.resolve("flag.sock")))
  }

  @Test
  fun `a mistake elsewhere in config toml does not keep a frontend from the socket it names`() {
    configToml("command_budget_seconds = true\ncontrol_socket = \"${home.resolve("from-toml.sock")}\"\n[table]")
    assertEquals(home.resolve("from-toml.sock"), ControlSocket.resolve(environment()))
  }
}
