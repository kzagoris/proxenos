package io.github.kzagoris.proxenos.runtime

import io.github.kzagoris.proxenos.core.TunnelCredentials
import java.io.IOException
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermission
import java.nio.file.attribute.PosixFilePermission.*

/**
 * Why the Runtime will not start, worded for the person who has to fix it. The Runtime has no
 * terminal when a frontend starts it, so this is said once, on the way out, and never asked as
 * a question: a background host that blocks on stdin is a background host that hangs.
 */
class StartRefused(message: String) : Exception(message)

/** What the setup wizard is called wherever a refusal has to name it. */
internal const val WIZARD = "the setup wizard (bin/wizard in the distribution, scripts/wizard in a checkout)"

private const val TUNNEL_ID = "TUNNEL_ID"
private const val RUNTIME_KEY = "RUNTIME_KEY"

/** Only the owner's bits. Anything in the group or other columns lets someone else read the key. */
private val WIDER_THAN_OWNER = setOf(GROUP_READ, GROUP_WRITE, GROUP_EXECUTE, OTHERS_READ, OTHERS_WRITE, OTHERS_EXECUTE)

/**
 * Reads the credentials file the wizard wrote, refusing it if it is missing or if its mode lets
 * anyone but the owner in. The mode is checked **before** the file is read: a key that other
 * users could already read is not made safer by the Runtime reading it too, but refusing is what
 * gets the mode fixed.
 *
 * A line the Runtime does not recognise is refused rather than skipped, and nothing a line holds
 * is ever quoted back — a refusal is printed where a key should never be.
 */
internal fun readCredentials(file: Path): TunnelCredentials {
  val permissions = try {
    Files.getPosixFilePermissions(file)
  } catch (_: NoSuchFileException) {
    throw StartRefused(
      "No credentials file at $file. The Runtime will not start without one, and it is the only " +
        "thing it needs from you. Run $WIZARD: it walks the Platform dashboard and writes the " +
        "tunnel ID and runtime key there at mode 0600.",
    )
  } catch (_: UnsupportedOperationException) {
    throw StartRefused("$file is on a filesystem with no POSIX permissions, so nothing can say who else may read the key in it.")
  }
  if (permissions.any { it in WIDER_THAN_OWNER }) {
    throw StartRefused(
      "$file is mode ${permissions.octal()}, which lets other users on this machine read the " +
        "runtime key. The Runtime refuses to start until only you can: chmod 600 $file",
    )
  }

  val lines = try {
    Files.readAllLines(file)
  } catch (unreadable: IOException) {
    throw StartRefused("$file could not be read: ${unreadable.javaClass.simpleName}.")
  }
  val values = mutableMapOf<String, String>()
  lines.forEachIndexed { index, raw ->
    val line = raw.trim()
    if (line.isEmpty() || line.startsWith("#")) return@forEachIndexed
    val key = line.substringBefore('=', missingDelimiterValue = "").trim()
    if (key != TUNNEL_ID && key != RUNTIME_KEY) {
      throw StartRefused(
        "$file line ${index + 1} is not a $TUNNEL_ID= or $RUNTIME_KEY= line. The file carries " +
          "those two and nothing else; re-run $WIZARD to rewrite it.",
      )
    }
    if (key in values) throw StartRefused("$file sets $key twice, and the Runtime will not guess which one is meant. Re-run $WIZARD to rewrite it.")
    values[key] = line.substringAfter('=').trim()
  }
  fun required(key: String): String = values[key]?.takeIf { it.isNotEmpty() }
    ?: throw StartRefused("$file has no $key. Re-run $WIZARD to write both the tunnel ID and the runtime key.")
  return TunnelCredentials(required(TUNNEL_ID), required(RUNTIME_KEY))
}

private fun Set<PosixFilePermission>.octal(): String {
  var mode = 0
  PosixFilePermission.entries.forEach { if (it in this) mode = mode or (1 shl (8 - it.ordinal)) }
  return "0" + mode.toString(8).padStart(3, '0')
}
