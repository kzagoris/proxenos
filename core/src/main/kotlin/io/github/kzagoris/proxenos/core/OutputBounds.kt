package io.github.kzagoris.proxenos.core

import java.io.ByteArrayOutputStream
import java.io.InputStream
import kotlin.text.Charsets.UTF_8

internal const val NEWLINE: Byte = '\n'.code.toByte()
internal const val CARRIAGE_RETURN: Byte = '\r'.code.toByte()
internal const val ZERO: Byte = 0

internal fun String.utf8Size(): Int = toByteArray(UTF_8).size

/** The longest prefix whose UTF-8 encoding fits [budget], cut between characters. */
internal fun String.takeUtf8(budget: Int): String {
  if (utf8Size() <= budget) return this
  var size = 0
  var index = 0
  while (index < length) {
    val codePoint = codePointAt(index)
    val width = when {
      codePoint < 0x80 -> 1
      codePoint < 0x800 -> 2
      codePoint < 0x10000 -> 3
      else -> 4
    }
    if (size + width > budget) break
    size += width
    index += Character.charCount(codePoint)
  }
  return substring(0, index)
}

/** What survived the byte bound, and the size of the hole it left in the middle. */
internal class HeadAndTail<T>(val kept: List<T>, val droppedBytes: Int, val droppedItems: Int)

/**
 * The output bound, as one implementation rather than as each caller's own arithmetic: keep
 * what fits in [head] from the front and what fits in [tail] from the back, and name the bytes
 * between them. Truncation is never silent, and the marker is a byte count.
 *
 * [weight] measures what an item contributes to the reply. It is the varying part of what is
 * returned, not an exact count of rendered bytes — the punctuation around them belongs to
 * whichever adapter renders them, and no adapter exists to ask.
 */
internal fun <T> headAndTail(items: List<T>, head: Int, tail: Int, weight: (T) -> Int): HeadAndTail<T> {
  val weights = items.map(weight)
  val total = weights.sumOf { it.toLong() }
  if (total <= head + tail) return HeadAndTail(items, 0, 0)

  var first = 0
  var headBytes = 0
  while (first < items.size && headBytes + weights[first] <= head) {
    headBytes += weights[first]
    first++
  }
  var last = items.size
  var tailBytes = 0
  while (last > first && tailBytes + weights[last - 1] <= tail) {
    tailBytes += weights[last - 1]
    last--
  }
  val dropped = total - headBytes - tailBytes
  return HeadAndTail(
    items.subList(0, first) + items.subList(last, items.size),
    // The cap is an Int, so what is dropped beneath it is one too.
    dropped.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
    last - first,
  )
}

/** What survived the byte bound, and the size of the hole it left in the middle. */
internal class BoundedText(val text: String, val droppedBytes: Int)

/**
 * The output bound over a stream of bytes: 32 KiB from the front, 32 KiB from the back, and a
 * count of what fell between them. Streamed rather than collected, because a generated file's
 * diff — and a long build's output — is megabytes, and holding it whole to then throw away the
 * middle is how a Runtime runs out of memory.
 *
 * Both cuts are moved to a line boundary. What is bounded here is read in lines, and the bytes
 * that move are counted with the rest, so the marker still names everything that was dropped.
 *
 * [accept] and [text] are synchronized because a command's output is fed in by the thread
 * draining the child while the thread that reaped it asks what was captured — and after a
 * reaping the drain is not always over: a grandchild that outlived the kill still holds the
 * write end of the pipe.
 */
internal class HeadAndTailBytes(private val head: Int, private val tail: Int) {
  private val front = ByteArrayOutputStream()
  private val back = ByteArray(tail)
  private var backStart = 0
  private var backSize = 0
  private var dropped = 0L

  /** Read to the end, and bound what came through. */
  fun drain(input: InputStream): BoundedText {
    val chunk = ByteArray(1 shl 16)
    while (true) {
      val read = input.read(chunk)
      if (read < 0) break
      accept(chunk, 0, read)
    }
    return text()
  }

  @Synchronized
  fun accept(chunk: ByteArray, offset: Int, length: Int) {
    var at = offset
    var left = length
    if (front.size() < head) {
      val take = minOf(head - front.size(), left)
      front.write(chunk, at, take)
      at += take
      left -= take
    }
    if (left > 0) intoTail(chunk, at, left)
  }

  @Synchronized
  fun text(): BoundedText {
    val tailBytes = ByteArray(backSize)
    for (index in 0 until backSize) tailBytes[index] = back[(backStart + index) % tail]
    if (dropped == 0L) return BoundedText(String(front.toByteArray() + tailBytes, UTF_8), 0)

    // Cut both ends back to a line boundary, and count the bytes that move with the rest.
    val headBytes = front.toByteArray()
    val headEnd = headBytes.lastIndexOf(NEWLINE) + 1
    val tailStart = tailBytes.indexOf(NEWLINE) + 1
    val moved = (headBytes.size - headEnd) + tailStart
    val text = String(headBytes, 0, headEnd, UTF_8) + String(tailBytes, tailStart, tailBytes.size - tailStart, UTF_8)
    return BoundedText(text, (dropped + moved).coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
  }

  /** Into the ring, dropping from its front whatever no longer fits. */
  private fun intoTail(chunk: ByteArray, offset: Int, length: Int) {
    if (length >= tail) {
      dropped += backSize + (length - tail)
      System.arraycopy(chunk, offset + length - tail, back, 0, tail)
      backStart = 0
      backSize = tail
      return
    }
    val overflow = backSize + length - tail
    if (overflow > 0) {
      backStart = (backStart + overflow) % tail
      backSize -= overflow
      dropped += overflow
    }
    val writeAt = (backStart + backSize) % tail
    val first = minOf(length, tail - writeAt)
    System.arraycopy(chunk, offset, back, writeAt, first)
    if (length > first) System.arraycopy(chunk, offset + first, back, 0, length - first)
    backSize += length
  }
}
