package io.github.kzagoris.proxenos.gui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.input.key.*
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import io.github.kzagoris.proxenos.control.ConfigRefused
import io.github.kzagoris.proxenos.control.ControlSocket
import io.github.kzagoris.proxenos.frontend.Attachment
import io.github.kzagoris.proxenos.frontend.RuntimeAttachment
import java.awt.Toolkit
import java.nio.file.Path
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
    GuiOwner(RuntimeAttachment(socket, RuntimeAttachment.executable())).use { owner ->
      application(exitProcessOnExit = false) {
        val state by owner.state.collectAsState()
        var drawn by remember { mutableStateOf(false) }
        val close = { owner.close(); exitApplication() }
        Window(onCloseRequest = close, title = "Proxenos",
          state = rememberWindowState(width = 640.dp, height = 480.dp),
          onPreviewKeyEvent = { event ->
            if (event.type == KeyEventType.KeyDown && event.isCtrlPressed && event.key == Key.W) {
              close()
              true
            } else false
          },
        ) {
          MaterialTheme {
            Surface(Modifier.fillMaxSize().drawWithContent {
              drawContent()
              if (!drawn) {
                drawn = true
                log.say("first frame")
              }
            }) { RuntimeWindow(state, owner::accept) }
          }
        }
        LaunchedEffect(state.runtimeWords) { log.say(state.runtimeWords) }
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
