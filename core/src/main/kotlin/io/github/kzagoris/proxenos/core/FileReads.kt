package io.github.kzagoris.proxenos.core

import io.github.kzagoris.proxenos.coreapi.*
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import kotlin.text.Charsets.UTF_8

/**
 * Bounded twice over: the line ceiling and the byte cap (SPEC §6.5), whichever binds first. The
 * reply states the file's total line count, so a model knows what it has not seen and can page
 * deliberately rather than guess.
 */
internal fun readFile(op: Operation.ReadFile, workspace: Workspace, file: Path): Outcome<FileContent> {
  val offset = op.offset ?: 1
  // A ceiling, not merely a default: a caller asking for more is held to it and told the
  // file's length, which is the paging the ceiling exists to make deliberate.
  val limit = minOf(op.limit ?: Operation.DEFAULT_LINE_CEILING, Operation.DEFAULT_LINE_CEILING)
  if (offset < 1) return Outcome.Failed(
    Failure.InvalidArgument("offset"), "offset is a 1-based line number, so it cannot be $offset.",
  )
  if ((op.limit ?: 1) < 1) return Outcome.Failed(
    Failure.InvalidArgument("limit"), "limit is a number of lines, so it cannot be ${op.limit}.",
  )
  if (!Files.isRegularFile(file)) return Outcome.Failed(
    Failure.NotAFile(workspace.name, op.path),
    if (Files.exists(file)) "'${op.path}' is not a regular file."
    else "'${op.path}' does not exist in Workspace '${workspace.name}'.",
  )

  val text = StringBuilder()
  val line = ByteArrayOutputStream()
  var used = 0
  var totalLines = 0
  var lineCount = 0
  var capped = false
  var lineOverflowed = false

  fun endLine(terminated: Boolean) {
    totalLines++
    if (totalLines >= offset && lineCount < limit && !capped) {
      // The cap is measured on what is returned, not on what was read: malformed bytes become
      // U+FFFD, which is three bytes where the input was one, and the cap is a transport bound.
      val decoded = String(line.toByteArray(), UTF_8) + if (terminated && !lineOverflowed) "\n" else ""
      val budget = Operation.OUTPUT_CAP_BYTES - used
      val size = decoded.utf8Size()
      if (size <= budget && !lineOverflowed) {
        text.append(decoded)
        used += size
        lineCount++
      } else {
        // One line longer than the whole cap — a minified bundle — is returned cut at a
        // character boundary rather than as nothing at all. Otherwise the read stops clean.
        if (lineCount == 0) {
          val prefix = decoded.takeUtf8(budget)
          text.append(prefix)
          used += prefix.utf8Size()
          if (prefix.isNotEmpty()) lineCount++
        }
        capped = true
      }
    }
    line.reset()
    lineOverflowed = false
  }

  // Byte-oriented on purpose: 0x0A never appears inside a multi-byte UTF-8 sequence, so lines
  // can be counted and cut without first decoding a file that may not decode.
  Files.newInputStream(file).use { input ->
    val chunk = ByteArray(1 shl 16)
    while (true) {
      val read = input.read(chunk)
      if (read < 0) break
      for (index in 0 until read) {
        val byte = chunk[index]
        // Refused in plain words rather than returned as replacement-character soup, which
        // would waste the cap and say nothing. The whole file is scanned for the line count
        // anyway, so this costs nothing and does not turn on where in the file the NUL sits.
        if (byte == ZERO) return Outcome.Failed(
          Failure.Binary(workspace.name, op.path),
          "'${op.path}' is a binary file, so there is no text to return.",
        )
        when {
          byte == NEWLINE -> endLine(terminated = true)
          // Past the cap this line cannot be returned whole, and a file with no newline in it
          // is not a reason to hold a gigabyte in memory.
          line.size() < Operation.OUTPUT_CAP_BYTES -> line.write(byte.toInt())
          else -> lineOverflowed = true
        }
      }
    }
  }
  if (line.size() > 0 || lineOverflowed) endLine(terminated = false)

  return Outcome.Ok(FileContent(op.path, text.toString(), offset, lineCount, totalLines, capped))
}
