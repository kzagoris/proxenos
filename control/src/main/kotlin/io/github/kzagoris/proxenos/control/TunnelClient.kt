package io.github.kzagoris.proxenos.control

import java.nio.file.Files
import java.nio.file.Path

/**
 * The Runtime's per-user state directory, resolved the one way the Runtime, `runtime register`
 * and setup's checks all find it: `state_dir` in `config.toml`, overridden by
 * `PROXENOS_STATE_DIR`, else `$XDG_STATE_HOME/proxenos`, else `~/.local/state/proxenos`.
 */
fun stateDirectory(toml: ConfigToml, environment: Map<String, String>): Path =
  toml.path("state_dir", environment, "PROXENOS_STATE_DIR")
    ?: (environment.absolutePath("XDG_STATE_HOME") ?: ConfigToml.home(environment).resolve(".local/state")).resolve(APP)

/**
 * Where the Runtime finds the `tunnel-client` it runs, so a frontend's check of it cannot come to
 * disagree with the Runtime's start.
 */
object TunnelClient {
  const val NAME = "tunnel-client"

  /** `tunnel_client` in `config.toml`, overridden by `PROXENOS_TUNNEL_CLIENT`: taken as named, unsearched. */
  fun configured(toml: ConfigToml, environment: Map<String, String>): Path? =
    toml.path("tunnel_client", environment, "PROXENOS_TUNNEL_CLIENT")

  /** Where the Runtime looks when none is configured: `PATH`, else the state directory's `tools/`. */
  fun tools(stateDirectory: Path): Path = stateDirectory.resolve("tools").resolve(NAME)

  /** The first executable `tunnel-client` on `PATH`, else in [tools]; null when there is none. */
  fun search(environment: Map<String, String>, stateDirectory: Path): Path? {
    // `+ listOf(...)`, not `+ path`: a Path is an Iterable of its own name segments.
    val candidates = environment["PATH"].orEmpty().split(':').filter { it.isNotEmpty() }.map { Path.of(it).resolve(NAME) } +
      listOf(tools(stateDirectory))
    return candidates.firstOrNull { Files.isRegularFile(it) && Files.isExecutable(it) }
  }
}
