package io.github.kzagoris.proxenos.buildlogic

import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskAction

abstract class WriteRuntimeClasspathNotice : DefaultTask() {
  @get:Input abstract val coordinates: ListProperty<String>
  @get:Input abstract val moduleName: org.gradle.api.provider.Property<String>
  @get:OutputFile abstract val report: RegularFileProperty

  @TaskAction
  fun write() {
    report.get().asFile.apply {
      parentFile.mkdirs()
      writeText(coordinates.get().sorted().joinToString("\n") { "$it\t" + moduleName.get() } + "\n")
    }
  }
}

abstract class GenerateThirdPartyNotices : DefaultTask() {
  @get:InputFiles abstract val reports: ConfigurableFileCollection
  @get:InputDirectory abstract val licenseDir: DirectoryProperty
  @get:OutputFile abstract val output: RegularFileProperty

  @TaskAction
  fun generate() {
    val components = sortedMapOf<String, MutableSet<String>>()
    reports.files.forEach { report ->
      report.readLines().filter { it.isNotBlank() }.forEach { line ->
        val (coordinate, module) = line.split('\t')
        components.getOrPut(coordinate) { sortedSetOf() }.add(module)
      }
    }
    val fence = "\u0060\u0060\u0060"
    val text = buildString {
      appendLine("# Third-party notices")
      appendLine()
      appendLine("Generated from the resolved runtime classpaths of Runtime, TUI and GUI.")
      appendLine("The portable archive contains Runtime and TUI; GUI entries apply to linux-x64 only.")
      appendLine("The linux-x64 archive's Java runtime carries its own notices in jre/legal/.")
      appendLine("JNA is used under its Apache-2.0 option. Skiko includes Skia native code")
      appendLine("under the BSD-3-Clause text below.")
      appendLine()
      appendLine("| Component | Used by | Licence |")
      appendLine("|---|---|---|")
      components.forEach { (coordinate, modules) ->
        appendLine("| $coordinate | " + modules.joinToString(", ") + " | " + licenseFor(coordinate) + " |")
      }
      appendLine()
      appendLine("The GUI's Material Symbols Outlined vector icons are Apache-2.0.")
      appendLine()
      appendLine("## Licence texts")
      val texts = listOf(
        "Apache-2.0" to "Apache-2.0.txt",
        "Skia BSD-3-Clause" to "Skia-BSD-3-Clause.txt",
        "JNA bundled native notices" to "JNA-OTHERS.txt",
        "dbus-java MIT" to "dbus-java-MIT.txt",
        "FileKit MIT" to "FileKit-MIT.txt",
        "kotlin-codepoints MIT" to "kotlin-codepoints-MIT.txt",
        "MCP Kotlin SDK licence" to "MCP-Kotlin-SDK.txt",
        "SLF4J MIT" to "SLF4J-MIT.txt",
      )
      texts.forEach { (title, source) ->
        appendLine()
        appendLine("### $title")
        appendLine()
        appendLine(fence + "text")
        append(licenseDir.get().asFile.resolve(source).readText().trimEnd())
        appendLine()
        appendLine(fence)
      }
    }
    output.get().asFile.apply {
      parentFile.mkdirs()
      writeText(text)
    }
  }
}

private fun licenseFor(coordinate: String): String {
  val group = coordinate.substringBefore(':')
  return when {
    group == "com.github.hypfvieh" -> "MIT (dbus-java)"
    group == "io.github.vinceglb" -> "MIT (FileKit)"
    group == "de.cketti.unicode" -> "MIT (kotlin-codepoints)"
    group == "io.modelcontextprotocol" -> "MIT / Apache-2.0 (MCP Kotlin SDK)"
    group == "org.slf4j" -> "MIT (SLF4J)"
    group == "net.java.dev.jna" -> "Apache-2.0 (JNA dual license)"
    group.startsWith("androidx.") || group.startsWith("org.jetbrains") ||
      group.startsWith("com.jakewharton.") || group in setOf(
        "com.typesafe", "dev.drewhamilton.poko", "io.github.oshai", "io.ktor",
        "org.jspecify",
      ) -> "Apache-2.0"
    else -> error("Review the licence for new runtime component $coordinate")
  }
}
