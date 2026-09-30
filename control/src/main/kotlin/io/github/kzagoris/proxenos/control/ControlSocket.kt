package io.github.kzagoris.proxenos.control

import java.nio.file.Path

/**
 * Where the control socket is, resolved the one way the Runtime binds it and every frontend dials
 * it. Precedence, lowest first: `$XDG_RUNTIME_DIR/proxenos/control.sock`, then `control_socket`
 * in `config.toml`, then `PROXENOS_CONTROL_SOCKET`, then a frontend's `--control-socket`. A
 * frontend started from a menu and one started from a shell therefore cannot reach different
 * sockets.
 */
object ControlSocket {
  const val VARIABLE = "PROXENOS_CONTROL_SOCKET"
  const val KEY = "control_socket"

  /**
   * For a frontend. `config.toml` is read only when neither [flag] nor the environment settles
   * it, and then only its [KEY] line, so a file the Runtime would refuse elsewhere does not stop
   * a frontend reaching a Runtime already running.
   */
  fun resolve(environment: Map<String, String>, flag: Path? = null): Path =
    flag?.toAbsolutePath()?.normalize()
      ?: resolve(if (!environment[VARIABLE].isNullOrEmpty()) ConfigToml.unread(environment) else ConfigToml.read(environment, only = KEY), environment)

  /** For the Runtime, which reads [toml] for everything else too. */
  fun resolve(toml: ConfigToml, environment: Map<String, String>): Path =
    toml.path(KEY, environment, VARIABLE)
      ?: environment.absolutePath("XDG_RUNTIME_DIR")?.resolve(APP)?.resolve("control.sock")?.normalize()
      ?: throw ConfigRefused(
        "XDG_RUNTIME_DIR is not set, and the control socket is in it by default. Start from a login " +
          "session, or set $KEY in ${ConfigToml.file(environment)}.",
      )
}

/** The directory name under every XDG base directory. */
internal const val APP = "proxenos"

internal fun Map<String, String>.absolutePath(key: String): Path? =
  this[key]?.takeIf { it.isNotEmpty() }?.let(Path::of)?.takeIf { it.isAbsolute }
