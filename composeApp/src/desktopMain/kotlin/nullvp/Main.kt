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

import nullvp.core.getPlatformCoreEngine

fun main() = application {
    val engine = remember { getPlatformCoreEngine() }
    val isRunning by engine.isRunning.collectAsState()
    val isConnecting by engine.isConnecting.collectAsState()
    val profiles by AppStateStore.profiles.collectAsState()
    val selectedProfileId by AppStateStore.selectedProfileId.collectAsState()
    val activeProfile = profiles.firstOrNull { it.id == selectedProfileId }

    var isWindowVisible by remember { mutableStateOf(true) }
    val windowState = rememberWindowState(width = 1020.dp, height = 720.dp)

    val settings by AppStateStore.settings.collectAsState()
    val strings = Localization.get(settings.language)

    // Desktop System Tray Icon
    val trayIcon = remember(isRunning) {
        DesktopTrayIcon.createIcon(isRunning)
    }

    // Native Wayland & X11 System Tray (libLinuxTray.so + StatusNotifierItem DBus)
    Tray(
        icon = trayIcon,
        tooltip = "NullVP — ${if (isRunning) "${strings.statusConnected} (${activeProfile?.name ?: ""})" else strings.statusDisconnected}",
        primaryAction = {
            // Left click: Toggle window visibility and restore
            isWindowVisible = true
            windowState.isMinimized = false
        }
    ) {
        Item(
            label = if (isWindowVisible) "Скрыть окно" else "Открыть NullVP",
            onClick = {
                isWindowVisible = !isWindowVisible
                if (isWindowVisible) {
                    windowState.isMinimized = false
                }
            }
        )
        Divider()
        Item(
            label = if (isRunning) "⏹ ${strings.btnDisconnect}" else "▶ ${strings.btnConnect}",
            isEnabled = !isConnecting,
            onClick = {
                if (isRunning) {
                    engine.stop()
                } else {
                    val p = profiles.firstOrNull { it.id == selectedProfileId } ?: profiles.firstOrNull()
                    if (p != null) {
                        engine.start(p)
                    } else {
                        isWindowVisible = true
                        windowState.isMinimized = false
                    }
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
                engine.stop()
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
