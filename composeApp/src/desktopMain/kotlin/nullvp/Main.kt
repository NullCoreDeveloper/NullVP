package nullvp

import androidx.compose.runtime.*
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import dev.nucleusframework.composenativetray.tray.api.Tray
import nullvp.data.store.AppStateStore
import nullvp.domain.model.OperationMode
import nullvp.i18n.Localization
import nullvp.platform.DesktopPrivilegeManager
import nullvp.tray.DesktopTrayIcon

fun main() = application {
    var isWindowVisible by remember { mutableStateOf(true) }
    var isConnected by remember { mutableStateOf(false) }
    val windowState = rememberWindowState(width = 1020.dp, height = 720.dp)

    val settings by AppStateStore.settings.collectAsState()
    val strings = Localization.get(settings.language)

    // Desktop System Tray Icon
    val trayIcon = remember(isConnected) {
        DesktopTrayIcon.createIcon(isConnected)
    }

    // Native Wayland & X11 System Tray (libLinuxTray.so + StatusNotifierItem DBus)
    Tray(
        icon = trayIcon,
        tooltip = "NullVP — ${if (isConnected) strings.statusConnected else strings.statusDisconnected}",
        primaryAction = {
            // Left click: Toggle window visibility and restore
            isWindowVisible = !isWindowVisible
            if (isWindowVisible) {
                windowState.isMinimized = false
            }
        }
    ) {
        Item(
            label = if (isWindowVisible) "Свернуть в трей" else "Открыть NullVP",
            onClick = {
                isWindowVisible = !isWindowVisible
                if (isWindowVisible) {
                    windowState.isMinimized = false
                }
            }
        )
        Divider()
        Item(
            label = if (isConnected) "⏹ ${strings.btnDisconnect}" else "▶ ${strings.btnConnect}",
            onClick = {
                isConnected = !isConnected
                if (isConnected) {
                    when (settings.operationMode) {
                        OperationMode.SYSTEM_PROXY -> DesktopPrivilegeManager.enableSystemProxy()
                        OperationMode.TUN -> DesktopPrivilegeManager.startElevatedDaemon("/usr/bin/nullvp-core", "/tmp/nullvp-core.sock")
                        OperationMode.PROXY_ONLY -> { /* local proxy only */ }
                    }
                } else {
                    DesktopPrivilegeManager.disableSystemProxy()
                    DesktopPrivilegeManager.stopDaemon()
                }
            }
        )
        Item(
            label = when (settings.operationMode) {
                OperationMode.TUN -> "Режим: TUN"
                OperationMode.SYSTEM_PROXY -> "Режим: Системный прокси"
                OperationMode.PROXY_ONLY -> "Режим: Только прокси"
            },
            onClick = {
                val nextMode = when (settings.operationMode) {
                    OperationMode.TUN -> OperationMode.SYSTEM_PROXY
                    OperationMode.SYSTEM_PROXY -> OperationMode.PROXY_ONLY
                    OperationMode.PROXY_ONLY -> OperationMode.TUN
                }
                AppStateStore.setOperationMode(nextMode)
            }
        )
        Divider()
        Item(
            label = if (settings.language.code == "ru") "Выход" else "Quit",
            onClick = {
                DesktopPrivilegeManager.disableSystemProxy()
                DesktopPrivilegeManager.stopDaemon()
                exitApplication()
            }
        )
    }

    // Main App Window
    Window(
        visible = isWindowVisible,
        onCloseRequest = {
            if (settings.minimizeToTrayOnClose) {
                isWindowVisible = false
            } else {
                DesktopPrivilegeManager.disableSystemProxy()
                DesktopPrivilegeManager.stopDaemon()
                exitApplication()
            }
        },
        title = "NullVP — Modern VPN & Proxy Client",
        state = windowState,
        icon = trayIcon
    ) {
        App()
    }
}
