package io.github.kzagoris.proxenos.core

import io.github.kzagoris.proxenos.coreapi.*
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.engine.cio.endpoint
import io.ktor.client.request.header
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.HttpHeaders
import io.ktor.http.Url
import io.ktor.http.URLBuilder
import io.ktor.utils.io.readAvailable
import java.io.ByteArrayOutputStream
import java.net.InetAddress
import java.net.URI
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import javax.net.ssl.X509TrustManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

internal data class DownloadedAttachment(val bytes: ByteArray, val mimeType: String?)

/** Exact observed origins, not a wildcard for a cloud provider. Configuration is test-only. */
internal class AttachmentDownloads(
  private val trustedHosts: Set<String> = setOf("files.oaiusercontent.com", "oaisdmntprdenmarkeast.blob.core.windows.net"),
  private val port: Int = 443,
  private val permitLoopback: Boolean = false,
  private val tlsTrust: X509TrustManager? = null,
  private val budget: Duration = 25.seconds,
) {
  suspend fun read(reference: AttachmentReference): Outcome<DownloadedAttachment> {
    if (reference.fileId.isBlank() || reference.downloadUrl.isBlank()) return assetFailure("file", "A genuine host reference needs nonempty download_url and file_id. Reattach the file to obtain a fresh reference.")
    if (reference.downloadUrl.length > 8192 || reference.fileId.length > 512 || (reference.mimeType?.length ?: 0) > 128 || (reference.fileName?.length ?: 0) > 512) return assetFailure("file", "The attachment reference exceeds its bounded metadata size.")
    val uri = try { URI(reference.downloadUrl) } catch (_: Exception) { return refusedOrigin() }
    val host = uri.host?.lowercase() ?: return refusedOrigin()
    if (uri.scheme != "https" || host !in trustedHosts || uri.rawUserInfo != null || uri.fragment != null ||
      uri.port !in setOf(-1, port) || uri.rawPath.isNullOrEmpty()
    ) return refusedOrigin()
    return try {
      withTimeoutOrNull(budget) {
        // libc DNS can ignore interruption. Bound both the caller's wait and the number
        // of stuck lookups, without waiting for a native resolver during cancellation.
        val lookup = dnsWorkers.submit<List<InetAddress>> { InetAddress.getAllByName(host).toList() }
        val addresses = try { runInterruptible { lookup.get() } } finally { lookup.cancel(true) }
        if (addresses.isEmpty() || addresses.any { !publicAddress(it) && !(permitLoopback && it.isLoopbackAddress) }) return@withTimeoutOrNull refusedOrigin()
        // Dial the checked address itself. TLS still verifies the original hostname and sends SNI.
        // A second DNS lookup (including rebinding) cannot redirect this connection.
        val pinned = URLBuilder(Url(reference.downloadUrl)).apply { this.host = addresses.first().hostAddress }.buildString()
        HttpClient(CIO) {
          followRedirects = false
          expectSuccess = false
          engine {
            requestTimeout = budget.inWholeMilliseconds
            endpoint { connectTimeout = 5000; socketTimeout = 5000; connectAttempts = 1 }
            https { serverName = host; tlsTrust?.let { trustManager = it } }
          }
        }.use { client ->
          client.prepareGet(pinned) {
            header(HttpHeaders.Host, if (port == 443) host else "$host:$port")
            header(HttpHeaders.AcceptEncoding, "identity")
          }.execute { response ->
            if (response.status.value != 200) return@execute assetFailure("file", "Attachment download was refused (HTTP ${response.status.value}); redirects are not followed. Reattach an expired or invalid file to obtain a fresh reference, then use a fresh request_id.")
            val encoding = response.headers[HttpHeaders.ContentEncoding]
            if (encoding != null && encoding != "identity") return@execute assetFailure("file", "Encoded attachment responses are not supported.")
            val length = response.headers[HttpHeaders.ContentLength]?.toLongOrNull()
            if (length != null && (length < 0 || length > ASSET_BYTE_CAP)) return@execute tooLarge()
            val channel = response.bodyAsChannel()
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(16 * 1024)
            while (true) {
              currentCoroutineContext().ensureActive()
              val read = channel.readAvailable(buffer, 0, minOf(buffer.size, ASSET_BYTE_CAP + 1 - output.size()))
              if (read < 0) break
              if (read == 0) continue
              output.write(buffer, 0, read)
              if (output.size() > ASSET_BYTE_CAP) return@execute tooLarge()
            }
            if (length != null && output.size().toLong() != length) return@execute assetFailure("file", "Attachment transfer was incomplete; no destination was changed.")
            Outcome.Ok(DownloadedAttachment(output.toByteArray(), response.headers[HttpHeaders.ContentType]?.substringBefore(';')?.trim()?.lowercase()))
          }
        }
      } ?: assetFailure("file", "Attachment download timed out before replacement; no destination was changed. Reattach if the reference expired.")
    } catch (cancelled: CancellationException) {
      throw cancelled
    } catch (_: Exception) {
      // HTTP/TLS exceptions can contain the signed URL; neither their message nor cause escapes.
      assetFailure("file", "Attachment download could not complete; no destination was changed. Reattach an expired or invalid file to obtain a fresh host reference.")
    }
  }

  private fun refusedOrigin() = assetFailure("file", "Attachment origin refused: HTTPS on an exact trusted attachment host and public resolved addresses are required; credentials, custom ports and redirects are not accepted.")
  private fun tooLarge() = assetFailure("file", "Attachment exceeds the 1 MiB streaming byte cap; no destination was changed.")
}

private fun publicAddress(address: InetAddress): Boolean {
  if (address.isAnyLocalAddress || address.isLoopbackAddress || address.isLinkLocalAddress || address.isSiteLocalAddress || address.isMulticastAddress) return false
  val bytes = address.address.map { it.toInt() and 255 }
  if (bytes.size == 16) return bytes[0] in 0x20..0x3f &&
    !(bytes[0] == 0x20 && bytes[1] == 0x01 && bytes[2] < 2) &&
    !(bytes[0] == 0x20 && bytes[1] == 0x01 && bytes[2] == 0x0d && bytes[3] == 0xb8) &&
    !(bytes[0] == 0x20 && bytes[1] == 0x02) && !(bytes[0] == 0x3f && bytes[1] == 0xff)
  val a = bytes[0]; val b = bytes[1]; val c = bytes[2]
  return a !in setOf(0, 10, 127) && a < 224 && !(a == 100 && b in 64..127) &&
    !(a == 169 && b == 254) && !(a == 172 && b in 16..31) &&
    !(a == 192 && (b == 168 || (b == 0 && c in setOf(0, 2)) || (b == 88 && c == 99))) &&
    !(a == 198 && (b in 18..19 || (b == 51 && c == 100))) && !(a == 203 && b == 0 && c == 113)
}

private val dnsWorkers = ThreadPoolExecutor(
  0, 4, 30, TimeUnit.SECONDS, SynchronousQueue(),
  Thread.ofPlatform().daemon(true).name("attachment-dns-", 0).factory(),
)
