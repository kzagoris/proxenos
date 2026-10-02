package io.github.kzagoris.proxenos.control

import java.io.IOException
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import kotlin.time.Duration

/** `config.toml` or the environment names something that cannot be used, in words to show as they are. */
class ConfigRefused(message: String) : Exception(message)


/**
 * `config.toml`, read key by key. The Runtime reads its settings and a frontend only its own,
 * which is why it lives here, in the module both ends reach. Each key is taken
 * once by name, and whatever is left at the end is a key nothing took — a typo, most likely, and
 * refused rather than ignored.
 */
class ConfigToml private constructor(val file: Path, values: Map<String, TomlValue>, private val home: Path) {
  private val unread = values.toMutableMap()

  fun text(key: String): String? = when (val value = unread.remove(key)) {
    null -> null
    is TomlValue.Text -> value.value
    is TomlValue.Integer -> refuse(value, "$key must be a quoted string.")
    is TomlValue.Decimal -> refuse(value, "$key must be a quoted string.")
  }

  fun number(key: String): Float? = when (val value = unread.remove(key)) {
    null -> null
    is TomlValue.Integer -> value.value.toFloat()
    is TomlValue.Decimal -> value.value.toFloat()
    is TomlValue.Text -> refuse(value, "$key must be a number, not a quoted string.")
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
    is TomlValue.Decimal -> refuse(value, "$key must be a whole number, not a decimal.")
  }

  fun duration(key: String, unit: (Int) -> Duration): Duration? = count(key)?.let(unit)

  private fun absolute(value: String, where: String): Path {
    val expanded = if (value == "~" || value.startsWith("~/")) home.resolve(value.removePrefix("~").removePrefix("/")) else Path.of(value)
    if (!expanded.isAbsolute) throw ConfigRefused("$where must be an absolute path; it is resolved before anything knows what directory the Runtime was started in.")
    return expanded.normalize()
  }

  /** Refuses the first key nothing took, naming the [known] ones. */
  fun refuseUnread(known: List<String>) {
    val (key, value) = unread.entries.firstOrNull() ?: return
    refuse(value, "$key is not a setting the Runtime reads. It reads: ${known.joinToString()}.")
  }

  private fun refuse(value: TomlValue, why: String): Nothing = throw ConfigRefused("$file line ${value.line}: $why")

  companion object {
    /** This machine's settings with the file left unread, for when the environment has already decided. */
    internal fun unread(environment: Map<String, String>): ConfigToml = ConfigToml(file(environment), emptyMap(), home(environment))

    fun home(environment: Map<String, String>): Path =
      environment.absolutePath("HOME") ?: Path.of(System.getProperty("user.home"))

    /** `$XDG_CONFIG_HOME/proxenos`, else `~/.config/proxenos`: where the wizard writes. */
    fun directory(environment: Map<String, String>): Path =
      (environment.absolutePath("XDG_CONFIG_HOME") ?: home(environment).resolve(".config")).resolve(APP)

    fun file(environment: Map<String, String>): Path = directory(environment).resolve("config.toml")

    /** The file, or defaults when absent. [only] selects a frontend's key; [ignored] skips another surface's. */
    fun read(environment: Map<String, String>, only: String? = null, ignored: String? = null): ConfigToml {
      val file = file(environment)
      val values = try {
        parseConfigToml(Files.readString(file), file, directory(environment).resolve("credentials"), only, ignored)
      } catch (_: NoSuchFileException) {
        emptyMap()
      } catch (unreadable: IOException) {
        throw ConfigRefused("$file cannot be read: ${unreadable.message ?: unreadable::class.simpleName}.")
      }
      return ConfigToml(file, values, home(environment))
    }
  }
}

/** One value from `config.toml`, remembered with the line it came from so a refusal can point at it. */
internal sealed interface TomlValue {
  val line: Int

  data class Text(val value: String, override val line: Int) : TomlValue

  data class Integer(val value: Long, override val line: Int) : TomlValue

  data class Decimal(val value: Double, override val line: Int) : TomlValue
}

/**
 * The part of TOML `config.toml` needs, and no more: flat `key = value` lines, where a value is a
 * basic or literal string or a number, and `#` comments. Every override is one key and one
 * scalar, so a TOML library would be a dependency carried for a file that never nests.
 *
 * Anything outside that subset is refused with its line number rather than skipped — a tunable
 * the Runtime quietly did not read is worse than one it refused. A refusal never quotes the
 * value, because the one thing that must never be pasted from here is a key someone put in the
 * wrong file — which is refused by name before its value is even parsed.
 *
 * With [only], every line but that key's is skipped unread: a frontend wants one setting, and a
 * mistake elsewhere in the file must not keep it from reaching a Runtime already running.
 */
internal fun parseConfigToml(text: String, file: Path, credentialsFile: Path, only: String? = null, ignored: String? = null): Map<String, TomlValue> {
  val values = linkedMapOf<String, TomlValue>()
  text.lines().forEachIndexed { index, raw ->
    val number = index + 1
    fun refuse(why: String): Nothing = throw ConfigRefused("$file line $number: $why")

    val line = raw.trim()
    if (line.isEmpty() || line.startsWith("#")) return@forEachIndexed
    if (only != null && line.substringBefore('=').trim() != only) return@forEachIndexed
    if (ignored != null && line.substringBefore('=').trim() == ignored) return@forEachIndexed
    if (line.startsWith("[")) refuse("tables are not used; every setting is a top-level key = value.")
    val key = line.substringBefore('=', missingDelimiterValue = "").trim()
    if (key.isEmpty() || !KEY.matches(key)) refuse("expected key = value.")
    if (key.lowercase() in CREDENTIAL_KEYS) refuse(
      "$key is a credential, and this file never carries one: it is meant to be diffed, copied " +
        "between machines and pasted into bug reports. Remove the line, and revoke that key if " +
        "this file has been shared. The credential belongs in $credentialsFile.",
    )
    if (key in values) refuse("$key is set twice.")
    val value = parseValue(line.substringAfter('=').trim(), number) ?: refuse(
      "$key is not a quoted string or a number.",
    )
    values[key] = value
  }
  return values
}

/**
 * Every name a credential might plausibly be written under here, whatever the case: the
 * credentials file's own two, the tunnel child's environment names, and the obvious guesses.
 */
private val CREDENTIAL_KEYS = setOf(
  "tunnel_id", "runtime_key", "api_key", "control_plane_tunnel_id", "control_plane_api_key",
)

private val KEY = Regex("[A-Za-z0-9_-]+")

private fun parseValue(text: String, line: Int): TomlValue? {
  when {
    text.startsWith('"') -> {
      val builder = StringBuilder()
      var index = 1
      while (index < text.length) {
        val character = text[index++]
        when {
          character == '"' -> return if (isComment(text.substring(index))) TomlValue.Text(builder.toString(), line) else null
          character == '\\' && index < text.length -> when (text[index++]) {
            '"' -> builder.append('"')
            '\\' -> builder.append('\\')
            'n' -> builder.append('\n')
            't' -> builder.append('\t')
            else -> return null
          }
          else -> builder.append(character)
        }
      }
      return null
    }
    text.startsWith('\'') -> {
      val end = text.indexOf('\'', 1)
      if (end < 0 || !isComment(text.substring(end + 1))) return null
      return TomlValue.Text(text.substring(1, end), line)
    }
    else -> {
      val number = text.substringBefore('#').trim()
      if (Regex("[+-]?[0-9](_?[0-9])*").matches(number))
        return number.replace("_", "").toLongOrNull()?.let { TomlValue.Integer(it, line) }
      if (!DECIMAL.matches(number)) return null
      return number.replace("_", "").toDoubleOrNull()?.let { TomlValue.Decimal(it, line) }
    }
  }
}

private val DECIMAL = Regex("[+-]?(0|[1-9](_?[0-9])*)(\\.[0-9](_?[0-9])*)?([eE][+-]?[0-9](_?[0-9])*)?")

/** What may follow a closed string on its line: nothing, or a comment. */
private fun isComment(rest: String): Boolean = rest.isBlank() || rest.trimStart().startsWith("#")
