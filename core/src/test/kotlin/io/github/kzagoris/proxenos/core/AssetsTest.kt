package io.github.kzagoris.proxenos.core

import io.github.kzagoris.proxenos.coreapi.*
import java.nio.file.Files
import java.nio.file.Path
import java.util.Base64
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.async
import org.junit.jupiter.api.io.TempDir
import kotlin.test.*

class AssetsTest {
  @TempDir lateinit var temporary: Path

  @Test
  fun `Read exports exact PNG bytes with observed MIME actual size and SHA-256`() = runBlocking<Unit> {
    val root = Files.createDirectory(temporary.resolve("root"))
    val png = Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+aA1sAAAAASUVORK5CYII=")
    Files.write(root.resolve("pixel.png"), png)
    val registry = WorkspaceRegistry(temporary.resolve("registry"))
    registry.perform(ManagementAct.Register(root.toString(), "assets"))
    val activity = Activity(temporary.resolve("activity"))
    val operations = WorkspaceOperationsPipeline(registry, activity).operationsFor(Origin.ChatGpt)
    val result = assertIs<Outcome.Ok<AssetExported>>(operations.perform(Operation.ExportFile("assets", "pixel.png"))).value
    assertContentEquals(png, Base64.getDecoder().decode(result.base64))
    assertEquals("image/png", result.metadata.mimeType)
    assertEquals(68, result.metadata.bytes)
    assertEquals("852a44ed047cc995ca787c4aee5bd87fecede010675c1586e32a55f9bd5cfbc8", result.metadata.sha256)
  }
  @Test
  fun `Write imports a host attachment byte exactly and repeated Delivery never downloads again`() = runBlocking<Unit> {
    val root = Files.createDirectory(temporary.resolve("root"))
    val pdf = "%PDF-1.4\nnew export bytes\n%%EOF\n".toByteArray()
    AttachmentHost(temporary) { exchange ->
      exchange.responseHeaders.add("Content-Type", "application/pdf")
      exchange.sendResponseHeaders(200, pdf.size.toLong())
      exchange.responseBody.write(pdf)
    }.use { host ->
      val registry = WorkspaceRegistry(temporary.resolve("registry"))
      val workspace = registry.perform(ManagementAct.Register(root.toString(), "assets"))
      registry.perform(ManagementAct.SetLevel(workspace.id, AccessLevel.Write))
      val activity = Activity(temporary.resolve("activity"))
      val operations = WorkspaceOperationsPipeline(registry, activity, Operation.SEARCH_BUDGET, PathLocks(),
        attachments = AttachmentDownloads(setOf("localhost"), host.port, true, host.trust))
        .operationsFor(Origin.ChatGpt)
      val op = Operation.ImportAttachment("assets", "saved.pdf",
        AttachmentReference("https://localhost:${host.port}/file?secret=SIGNED-URL", "file-test", "application/pdf", "../ignored.pdf"), "import-1")
      val result = assertIs<Outcome.Ok<AssetImported>>(operations.perform(op)).value
      assertContentEquals(pdf, Files.readAllBytes(root.resolve("saved.pdf")))
      assertEquals(pdf.size, result.metadata.bytes)
      assertEquals("application/pdf", result.metadata.mimeType)
      assertTrue(result.created)
      registry.perform(ManagementAct.SetLevel(workspace.id, AccessLevel.None))
      val repeated = assertIs<Outcome.Ok<AssetImported>>(operations.perform(op))
      assertTrue(repeated.recorded)
      assertEquals(result, repeated.value)
      assertEquals(1, host.calls.get())
      assertEquals(1, activity.entries().size)
      assertFalse(Files.readString(temporary.resolve("activity")).contains("SIGNED-URL"))
    }
  }

  @Test
  fun `invalid expired redirected oversized and mismatched attachments leave the existing destination intact`() = runBlocking<Unit> {
    val root = Files.createDirectory(temporary.resolve("root"))
    val original = "keep existing bytes".toByteArray()
    Files.write(root.resolve("saved.pdf"), original)
    val pdf = "%PDF-1.4\nreplacement\n%%EOF\n".toByteArray()
    AttachmentHost(temporary) { exchange ->
      when (exchange.requestURI.path) {
        "/expired" -> exchange.sendResponseHeaders(403, -1)
        "/redirect" -> { exchange.responseHeaders.add("Location", "https://localhost:${exchange.localAddress.port}/ok"); exchange.sendResponseHeaders(302, -1) }
        "/large" -> { exchange.sendResponseHeaders(200, 0); exchange.responseBody.write(ByteArray(1024 * 1024 + 1)) }
        "/length" -> exchange.sendResponseHeaders(200, 1024 * 1024 + 1L)
        "/mime" -> { exchange.responseHeaders.add("Content-Type", "image/png"); exchange.sendResponseHeaders(200, pdf.size.toLong()); exchange.responseBody.write(pdf) }
        "/signature" -> { exchange.sendResponseHeaders(200, 4); exchange.responseBody.write("text".toByteArray()) }
        else -> { exchange.responseHeaders.add("Content-Type", "application/pdf"); exchange.sendResponseHeaders(200, pdf.size.toLong()); exchange.responseBody.write(pdf) }
      }
    }.use { host ->
      val registry = WorkspaceRegistry(temporary.resolve("registry"))
      val workspace = registry.perform(ManagementAct.Register(root.toString(), "assets"))
      registry.perform(ManagementAct.SetLevel(workspace.id, AccessLevel.Write))
      val activity = Activity(temporary.resolve("activity"))
      val operations = WorkspaceOperationsPipeline(registry, activity, Operation.SEARCH_BUDGET, PathLocks(),
        attachments = AttachmentDownloads(setOf("localhost"), host.port, true, host.trust)).operationsFor(Origin.ChatGpt)
      for (route in listOf("expired", "redirect", "large", "length", "mime", "signature")) {
        val op = Operation.ImportAttachment("assets", "saved.pdf", AttachmentReference("https://localhost:${host.port}/$route?sig=SECRET", "file-test"), route)
        assertIs<Outcome.Failed>(operations.perform(op), route)
        assertContentEquals(original, Files.readAllBytes(root.resolve("saved.pdf")), route)
        assertEquals(listOf("saved.pdf"), Files.list(root).use { it.map { p -> p.fileName.toString() }.toList() })
      }
      val calls = host.calls.get()
      val untrusted = listOf("http://localhost:${host.port}/ok", "https://user:pass@localhost:${host.port}/ok", "https://localhost:1/ok", "https://evil.blob.core.windows.net/ok", "sandbox:/mnt/data/file.pdf", "")
      for ((index, url) in untrusted.withIndex()) assertIs<Outcome.Failed>(operations.perform(Operation.ImportAttachment("assets", "saved.pdf", AttachmentReference(url, "file-test"), "ref-$index")))
      assertIs<Outcome.Failed>(operations.perform(Operation.ImportAttachment("assets", "saved.pdf", AttachmentReference("https://localhost:${host.port}/ok", ""), "missing-id")))
      assertEquals(calls, host.calls.get(), "invalid references must be refused before network work")
      assertFalse(Files.readString(temporary.resolve("activity")).contains("SECRET"))
      assertTrue(activity.entries().all { it.outcome is ActivityOutcome.Failed })
    }
  }

  @Test
  fun `cancellation during download records known unchanged destination and leaves no staging file`() = runBlocking<Unit> {
    val root = Files.createDirectory(temporary.resolve("root"))
    Files.writeString(root.resolve("saved.pdf"), "untouched")
    val arrived = kotlinx.coroutines.CompletableDeferred<Unit>()
    val release = java.util.concurrent.CountDownLatch(1)
    AttachmentHost(temporary) { exchange ->
      exchange.sendResponseHeaders(200, 0)
      exchange.responseBody.write("%PDF-1.4\n".toByteArray())
      exchange.responseBody.flush()
      arrived.complete(Unit)
      release.await()
    }.use { host ->
      val registry = WorkspaceRegistry(temporary.resolve("registry"))
      val workspace = registry.perform(ManagementAct.Register(root.toString(), "assets"))
      registry.perform(ManagementAct.SetLevel(workspace.id, AccessLevel.Write))
      val activity = Activity(temporary.resolve("activity"))
      val operations = WorkspaceOperationsPipeline(registry, activity, Operation.SEARCH_BUDGET, PathLocks(),
        attachments = AttachmentDownloads(setOf("localhost"), host.port, true, host.trust)).operationsFor(Origin.ChatGpt)
      val op = Operation.ImportAttachment("assets", "saved.pdf", AttachmentReference("https://localhost:${host.port}/slow?sig=SECRET", "file-test"), "interrupted")
      val call = async { operations.perform(op) }
      try {
        kotlinx.coroutines.withTimeout(10000) { arrived.await() }
        call.cancel()
        call.join()
        assertIs<ActivityOutcome.Failed>(activity.entries().single().outcome)
        assertEquals("untouched", Files.readString(root.resolve("saved.pdf")))
        assertEquals(listOf("saved.pdf"), Files.list(root).use { it.map { p -> p.fileName.toString() }.toList() })
        assertFalse(Files.readString(temporary.resolve("activity")).contains("SECRET"))
      } finally { release.countDown() }
    }
  }

  @Test
  fun `admission confinement and public address policy refuse before downloading`() = runBlocking<Unit> {
    val root = Files.createDirectory(temporary.resolve("root"))
    val outside = Files.createDirectory(temporary.resolve("outside"))
    Files.createSymbolicLink(root.resolve("escape"), outside)
    Files.createSymbolicLink(root.resolve("dangling"), outside.resolve("absent.pdf"))
    AttachmentHost(temporary) { error("No network work should be admitted") }.use { host ->
      val registry = WorkspaceRegistry(temporary.resolve("registry"))
      val workspace = registry.perform(ManagementAct.Register(root.toString(), "assets"))
      val activity = Activity(temporary.resolve("activity"))
      val operations = WorkspaceOperationsPipeline(registry, activity, Operation.SEARCH_BUDGET, PathLocks(),
        attachments = AttachmentDownloads(setOf("localhost"), host.port, true, host.trust)).operationsFor(Origin.ChatGpt)
      val reference = AttachmentReference("https://localhost:${host.port}/file", "file-test")
      fun op(path: String, key: String = path) = Operation.ImportAttachment("assets", path, reference, key)
      assertIs<Failure.LevelTooLow>(assertIs<Outcome.Failed>(operations.perform(op("read-only.pdf"))).reason)
      registry.perform(ManagementAct.SetLevel(workspace.id, AccessLevel.Write))
      for (path in listOf("../outside/file.pdf", outside.resolve("file.pdf").toString(), "escape/file.pdf", "dangling", "missing-parent/file.pdf")) {
        assertIs<Outcome.Failed>(operations.perform(op(path)))
      }
      assertIs<Failure.MissingKey>(assertIs<Outcome.Failed>(operations.perform(op("missing-key.pdf", ""))).reason)
      val strict = WorkspaceOperationsPipeline(registry, activity, Operation.SEARCH_BUDGET, PathLocks(),
        attachments = AttachmentDownloads(setOf("localhost"), host.port, false, host.trust)).operationsFor(Origin.ChatGpt)
      assertIs<Outcome.Failed>(strict.perform(op("private-address.pdf")))
      Files.move(root, temporary.resolve("moved-root"))
      assertIs<Failure.WorkspaceBroken>(assertIs<Outcome.Failed>(operations.perform(op("broken.pdf"))).reason)
      assertEquals(0, host.calls.get())
      assertEquals(0, Files.list(outside).use { it.count() }.toInt())
    }
  }

  @Test
  fun `timeout refuses before staging and admission is repeated after a slow download`() = runBlocking<Unit> {
    val root = Files.createDirectory(temporary.resolve("root"))
    Files.writeString(root.resolve("saved.pdf"), "original")
    val pdf = "%PDF-1.4\nnew bytes\n%%EOF\n".toByteArray()
    val arrived = kotlinx.coroutines.CompletableDeferred<Unit>()
    val release = java.util.concurrent.CountDownLatch(1)
    AttachmentHost(temporary) { exchange ->
      exchange.responseHeaders.add("Content-Type", "application/pdf")
      exchange.sendResponseHeaders(200, pdf.size.toLong())
      arrived.complete(Unit)
      release.await()
      exchange.responseBody.write(pdf)
    }.use { host ->
      val registry = WorkspaceRegistry(temporary.resolve("registry"))
      val workspace = registry.perform(ManagementAct.Register(root.toString(), "assets"))
      registry.perform(ManagementAct.SetLevel(workspace.id, AccessLevel.Write))
      val activity = Activity(temporary.resolve("activity"))
      val reference = AttachmentReference("https://localhost:${host.port}/slow", "file-test")
      val op = Operation.ImportAttachment("assets", "saved.pdf", reference, "slow")
      val operations = WorkspaceOperationsPipeline(registry, activity, Operation.SEARCH_BUDGET, PathLocks(),
        attachments = AttachmentDownloads(setOf("localhost"), host.port, true, host.trust)).operationsFor(Origin.ChatGpt)
      val call = async { operations.perform(op) }
      try {
        kotlinx.coroutines.withTimeout(10000) { arrived.await() }
        registry.perform(ManagementAct.SetLevel(workspace.id, AccessLevel.Read))
        release.countDown()
        assertIs<Failure.LevelTooLow>(assertIs<Outcome.Failed>(call.await()).reason)
        assertEquals("original", Files.readString(root.resolve("saved.pdf")))
      } finally { release.countDown() }
    }
    // A separate real HTTPS response that never completes its body.
    val timeoutDirectory = Files.createDirectory(temporary.resolve("timeout"))
    val unblock = java.util.concurrent.CountDownLatch(1)
    AttachmentHost(timeoutDirectory) { exchange ->
      exchange.sendResponseHeaders(200, 0)
      exchange.responseBody.flush()
      unblock.await()
    }.use { host ->
      val registry = WorkspaceRegistry(temporary.resolve("timeout-registry"))
      val workspace = registry.perform(ManagementAct.Register(root.toString(), "assets"))
      registry.perform(ManagementAct.SetLevel(workspace.id, AccessLevel.Write))
      val operations = WorkspaceOperationsPipeline(registry, Activity(temporary.resolve("timeout-activity")), Operation.SEARCH_BUDGET, PathLocks(),
        attachments = AttachmentDownloads(setOf("localhost"), host.port, true, host.trust, kotlin.time.Duration.parse("1s"))).operationsFor(Origin.ChatGpt)
      try {
        assertIs<Outcome.Failed>(kotlinx.coroutines.withTimeout(5000) { operations.perform(Operation.ImportAttachment("assets", "saved.pdf", AttachmentReference("https://localhost:${host.port}/slow", "file-timeout"), "timeout")) })
        assertEquals("original", Files.readString(root.resolve("saved.pdf")))
        assertEquals(listOf("saved.pdf"), Files.list(root).use { it.map { p -> p.fileName.toString() }.toList() })
      } finally { unblock.countDown() }
    }
  }

  @Test
  fun `a destination created during download is reported as replaced with its mode and old hardlink preserved`() = runBlocking<Unit> {
    val root = Files.createDirectory(temporary.resolve("root"))
    val destination = root.resolve("saved.pdf")
    val pdf = "%PDF-1.4\r\nnew exact bytes\r\n%%EOF\r\n".toByteArray()
    val mode = java.nio.file.attribute.PosixFilePermissions.fromString("rw-------")
    AttachmentHost(temporary) { exchange ->
      Files.writeString(destination, "external version")
      Files.setPosixFilePermissions(destination, mode)
      Files.createLink(root.resolve("old-link"), destination)
      exchange.responseHeaders.add("Content-Type", "application/pdf")
      exchange.sendResponseHeaders(200, pdf.size.toLong())
      exchange.responseBody.write(pdf)
    }.use { host ->
      val registry = WorkspaceRegistry(temporary.resolve("registry"))
      val workspace = registry.perform(ManagementAct.Register(root.toString(), "assets"))
      registry.perform(ManagementAct.SetLevel(workspace.id, AccessLevel.Write))
      val activity = Activity(temporary.resolve("activity"))
      val operations = WorkspaceOperationsPipeline(registry, activity, Operation.SEARCH_BUDGET, PathLocks(),
        attachments = AttachmentDownloads(setOf("localhost"), host.port, true, host.trust)).operationsFor(Origin.ChatGpt)
      val result = assertIs<Outcome.Ok<AssetImported>>(operations.perform(Operation.ImportAttachment("assets", "saved.pdf",
        AttachmentReference("https://localhost:${host.port}/file", "file-test"), "replace"))).value
      assertFalse(result.created)
      assertContentEquals(pdf, Files.readAllBytes(destination))
      assertEquals(mode, Files.getPosixFilePermissions(destination))
      assertEquals("external version", Files.readString(root.resolve("old-link")))
      assertContains(assertIs<ActivityOutcome.Ok>(activity.entries().single().outcome).detail, "replaced")
    }
  }

  @Test
  fun `an import cancelled while waiting for its destination lock records no effects`() = runBlocking<Unit> {
    val root = Files.createDirectory(temporary.resolve("root"))
    val destination = root.resolve("saved.pdf")
    val registry = WorkspaceRegistry(temporary.resolve("registry"))
    val workspace = registry.perform(ManagementAct.Register(root.toString(), "assets"))
    registry.perform(ManagementAct.SetLevel(workspace.id, AccessLevel.Write))
    val activity = Activity(temporary.resolve("activity"))
    val locks = PathLocks()
    val operations = WorkspaceOperationsPipeline(registry, activity, Operation.SEARCH_BUDGET, locks).operationsFor(Origin.ChatGpt)
    locks.withPathLock(destination.toString()) {
      val call = async { operations.perform(Operation.ImportAttachment("assets", "saved.pdf",
        AttachmentReference("https://files.oaiusercontent.com/file", "file-test"), "waiting")) }
      kotlinx.coroutines.withTimeout(5000) { while (activity.entries().isEmpty()) kotlinx.coroutines.yield() }
      call.cancel()
      call.join()
      assertIs<ActivityOutcome.Failed>(activity.entries().single().outcome)
      assertFalse(Files.exists(destination))
    }
  }

}
