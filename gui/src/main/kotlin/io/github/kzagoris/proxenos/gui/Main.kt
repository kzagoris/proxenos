package io.github.kzagoris.proxenos.gui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import io.github.kzagoris.proxenos.control.ConfigRefused
import io.github.kzagoris.proxenos.control.ControlSocket
import io.github.kzagoris.proxenos.coreapi.ActivityEntryId
import io.github.kzagoris.proxenos.frontend.Attachment
import io.github.kzagoris.proxenos.frontend.RuntimeAttachment
import java.awt.Toolkit
import java.nio.file.Path
import java.time.Instant
import kotlin.system.exitProcess

fun main(args: Array<String>) {
  GuiLog().use { log ->
    if (System.getenv("DISPLAY").isNullOrBlank()) {
      log.say("DISPLAY is not set; Proxenos needs X11 or XWayland to open a window.")
      exitProcess(78)
    }
    val socket = try {
      val flag = when {
        args.isEmpty() -> null
        args.size == 2 && args[0] == "--control-socket" -> Path.of(args[1])
        else -> throw IllegalArgumentException("usage: gui [--control-socket PATH]")
      }
      ControlSocket.resolve(System.getenv(), flag)
    } catch (refused: ConfigRefused) {
      log.say(refused.message ?: "Could not resolve the control socket.")
      exitProcess(78)
    } catch (usage: IllegalArgumentException) {
      log.say(usage.message ?: "usage: gui [--control-socket PATH]")
      exitProcess(64)
    }
    // XToolkit initializes this internal field from main's class name. Set it after toolkit
    // initialization and before any window; bin/gui opens this JDK 26 package deliberately.
    val toolkit = Toolkit.getDefaultToolkit()
    toolkit.javaClass.getDeclaredField("awtAppClassName").apply { isAccessible = true }.set(null, "proxenos")
    val smoke = System.getenv("PROXENOS_GUI_SMOKE") == "1"
    // Evidence for GUI-SPEC §14.4, never a gate: when a frame first draws a newer Activity entry.
    val trace = System.getenv("PROXENOS_GUI_TRACE") == "1"
    var traced: ActivityEntryId? = null
    // A family that is not installed falls back silently, so the log names the one asked for.
    val fontName = Fonts.desktopName()
    log.say("UI font asked for: ${fontName ?: "none named; the default"}")
    val fonts = Fonts.named(fontName)
    val scale = sourceGuiScale(System.getenv(), log::say)
    GuiOwner(RuntimeAttachment(socket, RuntimeAttachment.executable())).use { owner ->
      application(exitProcessOnExit = false) {
        val state by owner.state.collectAsState()
        var drawn by remember { mutableStateOf(false) }
        val close = { owner.close(); exitApplication() }
        // The window opens light at once; the portal answers off the startup path (GUI-SPEC §8).
        val scheme = remember { DesktopScheme(log::say) }
        LaunchedEffect(scheme) { scheme.watch() }
        val dark by scheme.dark.collectAsState()
        // The native window still uses the JVM density; LocalDensity only sizes its content (§9).
        val nativeDensity = LocalDensity.current.density
        val windowRatio = (scale ?: nativeDensity) / nativeDensity
        Window(onCloseRequest = close, title = "Proxenos",
          state = rememberWindowState(width = (1100 * windowRatio).dp, height = (760 * windowRatio).dp),
          onPreviewKeyEvent = { event -> shortcut(event, state, owner::accept, close) },
        ) {
          val chooser = remember(window) { PortalFolderChooser(window, log::say) }
          GuiDensity(scale) {
            ProxenosTheme(dark, fonts) {
              Box(Modifier.fillMaxSize().drawWithContent {
                drawContent()
                if (!drawn) {
                  drawn = true
                  log.say(if (trace) "first frame at=${Instant.now()}" else "first frame")
                }
                // Reading state here makes this draw follow every change while tracing, and only then.
                if (trace) {
                  val activity = state.snapshot?.activity.orEmpty()
                  val newest = activity.lastOrNull()
                  if (newest != null && newest.id != traced) {
                    traced = newest.id
                    log.say("frame at=${Instant.now()} newest=${newest.at} entries=${activity.size}")
                  }
                }
              }) { Shell(state, owner::accept, chooser) }
            }
          }
        }
        LaunchedEffect(state.runtimeWords) { log.say(state.runtimeWords) }
        LaunchedEffect(drawn) { if (drawn) owner.open() }
        LaunchedEffect(drawn, state.attachment is Attachment.Attached) {
          if (smoke && drawn && state.attachment is Attachment.Attached) {
            withFrameNanos { }
            close()
          }
        }
      }
    }
  }
}
