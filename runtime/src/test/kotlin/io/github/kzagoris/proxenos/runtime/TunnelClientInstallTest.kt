package io.github.kzagoris.proxenos.runtime

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.jupiter.api.io.TempDir
import kotlin.test.*

/**
 * The shipped `install-tunnel-client` script, judged by the one reader that matters: the
 * Runtime's own lookup of `tunnel-client` (SPEC §11). A release is served from a directory laid
 * out as GitHub lays out `releases/download/`, so nothing here reaches the network.
 */
class TunnelClientInstallTest {
  @TempDir
  lateinit var home: Path

  private val releases: Path get() = home.resolve("releases")
  private val release: Path get() = releases.resolve("v$VERSION")

  /** A machine with credentials and no tunnel-client anywhere: the one the installer is for. */
  private fun environment(): Map<String, String> = mapOf(
    "HOME" to home.toString(),
    "XDG_RUNTIME_DIR" to home.resolve("run").toString(),
    "PATH" to home.resolve("empty").toString(),
  )

  @BeforeTest
  fun `credentials, and a published release`() {
    val credentials = home.resolve(".config/proxenos/credentials")
    Files.createDirectories(credentials.parent)
    Files.writeString(credentials, "TUNNEL_ID=tunnel_abc\nRUNTIME_KEY=sk-secret\n")
    Files.setPosixFilePermissions(credentials, PosixFilePermissions.fromString("rw-------"))
    Files.createDirectories(release)
  }

  @Test
  fun `a release that matches SHA256SUMS txt is installed where the Runtime finds it`() {
    val archive = publish("#!/bin/sh\necho tunnel-client $VERSION\n")
    sums(archive to sha256(archive))

    val installer = install()

    assertEquals(0, installer.exitCode, installer.said)
    val found = sourceConfiguration(environment()).config.tunnelExecutable
    assertEquals(home.resolve(".local/state/proxenos/tools/tunnel-client"), found)
    assertEquals("tunnel-client $VERSION", outputOf(found))
  }

  @Test
  fun `installing first leaves the state directory as private as the Runtime makes it`() {
    val archive = publish("#!/bin/sh\necho tunnel-client $VERSION\n")
    sums(archive to sha256(archive))

    install()

    // The Runtime creates its state directory owner-only, but only when it is absent, so one
    // made first by the installer is the one it keeps.
    val state = home.resolve(".local/state/proxenos")
    assertEquals("rwx------", PosixFilePermissions.toString(Files.getPosixFilePermissions(state)))
  }

  @Test
  fun `a release that does not match SHA256SUMS txt is refused and nothing is installed`() {
    val archive = publish("#!/bin/sh\necho tampered\n")
    sums(archive to "0".repeat(64))

    val installer = install()

    assertNotEquals(0, installer.exitCode)
    assertContains(installer.said, "SHA256SUMS.txt")
    assertFailsWith<StartRefused> { sourceConfiguration(environment()) }
  }

  @Test
  fun `a release SHA256SUMS txt does not list is refused and nothing is installed`() {
    val archive = publish("#!/bin/sh\necho unlisted\n")
    sums(release.resolve("some-other-archive.zip") to sha256(archive))

    val installer = install()

    assertNotEquals(0, installer.exitCode)
    assertContains(installer.said, "SHA256SUMS.txt")
    assertFailsWith<StartRefused> { sourceConfiguration(environment()) }
  }

  /** The release archive as upstream ships it: the executable at the root of a zip. */
  private fun publish(script: String): Path {
    val archive = release.resolve("tunnel-client-v$VERSION-linux-$ARCH.zip")
    ZipOutputStream(Files.newOutputStream(archive)).use { zip ->
      zip.putNextEntry(ZipEntry("tunnel-client"))
      zip.write(script.toByteArray())
      zip.closeEntry()
      zip.putNextEntry(ZipEntry("LICENSE"))
      zip.write("licence text\n".toByteArray())
      zip.closeEntry()
    }
    return archive
  }

  private fun sums(vararg lines: Pair<Path, String>) {
    Files.writeString(release.resolve("SHA256SUMS.txt"), lines.joinToString("") { (file, sum) -> "$sum  ${file.fileName}\n" })
  }

  private fun sha256(file: Path): String =
    MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file)).joinToString("") { "%02x".format(it) }

  private class Finished(val exitCode: Int, val said: String)

  private fun install(): Finished {
    val builder = ProcessBuilder(INSTALLER).redirectErrorStream(true).redirectInput(ProcessBuilder.Redirect.from(java.io.File("/dev/null")))
    builder.environment().apply {
      clear()
      putAll(environment())
      // The installer needs the host's tools (curl, unzip, sha256sum), which the Runtime's own
      // PATH above deliberately lacks.
      put("PATH", System.getenv("PATH"))
      put("TUNNEL_CLIENT_RELEASES", releases.toUri().toString())
      put("TUNNEL_CLIENT_VERSION", VERSION)
    }
    val process = builder.start()
    val said = process.inputStream.bufferedReader().readText()
    assertTrue(process.waitFor(30, TimeUnit.SECONDS), "the installer did not finish: $said")
    return Finished(process.exitValue(), said)
  }

  private fun outputOf(executable: Path): String {
    val process = ProcessBuilder(executable.toString()).redirectErrorStream(true).start()
    val said = process.inputStream.bufferedReader().readText().trim()
    process.waitFor(10, TimeUnit.SECONDS)
    return said
  }

  private companion object {
    const val VERSION = "0.0.14"
    val INSTALLER: String = System.getProperty("installer") ?: error("the installer system property is set by runtime/build.gradle.kts")
    val ARCH = when (System.getProperty("os.arch")) {
      "amd64", "x86_64" -> "amd64"
      "aarch64" -> "arm64"
      else -> error("no tunnel-client release for ${System.getProperty("os.arch")}")
    }
  }
}
