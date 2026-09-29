package io.github.kzagoris.proxenos.runtime

import java.nio.file.Path

/** One value from `config.toml`, remembered with the line it came from so a refusal can point at it. */
internal sealed interface TomlValue {
  val line: Int

  data class Text(val value: String, override val line: Int) : TomlValue

  data class Integer(val value: Long, override val line: Int) : TomlValue
}

/**
 * The part of TOML `config.toml` needs, and no more: flat `key = value` lines, where a value is a
 * basic or literal string or an integer, and `#` comments. Every override is one key and one
 * scalar, so a TOML library would be a dependency carried for a file that never nests.
 *
 * Anything outside that subset is refused with its line number rather than skipped — a tunable
 * the Runtime quietly did not read is worse than one it refused. A refusal never quotes the
 * value, because the one thing that must never be pasted from here is a key someone put in the
 * wrong file — which is refused by name before its value is even parsed.
 */
internal fun parseConfigToml(text: String, file: Path, credentialsFile: Path): Map<String, TomlValue> {
  val values = linkedMapOf<String, TomlValue>()
  text.lines().forEachIndexed { index, raw ->
    val number = index + 1
    fun refuse(why: String): Nothing = throw StartRefused("$file line $number: $why")

    val line = raw.trim()
    if (line.isEmpty() || line.startsWith("#")) return@forEachIndexed
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
      "$key is not a quoted string or a whole number.",
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
      if (!Regex("[+-]?[0-9](_?[0-9])*").matches(number)) return null
      return number.replace("_", "").toLongOrNull()?.let { TomlValue.Integer(it, line) }
    }
  }
}

/** What may follow a closed string on its line: nothing, or a comment. */
private fun isComment(rest: String): Boolean = rest.isBlank() || rest.trimStart().startsWith("#")
