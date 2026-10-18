package io.github.kzagoris.proxenos.core

import com.sun.net.httpserver.HttpsConfigurator
import com.sun.net.httpserver.HttpsServer
import com.sun.net.httpserver.HttpExchange
import java.net.InetSocketAddress
import java.nio.file.Files
import java.nio.file.Path
import java.security.KeyStore
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

/** Real HTTPS, including certificate/hostname checks; no fake downloader beneath the core. */
internal class AttachmentHost(directory: Path, handler: (HttpExchange) -> Unit) : AutoCloseable {
  val calls = AtomicInteger()
  val trust: X509TrustManager
  private val executor = Executors.newCachedThreadPool()
  private val server = HttpsServer.create(InetSocketAddress("127.0.0.1", 0), 0)
  val port: Int get() = server.address.port

  init {
    val store = directory.resolve("https.p12")
    val process = ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "keytool").toString(),
      "-genkeypair", "-alias", "test", "-keyalg", "RSA", "-keystore", store.toString(),
      "-storepass", "temporary-test-password", "-dname", "CN=localhost", "-ext", "SAN=dns:localhost", "-validity", "1")
      .redirectErrorStream(true).start()
    val output = process.inputStream.readAllBytes().decodeToString()
    check(process.waitFor() == 0) { output }
    val keys = KeyStore.getInstance("PKCS12").apply {
      Files.newInputStream(store).use { load(it, "temporary-test-password".toCharArray()) }
    }
    val managers = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply {
      init(keys, "temporary-test-password".toCharArray())
    }
    val trusts = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(keys) }
    trust = trusts.trustManagers.filterIsInstance<X509TrustManager>().single()
    val context = SSLContext.getInstance("TLS").apply { init(managers.keyManagers, trusts.trustManagers, null) }
    server.httpsConfigurator = HttpsConfigurator(context)
    server.executor = executor
    server.createContext("/") { exchange ->
      calls.incrementAndGet()
      try { handler(exchange) } finally { exchange.close() }
    }
    server.start()
  }

  override fun close() {
    server.stop(0)
    executor.shutdownNow()
  }
}
