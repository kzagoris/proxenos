package io.github.kzagoris.proxenos.core

import io.github.kzagoris.proxenos.coreapi.*
import kotlinx.serialization.json.JsonPrimitive
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import kotlin.text.Charsets.UTF_8

/** Lines are counted to EOF, but only one bounded slice is retained. No file snapshot is promised. */
internal fun readFile(
  op: Operation.ReadFile,
  workspace: Workspace,
  file: Path,
  byteCap: Int = Operation.OUTPUT_CAP_BYTES,
): Outcome<FileContent> {
  val offset = op.offset ?: 1
  val limit = minOf(op.limit ?: Operation.DEFAULT_LINE_CEILING, Operation.DEFAULT_LINE_CEILING)
  if (offset < 1) return Outcome.Failed(Failure.InvalidArgument("offset"), "offset is a 1-based line number, so it cannot be $offset.")
  if (limit < 1) return Outcome.Failed(Failure.InvalidArgument("limit"), "limit is a number of lines, so it cannot be ${op.limit}.")
  if (op.byteOffset < 0) return Outcome.Failed(Failure.InvalidArgument("byte_offset"), "byte_offset cannot be negative.")
  if (!Files.isRegularFile(file)) return Outcome.Failed(
    Failure.NotAFile(workspace.name, op.path),
    if (Files.exists(file)) "'${op.path}' is not a regular file." else "'${op.path}' does not exist in Workspace '${workspace.name}'.",
  )
  // Reserve room for metadata and the second, readable representation in MCP.
  val textBudget = minOf(byteCap, maxOf(8, 24 * 1024 - JsonPrimitive(op.path).toString().utf8Size() - 1024))
  fun cost(text: String): Int = JsonPrimitive(text).toString().utf8Size() - 2
  val text = StringBuilder()
  val line = ByteArrayOutputStream()
  var rawSize = 0L
  var totalLines = 0
  var lineCount = 0
  var used = 0
  var invalidCursor = false
  var capped = false
  var complete = true
  var nextLine: Int? = null
  var nextByte: Int? = null

  fun endLine(terminated: Boolean) {
    totalLines++
    if (totalLines >= offset && lineCount < limit && !capped) {
      val skipped = if (totalLines == offset) op.byteOffset else 0
      if (skipped > rawSize) {
        // A changed file can invalidate a cursor; never silently return a misleading slice.
        invalidCursor = true
      } else {
        val available = textBudget - used
        val raw = line.toByteArray() + if (terminated && rawSize - skipped == line.size().toLong()) byteArrayOf(NEWLINE) else byteArrayOf()
        val decoded = String(raw, UTF_8)
        if (rawSize - skipped <= line.size() && cost(decoded) <= available) {
          text.append(decoded)
          used += cost(decoded)
          lineCount++
        } else {
          if (lineCount == 0) {
            // Keep the cursor in original bytes, and do not split a valid UTF-8 sequence.
            fun boundary(at: Int): Int {
              if (at == raw.size) return at
              for (start in maxOf(0, at - 3) until at) {
                val lead = raw[start].toInt() and 0xFF
                val width = when (lead) { in 0xC2..0xDF -> 2; in 0xE0..0xEF -> 3; in 0xF0..0xF4 -> 4; else -> continue }
                if (start + width > at && start + width <= raw.size &&
                  (start + 1 until start + width).all { (raw[it].toInt() and 0xC0) == 0x80 }) return start
              }
              return at
            }
            var low = 0
            var high = minOf(raw.size, available)
            while (low < high) {
              val middle = (low + high + 1) / 2
              val end = boundary(middle)
              if (cost(String(raw.copyOf(end), UTF_8)) <= available) low = middle else high = middle - 1
            }
            val count = boundary(low)
            val prefix = String(raw.copyOf(count), UTF_8)
            text.append(prefix)
            if (count > 0) lineCount++
            complete = false
            nextLine = totalLines
            nextByte = skipped + count
          } else {
            nextLine = totalLines
            nextByte = 0
          }
          capped = true
        }
      }
    }
    line.reset()
    rawSize = 0
  }

  Files.newInputStream(file).use { input ->
    val chunk = ByteArray(1 shl 16)
    while (true) {
      val read = input.read(chunk)
      if (read < 0) break
      for (index in 0 until read) {
        val byte = chunk[index]
        if (byte == ZERO) return Outcome.Failed(Failure.Binary(workspace.name, op.path), "'${op.path}' is a binary file, so there is no text to return.")
        if (byte == NEWLINE) endLine(true)
        else {
          val skipped = if (totalLines + 1 == offset) op.byteOffset else 0
          if (totalLines + 1 >= offset && lineCount < limit && !capped && rawSize >= skipped && line.size() < byteCap + 4) line.write(byte.toInt())
          rawSize++
        }
      }
    }
  }
  if (rawSize > 0) endLine(false)
  if (invalidCursor || (offset > totalLines && op.byteOffset > 0)) return Outcome.Failed(
    Failure.InvalidArgument("byte_offset"), "The byte cursor is beyond its line; the file may have changed. Read from the line start again.",
  )
  if (!capped && offset.toLong() + lineCount <= totalLines) {
    nextLine = offset + lineCount
    nextByte = 0
  }
  return Outcome.Ok(FileContent(op.path, text.toString(), offset, lineCount, totalLines, capped, complete, nextLine, nextByte))
}
