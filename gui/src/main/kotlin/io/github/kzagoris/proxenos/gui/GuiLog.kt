package io.github.kzagoris.proxenos.gui

import java.io.IOException
import java.io.PrintStream
import java.nio.channels.Channels
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.PosixFilePermissions
import java.time.Instant

internal class GuiLog : AutoCloseable {
  private val output: PrintStream? = System.getenv("XDG_RUNTIME_DIR")?.takeIf { it.isNotEmpty() }?.let { base ->
    try {
      val directory = Path.of(base).resolve("proxenos")
      val ownerDirectory = PosixFilePermissions.fromString("rwx------")
      Files.createDirectories(directory, PosixFilePermissions.asFileAttribute(ownerDirectory))
      Files.setPosixFilePermissions(directory, ownerDirectory)
      val log = directory.resolve("gui.log")
      val ownerFile = PosixFilePermissions.fromString("rw-------")
      val channel = Files.newByteChannel(log,
        setOf(StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.APPEND),
        PosixFilePermissions.asFileAttribute(ownerFile),
      )
      Files.setPosixFilePermissions(log, ownerFile)
      PrintStream(Channels.newOutputStream(channel), true, Charsets.UTF_8)
    } catch (failed: IOException) {
      System.err.println("gui: could not open gui.log: ${failed.message}")
      null
    }
  }

  init { output?.println("gui: started ${Instant.now()} pid=${ProcessHandle.current().pid()}") }

  @Synchronized fun say(words: String) {
    System.err.println("gui: $words")
    output?.println("gui: $words")
  }

  override fun close() { output?.close() }
}
