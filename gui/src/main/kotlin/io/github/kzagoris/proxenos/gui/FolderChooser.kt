package io.github.kzagoris.proxenos.gui

import io.github.kzagoris.proxenos.frontend.absoluteRoot
import io.github.vinceglb.filekit.FileKit
import io.github.vinceglb.filekit.PlatformFile
import io.github.vinceglb.filekit.dialogs.FileKitDialogParent
import io.github.vinceglb.filekit.dialogs.FileKitDialogSettings
import io.github.vinceglb.filekit.dialogs.openDirectoryPicker
import io.github.vinceglb.filekit.path
import java.awt.Window
import java.util.concurrent.CompletableFuture
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.future.await
import org.freedesktop.dbus.connections.impl.DBusConnectionBuilder
import org.freedesktop.dbus.interfaces.Properties
import org.freedesktop.dbus.types.UInt32

/** The desktop boundary: null is the user cancelling, cancellation belongs to the window. */
interface FolderChooser {
  suspend fun available(): Boolean
  suspend fun choose(root: String): String?
}

internal class PortalFolderChooser(private val window: Window, private val log: (String) -> Unit) : FolderChooser {
  override suspend fun available(): Boolean = CompletableFuture.supplyAsync {
    // FileKit falls back to Swing when this property is absent. GUI-SPEC §7 requires the path
    // field instead. A daemon supplier keeps an unanswered D-Bus call from holding window close.
    try {
      DBusConnectionBuilder.forSessionBus().withShared(false).build().use { connection ->
        connection.getRemoteObject(DesktopScheme.PORTAL, DesktopScheme.PATH, Properties::class.java)
          .Get<UInt32>("org.freedesktop.portal.FileChooser", "version")
        true
      }
    } catch (_: Exception) {
      false
    }
  }.await()

  override suspend fun choose(root: String): String? = try {
    FileKit.openDirectoryPicker(
      directory = root.takeIf { it.isNotBlank() }?.let { PlatformFile(absoluteRoot(it).toString()) },
      dialogSettings = FileKitDialogSettings(title = "Choose the Workspace Root", parent = FileKitDialogParent.awt(window)),
    )?.path
  } catch (cancelled: CancellationException) {
    throw cancelled
  } catch (failed: Exception) {
    currentCoroutineContext().ensureActive()
    log("Could not choose a Root: ${failed.message ?: failed.javaClass.simpleName}")
    throw failed
  }
}
