package io.github.kzagoris.proxenos.gui

import java.util.concurrent.CompletableFuture
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.future.await
import org.freedesktop.dbus.annotations.DBusInterfaceName
import org.freedesktop.dbus.connections.impl.DBusConnection
import org.freedesktop.dbus.connections.impl.DBusConnectionBuilder
import org.freedesktop.dbus.interfaces.DBusInterface
import org.freedesktop.dbus.messages.DBusSignal
import org.freedesktop.dbus.types.UInt32
import org.freedesktop.dbus.types.Variant

/** The portal's settings interface, the two members this GUI uses. */
@DBusInterfaceName("org.freedesktop.portal.Settings")
interface PortalSettings : DBusInterface {
  @Suppress("FunctionName")
  fun ReadOne(namespace: String, key: String): Variant<*>

  class SettingChanged(path: String, val namespace: String, val key: String, val value: Variant<*>) :
    DBusSignal(path, namespace, key, value)
}

/**
 * The desktop's light/dark while the window is open (GUI-SPEC §8). `isSystemInDarkTheme()` reads
 * once per launch; this follows the portal's `color-scheme` live. A `uint32` 1 is dark; 0, 2, an
 * error or no bus is light — and stays whatever it last was if the bus or portal restarts.
 *
 * [connect] is the session bus by default; a test passes a bus of its own.
 */
class DesktopScheme(
  private val log: (String) -> Unit = {},
  private val connect: () -> DBusConnection = { DBusConnectionBuilder.forSessionBus().withShared(false).build() },
) {
  private val current = MutableStateFlow(false)
  val dark: StateFlow<Boolean> = current.asStateFlow()

  /**
   * Subscribes until cancelled. With no session bus dbus-java takes 9.7 s to fail (measured), and
   * a bus that accepts and never answers blocks for as long as it likes: neither may hold the
   * window. So the blocking calls run on a daemon pool thread this coroutine only waits for, and a
   * cancelled wait leaves it behind — closing what it opened whenever it finishes — rather than
   * keeping the application alive until it does.
   */
  suspend fun watch() {
    val subscription = CompletableFuture.supplyAsync(::subscribe)
    try {
      // await() cancels the future it waits on; wait on a copy, so the original still completes
      // and the close below still runs.
      subscription.thenApply { it }.await()
      awaitCancellation()
    } finally {
      subscription.thenAcceptAsync { it?.close() }
    }
  }

  /** The handler first, then ReadOne, so no flip falls between them. Null when there is no bus. */
  private fun subscribe(): AutoCloseable? {
    val connection = try {
      connect()
    } catch (failed: Exception) {
      log("no session bus (${failed.javaClass.simpleName}); light")
      return null
    }
    return try {
      val settings = connection.getRemoteObject(PORTAL, PATH, PortalSettings::class.java)
      val lock = Any()
      var signalled = false
      // ReadOne's older answer must not overwrite a signal that arrived while it was on its way.
      val handler = connection.addSigHandler(PortalSettings.SettingChanged::class.java, settings) { signal ->
        if (signal.namespace == NAMESPACE && signal.key == KEY) synchronized(lock) {
          signalled = true
          current.value = isDark(signal.value)
        }
      }
      try {
        val value = settings.ReadOne(NAMESPACE, KEY)
        synchronized(lock) { if (!signalled) current.value = isDark(value) }
      } catch (failed: Exception) {
        log("the desktop portal did not answer for color-scheme (${failed.javaClass.simpleName}); light until it signals")
      }
      AutoCloseable {
        handler.close()
        connection.disconnect()
      }
    } catch (failed: Exception) {
      connection.disconnect()
      log("could not subscribe to the desktop portal (${failed.javaClass.simpleName}); light")
      null
    }
  }

  private fun isDark(value: Variant<*>): Boolean = (value.value as? UInt32)?.toInt() == 1

  companion object {
    const val PORTAL = "org.freedesktop.portal.Desktop"
    const val PATH = "/org/freedesktop/portal/desktop"
    const val NAMESPACE = "org.freedesktop.appearance"
    const val KEY = "color-scheme"
  }
}
