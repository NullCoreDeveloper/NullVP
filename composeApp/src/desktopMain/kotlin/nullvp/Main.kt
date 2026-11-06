package nullvp

import androidx.compose.runtime.*
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Tray
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import nullvp.platform.DesktopPrivilegeManager
import nullvp.tray.DesktopTrayIcon

fun main() = application {
    var isWindowVisible by remember { mutableStateOf(true) }
    var isConnected by remember { mutableStateOf(false) }

    // Desktop System Tray Icon
    val trayIcon = remember(isConnected) {
        DesktopTrayIcon.createIcon(isConnected)
    }

    Tray(
        icon = trayIcon,
        tooltip = if (isConnected) "NullVP - Connected" else "NullVP - Disconnected",
        onAction = {
            // Left click: Toggle window visibility
            isWindowVisible = !isWindowVisible
        },
        menu = {
            Item(
                text = if (isWindowVisible) "Hide NullVP" else "Open NullVP",
                onClick = { isWindowVisible = !isWindowVisible }
            )
            Separator()
            Item(
                text = if (isConnected) "Disconnect" else "Connect",
                onClick = {
                    isConnected = !isConnected
                    if (isConnected) {
                        DesktopPrivilegeManager.enableSystemProxy()
                    } else {
                        DesktopPrivilegeManager.disableSystemProxy()
                    }
                }
            )
            Separator()
            Item(
                text = "Quit",
                onClick = {
                    DesktopPrivilegeManager.disableSystemProxy()
                    DesktopPrivilegeManager.stopDaemon()
                    exitApplication()
                }
            )
        }
    )

    // Main App Window (minimizes to tray on close 'X')
    if (isWindowVisible) {
        Window(
            onCloseRequest = {
                // Minimize to tray instead of exiting
                isWindowVisible = false
            },
            title = "NullVP - Modern VPN Client",
            state = rememberWindowState(width = 960.dp, height = 680.dp),
            icon = trayIcon
        ) {
            App()
        }
    }
}
