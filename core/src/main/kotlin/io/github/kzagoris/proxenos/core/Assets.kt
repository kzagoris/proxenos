package io.github.kzagoris.proxenos.core

import io.github.kzagoris.proxenos.coreapi.*
import java.io.IOException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardOpenOption.READ
import java.security.MessageDigest
import java.util.Base64
import java.util.HexFormat

internal fun exportFile(op: Operation.ExportFile, workspace: Workspace, file: Path): Outcome<AssetExported> {
  if (!Files.isRegularFile(file, NOFOLLOW_LINKS)) return assetFailure("path", "The export must name a regular file inside the Root.")
  val bytes = Files.newInputStream(file, READ, NOFOLLOW_LINKS).use { it.readNBytes(ASSET_BYTE_CAP + 1) }
  if (bytes.size > ASSET_BYTE_CAP) return assetFailure("path", "The file exceeds the 1 MiB binary transfer cap; nothing was exported.")
  val metadata = assetMetadata(op.path, bytes) ?: return assetFailure("path", "Only PNG and PDF signatures are supported.")
  if (op.vision && metadata.mimeType != "image/png") return assetFailure("vision", "Vision content is available for PNG only; export this PDF without vision.")
  return Outcome.Ok(AssetExported(metadata, Base64.getEncoder().encodeToString(bytes)))
}

internal suspend fun importAttachment(
  op: Operation.ImportAttachment, workspace: Workspace, file: Path,
  registry: WorkspaceRegistry, downloads: AttachmentDownloads,
): Outcome<AssetImported> {
  if (!Files.isDirectory(file.parent) || !Files.isWritable(file.parent)) return assetFailure("path", "The destination parent must already exist and be writable; no parents are created.")
  if (Files.exists(file, NOFOLLOW_LINKS) && !Files.isRegularFile(file, NOFOLLOW_LINKS)) return assetFailure("path", "The destination must be a regular file or a new file.")
  val downloaded = downloads.read(op.file).valueOr { return it }
  val metadata = assetMetadata(op.path, downloaded.bytes) ?: return assetFailure("file", "Only PNG and PDF signatures are supported.")
  for (declared in listOfNotNull(op.file.mimeType, downloaded.mimeType)) {
    if (declared != "application/octet-stream" && declared != metadata.mimeType) return assetFailure("file", "Attachment MIME does not match its signature; no destination was changed.")
  }
  // Network work can outlast a local change of Root or Access Level. Re-admit before staging.
  val admitted = registry.admit(workspace.name, AccessLevel.Write).valueOr { return it }
  val checked = confine(admitted, op.path).valueOr { return it }
  if (checked != file) return assetFailure("path", "The destination changed during download; no destination was changed.")
  val created = !Files.exists(file, NOFOLLOW_LINKS)
  if (!created && !Files.isRegularFile(file, NOFOLLOW_LINKS)) return assetFailure("path", "The destination is no longer a regular file; no destination was changed.")
  currentCoroutineContext().ensureActive()
  return when (val landed = Target(workspace, op.path, file).land(downloaded.bytes, created)) {
    is Outcome.Ok -> Outcome.Ok(AssetImported(metadata, landed.value.created))
    is Outcome.Failed -> landed
    is Outcome.Uncertain -> landed
  }
}

internal fun assetMetadata(path: String, bytes: ByteArray): AssetMetadata? {
  val mime = when {
    bytes.size >= 33 && bytes.copyOfRange(0, 8).contentEquals(byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10)) &&
      String(bytes, 12, 4, Charsets.US_ASCII) == "IHDR" -> "image/png"
    bytes.size >= 8 && String(bytes, 0, 5, Charsets.US_ASCII) == "%PDF-" &&
      String(bytes, maxOf(0, bytes.size - 1024), minOf(bytes.size, 1024), Charsets.US_ASCII).contains("%%EOF") -> "application/pdf"
    else -> return null
  }
  return AssetMetadata(path, mime, bytes.size, HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)))
}

internal fun assetFailure(argument: String, detail: String): Outcome.Failed = Outcome.Failed(
  Failure.InvalidArgument(argument), "This Operation failed and changed nothing: $detail",
)
