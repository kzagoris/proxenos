package io.github.kzagoris.proxenos.gui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.dp
import io.github.kzagoris.proxenos.frontend.Attachment
import io.github.kzagoris.proxenos.frontend.Reason
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.channels.ServerSocketChannel
import java.nio.channels.SocketChannel
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicReference
import org.freedesktop.dbus.bin.EmbeddedDBusDaemon
import org.freedesktop.dbus.connections.impl.DBusConnection
import org.freedesktop.dbus.connections.impl.DBusConnectionBuilder
import org.freedesktop.dbus.types.UInt32
import org.freedesktop.dbus.types.Variant
import org.junit.jupiter.api.io.TempDir
import kotlin.test.*
import kotlinx.coroutines.*

/** G18 with a stand-in portal on a bus of the test's own; the real desktop flip stays manual (GUI-SPEC §14.3). */
@OptIn(ExperimentalTestApi::class)
class DesktopSchemeTest {
  @TempDir lateinit var temporary: Path
  private lateinit var address: String
  private lateinit var daemon: EmbeddedDBusDaemon
  private val opened = mutableListOf<AutoCloseable>()

  @BeforeTest
  fun bus() {
    address = "unix:path=${temporary.resolve("bus")}"
    daemon = EmbeddedDBusDaemon("$address,listen=true").apply { startInBackgroundAndWait(5_000) }
  }

  @AfterTest
  fun stop() {
    opened.reversed().forEach { runCatching { it.close() } }
    daemon.close()
  }

  private fun connection(): DBusConnection = DBusConnectionBuilder.forAddress(address).withShared(false).build()

  /** The portal's Settings, answering [scheme] and announcing each change the way the real one does. */
  private inner class Portal(var scheme: Int) : PortalSettings {
    val bus = connection().also { opened += it }

    init {
      bus.requestBusName(DesktopScheme.PORTAL)
      bus.exportObject(DesktopScheme.PATH, this)
    }

    override fun ReadOne(namespace: String, key: String): Variant<*> = Variant(UInt32(scheme.toLong()))
    override fun getObjectPath() = DesktopScheme.PATH

    fun flip(to: Int) {
      scheme = to
      bus.sendMessage(PortalSettings.SettingChanged(DesktopScheme.PATH, DesktopScheme.NAMESPACE, DesktopScheme.KEY, Variant(UInt32(to.toLong()))))
    }
  }

  private fun ComposeUiTest.window(scheme: DesktopScheme) {
    setContent {
      LaunchedEffect(scheme) { scheme.watch() }
      val dark by scheme.dark.collectAsState()
      ProxenosTheme(dark) {
        Box(Modifier.size(800.dp, 500.dp)) { Shell(GuiState(attachment = Attachment.Absent(Reason.NotAnswering))) {} }
      }
    }
  }

  private fun ComposeUiTest.background(): Color = onRoot().captureToImage().toPixelMap().let { it[it.width - 2, it.height - 2] }

  @Test
  fun `flipping the desktop's scheme with the window open repaints it`() = runComposeUiTest {
    val portal = Portal(scheme = 1)
    window(DesktopScheme(connect = ::connection))
    val light = Color(0xFFFAFAFA)
    val dark = Color(0xFF18181B)
    waitUntil(timeoutMillis = 5_000) { background() == dark }
    portal.flip(0)
    waitUntil(timeoutMillis = 5_000) { background() == light }
    portal.flip(1)
    waitUntil(timeoutMillis = 5_000) { background() == dark }
    portal.flip(2)
    waitUntil(timeoutMillis = 5_000) { background() == light }
  }

  @Test
  fun `a bus with no portal is light and keeps listening`() = runComposeUiTest {
    val said = mutableListOf<String>()
    val scheme = DesktopScheme({ synchronized(said) { said += it } }, ::connection)
    window(scheme)
    waitUntil(timeoutMillis = 5_000) { synchronized(said) { said.isNotEmpty() } }
    assertFalse(scheme.dark.value)
    assertEquals(Color(0xFFFAFAFA), background())
  }

  @Test
  fun `a bus that never answers neither delays the window nor darkens it`() = runComposeUiTest {
    val silent = ServerSocketChannel.open(StandardProtocolFamily.UNIX).apply { bind(UnixDomainSocketAddress.of(temporary.resolve("silent"))) }
    val held = AtomicReference<SocketChannel>()
    val accepting = Thread { runCatching { held.set(silent.accept()) } }.apply { start() }
    opened += AutoCloseable { held.get()?.close(); silent.close(); accepting.join(1_000) }
    val scheme = DesktopScheme(connect = {
      DBusConnectionBuilder.forAddress("unix:path=${temporary.resolve("silent")}").withShared(false).build()
    })
    window(scheme)
    onNode(hasText("Workspaces") and hasClickAction()).assertExists()
    assertEquals(Color(0xFFFAFAFA), background())
    assertFalse(scheme.dark.value)
  }

  @Test
  fun `a bus that never answers does not hold the window's closing`() = runBlocking {
    val silent = ServerSocketChannel.open(StandardProtocolFamily.UNIX).apply { bind(UnixDomainSocketAddress.of(temporary.resolve("silent"))) }
    val held = AtomicReference<SocketChannel>()
    val accepting = Thread { runCatching { held.set(silent.accept()) } }.apply { start() }
    opened += AutoCloseable { held.get()?.close(); silent.close(); accepting.join(1_000) }
    // Not a child of runBlocking: a watcher that cannot be cancelled must fail this test, not hang it.
    val watching = CoroutineScope(Dispatchers.Default).launch {
      DesktopScheme(connect = {
        DBusConnectionBuilder.forAddress("unix:path=${temporary.resolve("silent")}").withShared(false).build()
      }).watch()
    }
    delay(300)
    assertTrue(watching.isActive, "the stand-in bus should still be keeping the subscription waiting")
    withTimeout(1_000) { watching.cancelAndJoin() }
  }
}
