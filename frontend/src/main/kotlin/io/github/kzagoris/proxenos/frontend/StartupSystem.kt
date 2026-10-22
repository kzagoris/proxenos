package io.github.kzagoris.proxenos.frontend

import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit

/** The operating system boundary: argument arrays, never shell commands or credential values. */
fun interface StartupSystem {
  fun run(arguments: List<String>): StartupReply
}

data class StartupReply(val code: Int, val words: String)

internal object LocalStartupSystem : StartupSystem {
  override fun run(arguments: List<String>): StartupReply {
    val process = ProcessBuilder(arguments).redirectErrorStream(true)
      .redirectInput(ProcessBuilder.Redirect.from(java.io.File("/dev/null"))).start()
    val output = ByteArrayOutputStream()
    val reader = Thread {
      runCatching {
        process.inputStream.use { input ->
          val buffer = ByteArray(4096)
          while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            synchronized(output) { output.write(buffer, 0, count.coerceAtMost((16_384 - output.size()).coerceAtLeast(0))) }
          }
        }
      }
    }.apply { isDaemon = true; start() }
    try {
      if (!process.waitFor(30, TimeUnit.SECONDS)) {
        return StartupReply(1, "The operating system did not answer within 30 seconds. Check the authorization prompt and try again.")
      }
      reader.join(1000)
      return StartupReply(process.exitValue(), synchronized(output) { output.toString(Charsets.UTF_8).trim() })
    } finally {
      if (process.isAlive) {
        process.descendants().forEach { it.destroyForcibly() }
        process.destroyForcibly()
      }
    }
  }
}
