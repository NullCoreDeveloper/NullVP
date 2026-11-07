package nullvp

import androidx.compose.animation.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.ktor.client.*
import kotlinx.coroutines.launch
import nullvp.core.getPlatformCoreEngine
import nullvp.data.protocol.*
import nullvp.data.store.AppStateStore
import nullvp.data.store.LogLevel
import nullvp.data.subscription.SubscriptionManager
import nullvp.domain.model.OperationMode
import nullvp.domain.model.Profile
import nullvp.domain.protocol.ProtocolRegistry
import nullvp.i18n.Localization
import nullvp.platform.AppBackHandler
import nullvp.platform.AppExitHelper
import nullvp.platform.AppListHelper
import nullvp.platform.ClipboardHelper
import nullvp.platform.ConnectionInfo
import nullvp.platform.ConnectionInfoHelper
import nullvp.platform.PingHelper
import nullvp.platform.PrivilegeHelper
import nullvp.platform.UrlHelper
import nullvp.platform.rememberQrScanner
import nullvp.presentation.components.ConnectButton
import nullvp.presentation.components.ConnectState
import nullvp.presentation.components.NullVpTextField
import nullvp.presentation.components.QrCodeScannerIcon
import nullvp.presentation.components.ServerCard
import nullvp.presentation.components.SpeedRow
import nullvp.presentation.components.SubscriptionCard
import nullvp.presentation.dialogs.EditProfileDialog
import nullvp.presentation.dialogs.SudoPasswordDialog
import nullvp.presentation.dialogs.VkCaptchaDialog
import nullvp.presentation.logs.LogsScreen
import nullvp.presentation.settings.SettingsScreen
import nullvp.theme.*

enum class Screen {
    DASHBOARD,
    SERVERS,
    SUBSCRIPTIONS,
    SETTINGS,
    LOGS
}

@Composable
fun App() {
    val scope = rememberCoroutineScope()
    val coreEngine = remember { getPlatformCoreEngine() }

    // Register all supported protocols on startup
    LaunchedEffect(Unit) {
        ProtocolRegistry.register(MdnsProtocolHandler())
        ProtocolRegistry.register(VkTurnProtocolHandler())
        ProtocolRegistry.register(VlessProtocolHandler())
        ProtocolRegistry.register(Hysteria2ProtocolHandler())
        ProtocolRegistry.register(VmessProtocolHandler())
        ProtocolRegistry.register(TrojanProtocolHandler())
        ProtocolRegistry.register(ShadowsocksProtocolHandler())
    }

    // Reactive State from AppStateStore & CoreEngine
    val profiles by AppStateStore.profiles.collectAsState()
    val selectedProfileId by AppStateStore.selectedProfileId.collectAsState()
    val subscriptions by AppStateStore.subscriptions.collectAsState()
    val settings by AppStateStore.settings.collectAsState()
    val isRunning by coreEngine.isRunning.collectAsState()
    val isConnecting by coreEngine.isConnecting.collectAsState()
    val connectionError by coreEngine.connectionError.collectAsState()
    val stats by coreEngine.stats.collectAsState()
    val captchaUrl by coreEngine.currentCaptchaUrl.collectAsState()

    // Auto-restart VPN tunnel immediately when routing rules or settings change
    LaunchedEffect(isRunning, selectedProfileId) {
        AppStateStore.routingSettingsChanged.collect {
            val active = AppStateStore.profiles.value.find { it.id == AppStateStore.selectedProfileId.value }
            if (isRunning && active != null) {
                AppStateStore.addLog(LogLevel.INFO, "Routing", "Правила маршрутизации изменены: мгновенный перезапуск туннеля...")
                coreEngine.start(active)
            }
        }
    }

    val strings = Localization.get(settings.language)

    var currentScreen by remember { mutableStateOf(Screen.DASHBOARD) }
    var profileToEdit by remember { mutableStateOf<Profile?>(null) }

    // Exit confirmation dialog
    var showExitDialog by remember { mutableStateOf(false) }

    // Android BackHandler — из любого экрана → главный, из главного → диалог выхода
    AppBackHandler(enabled = currentScreen != Screen.DASHBOARD) {
        currentScreen = Screen.DASHBOARD
    }
    AppBackHandler(enabled = currentScreen == Screen.DASHBOARD) {
        showExitDialog = true
    }

    // Sudo & connection flow states
    var showSudoDialog by remember { mutableStateOf(false) }
    var pendingConnectOnSudo by remember { mutableStateOf(false) }

    // Ping states
    var isPingingActive by remember { mutableStateOf(false) }
    var isBatchPinging by remember { mutableStateOf(false) }
    var connectionInfoResult by remember { mutableStateOf<nullvp.platform.ConnectionInfo?>(null) }

    // Subscriptions import state
    var newSubUrl by remember { mutableStateOf("") }
    var newSubName by remember { mutableStateOf("") }
    var subImportError by remember { mutableStateOf<String?>(null) }
    var isSubLoading by remember { mutableStateOf(false) }

    val subscriptionManager = remember {
        SubscriptionManager(HttpClient())
    }

    var showQrSourceDialog by remember { mutableStateOf(false) }

    val handleImportContent: (String) -> Unit = remember(isRunning, settings.httpPort) {
        { rawText: String ->
            val text = rawText.trim()
            val activeHttpPort = if (isRunning) settings.httpPort else null
            if (text.startsWith("http://") || text.startsWith("https://")) {
                isSubLoading = true
                scope.launch {
                    subscriptionManager.fetchSubscription(text, null, proxyPort = activeHttpPort)
                        .onSuccess { (sub, nodes) ->
                            isSubLoading = false
                            AppStateStore.addSubscription(sub, nodes)
                            AppStateStore.addLog(LogLevel.INFO, "Import", "Подписка '${sub.name}' успешно импортирована (${nodes.size} серверов)")
                        }.onFailure { err ->
                            isSubLoading = false
                            subImportError = "Fetch error: ${err.message}"
                            AppStateStore.addLog(LogLevel.ERROR, "Import", "Ошибка загрузки: ${err.message}")
                        }
                }
            } else {
                val lines = text.split("\n", "\r\n")
                var addedCount = 0
                for (l in lines) {
                    val line = l.trim()
                    if (line.isBlank() || line.startsWith("#")) continue
                    val p = ProtocolRegistry.parse(line)
                    if (p != null) {
                        AppStateStore.addProfile(p)
                        addedCount++
                    }
                }
                if (addedCount == 0) {
                    val single = ProtocolRegistry.parse(text)
                    if (single != null) {
                        AppStateStore.addProfile(single)
                        addedCount++
                    }
                }
                if (addedCount > 0) {
                    AppStateStore.addLog(LogLevel.INFO, "Import", "Импортировано серверов: $addedCount")
                } else {
                    subImportError = if (settings.language.code == "ru") "В QR-коде не найдено поддерживаемых серверов или ссылок" else "No valid servers found in QR code"
                }
            }
        }
    }

    val qrScanner = rememberQrScanner(
        onResult = { text ->
            handleImportContent(text)
        },
        onError = { err ->
            subImportError = err
            AppStateStore.addLog(LogLevel.ERROR, "QR", err)
        }
    )

    val onQrClick = {
        if (AppListHelper.isAndroid()) {
            showQrSourceDialog = true
        } else {
            qrScanner.launchGallery()
        }
    }

    val activeProfile = profiles.find { it.id == selectedProfileId } ?: profiles.firstOrNull()

    val connectState = when {
        isRunning -> ConnectState.CONNECTED
        isConnecting -> ConnectState.CONNECTING
        else -> ConnectState.DISCONNECTED
    }

    NullVpTheme {
        BoxWithConstraints(modifier = Modifier.fillMaxSize().background(DarkBackground).safeDrawingPadding()) {
            val isWideScreen = maxWidth >= 680.dp

            Row(modifier = Modifier.fillMaxSize()) {
                // Wide Screen Custom Sidebar (Desktop / Tablet) - 100% bug-free WindowInsets
                if (isWideScreen) {
                    NullVpSidebar(
                        currentScreen = currentScreen,
                        onSelectScreen = { currentScreen = it },
                        strings = strings,
                        version = settings.appVersion
                    )
                }

                // Main Content Screen
                Scaffold(
                    containerColor = DarkBackground,
                    contentWindowInsets = WindowInsets(0, 0, 0, 0),
                    bottomBar = {
                        // Compact Mobile Bottom Bar
                        if (!isWideScreen) {
                            NavigationBar(
                                containerColor = DarkSurface,
                                contentColor = OnDarkSurface,
                                windowInsets = WindowInsets(0, 0, 0, 0)
                            ) {
                                NavigationBarItem(
                                    selected = currentScreen == Screen.DASHBOARD,
                                    onClick = { currentScreen = Screen.DASHBOARD },
                                    icon = { Icon(Icons.Default.Home, contentDescription = strings.navHome) },
                                    label = { Text(strings.navHome, fontSize = 10.sp) }
                                )
                                NavigationBarItem(
                                    selected = currentScreen == Screen.SERVERS,
                                    onClick = { currentScreen = Screen.SERVERS },
                                    icon = { Icon(Icons.AutoMirrored.Filled.List, contentDescription = strings.navServers) },
                                    label = { Text(strings.navServers, fontSize = 10.sp) }
                                )
                                NavigationBarItem(
                                    selected = currentScreen == Screen.SUBSCRIPTIONS,
                                    onClick = { currentScreen = Screen.SUBSCRIPTIONS },
                                    icon = { Icon(Icons.Default.Refresh, contentDescription = strings.navSubscriptions) },
                                    label = { Text(strings.navSubscriptions, fontSize = 10.sp) }
                                )
                                NavigationBarItem(
                                    selected = currentScreen == Screen.SETTINGS,
                                    onClick = { currentScreen = Screen.SETTINGS },
                                    icon = { Icon(Icons.Default.Settings, contentDescription = strings.navSettings) },
                                    label = { Text(strings.navSettings, fontSize = 10.sp) }
                                )
                                NavigationBarItem(
                                    selected = currentScreen == Screen.LOGS,
                                    onClick = { currentScreen = Screen.LOGS },
                                    icon = { Icon(Icons.Default.Info, contentDescription = strings.navLogs) },
                                    label = { Text(strings.navLogs, fontSize = 10.sp) }
                                )
                            }
                        }
                    }
                ) { innerPadding ->
                    Box(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
                        when (currentScreen) {
                            Screen.DASHBOARD -> {
                                Column(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .verticalScroll(rememberScrollState())
                                        .padding(24.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.spacedBy(20.dp)
                                ) {
                                    // Top Header with Branding and Status
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Column {
                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                Text(
                                                    text = "NullVP",
                                                    style = MaterialTheme.typography.headlineMedium,
                                                    fontWeight = FontWeight.ExtraBold,
                                                    color = NeonCyan
                                                )
                                                Spacer(Modifier.width(8.dp))
                                                Surface(
                                                    shape = RoundedCornerShape(6.dp),
                                                    color = NeonCyan.copy(alpha = 0.15f)
                                                ) {
                                                    Text(
                                                        text = settings.appVersion,
                                                        style = MaterialTheme.typography.labelSmall,
                                                        fontWeight = FontWeight.Bold,
                                                        color = NeonCyan,
                                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                                    )
                                                }
                                            }
                                        }

                                        // Status Chip
                                        Surface(
                                            shape = RoundedCornerShape(12.dp),
                                            color = when (connectState) {
                                                ConnectState.CONNECTED -> CyberGreen.copy(alpha = 0.18f)
                                                ConnectState.CONNECTING -> NeonCyan.copy(alpha = 0.18f)
                                                ConnectState.DISCONNECTED -> DarkSurfaceVariant
                                            },
                                            border = BorderStroke(
                                                1.dp,
                                                when (connectState) {
                                                    ConnectState.CONNECTED -> CyberGreen.copy(alpha = 0.6f)
                                                    ConnectState.CONNECTING -> NeonCyan.copy(alpha = 0.6f)
                                                    ConnectState.DISCONNECTED -> Color.Transparent
                                                }
                                            )
                                        ) {
                                            Row(
                                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                Box(
                                                    modifier = Modifier
                                                        .size(8.dp)
                                                        .clip(RoundedCornerShape(4.dp))
                                                        .background(
                                                            when (connectState) {
                                                                ConnectState.CONNECTED -> CyberGreen
                                                                ConnectState.CONNECTING -> NeonCyan
                                                                ConnectState.DISCONNECTED -> OnDarkSurfaceVariant
                                                            }
                                                        )
                                                )
                                                Spacer(Modifier.width(8.dp))
                                                Text(
                                                    text = when (connectState) {
                                                        ConnectState.CONNECTED -> strings.statusConnected.uppercase()
                                                        ConnectState.CONNECTING -> strings.statusConnecting.uppercase()
                                                        ConnectState.DISCONNECTED -> strings.statusDisconnected.uppercase()
                                                    },
                                                    style = MaterialTheme.typography.labelMedium,
                                                    fontWeight = FontWeight.Bold,
                                                    color = when (connectState) {
                                                        ConnectState.CONNECTED -> CyberGreen
                                                        ConnectState.CONNECTING -> NeonCyan
                                                        ConnectState.DISCONNECTED -> OnDarkSurfaceVariant
                                                    }
                                                )
                                            }
                                        }
                                    }

                                    // Connection error alert if any
                                    if (!connectionError.isNullOrBlank()) {
                                        Surface(
                                            shape = RoundedCornerShape(12.dp),
                                            color = CyberRed.copy(alpha = 0.15f),
                                            border = BorderStroke(1.dp, CyberRed.copy(alpha = 0.6f)),
                                            modifier = Modifier.fillMaxWidth()
                                        ) {
                                            Row(
                                                modifier = Modifier.padding(14.dp),
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                Icon(
                                                    Icons.Default.Warning,
                                                    contentDescription = null,
                                                    tint = CyberRed,
                                                    modifier = Modifier.size(22.dp)
                                                )
                                                Spacer(Modifier.width(10.dp))
                                                Text(
                                                    text = connectionError ?: "",
                                                    color = CyberRed,
                                                    style = MaterialTheme.typography.bodySmall,
                                                    fontWeight = FontWeight.Medium,
                                                    modifier = Modifier.weight(1f)
                                                )
                                            }
                                        }
                                    }

                                    Spacer(modifier = Modifier.height(6.dp))

                                    // Big Connect Button with Neon Ring
                                    ConnectButton(
                                        state = connectState,
                                        onClick = {
                                            if (isRunning) {
                                                coreEngine.stop()
                                            } else if (activeProfile != null) {
                                                if (settings.operationMode == OperationMode.TUN && PrivilegeHelper.needsSudoForTun) {
                                                    pendingConnectOnSudo = true
                                                    showSudoDialog = true
                                                } else {
                                                    coreEngine.start(activeProfile)
                                                }
                                            }
                                        }
                                    )

                                    // Realtime Speed Stats
                                    SpeedRow(
                                        rxSpeedBps = stats.rxSpeedBps,
                                        txSpeedBps = stats.txSpeedBps
                                    )

                                    // Selected Server Card & Details
                                    if (activeProfile != null) {
                                        Column(
                                            modifier = Modifier.fillMaxWidth(),
                                            verticalArrangement = Arrangement.spacedBy(10.dp)
                                        ) {
                                            ServerCard(
                                                profile = activeProfile,
                                                isSelected = true,
                                                onSelect = { currentScreen = Screen.SERVERS }
                                            )

                                            Card(
                                                shape = RoundedCornerShape(12.dp),
                                                colors = CardDefaults.cardColors(containerColor = DarkSurface),
                                                border = BorderStroke(1.dp, DarkSurfaceVariant),
                                                modifier = Modifier.fillMaxWidth()
                                            ) {
                                                Row(
                                                    modifier = Modifier.fillMaxWidth().padding(14.dp),
                                                    horizontalArrangement = Arrangement.SpaceBetween,
                                                    verticalAlignment = Alignment.CenterVertically
                                                ) {
                                                    Column(modifier = Modifier.weight(1f)) {
                                                        Text(
                                                            text = "Адрес: ${activeProfile.serverAddress}:${activeProfile.serverPort}",
                                                            style = MaterialTheme.typography.bodySmall,
                                                            color = OnDarkSurfaceVariant
                                                        )
                                                        Text(
                                                            text = "Протокол: ${activeProfile.protocolType.name}",
                                                            style = MaterialTheme.typography.bodySmall,
                                                            color = NeonCyan,
                                                            fontWeight = FontWeight.SemiBold
                                                        )
                                                    }

                                                    OutlinedButton(
                                                        onClick = {
                                                            if (!isPingingActive) {
                                                                isPingingActive = true
                                                                connectionInfoResult = null
                                                                scope.launch {
                                                                    val ping = PingHelper.measurePing(activeProfile.serverAddress, activeProfile.serverPort)
                                                                    val info = ConnectionInfoHelper.fetchServerInfo(activeProfile.serverAddress, if (ping >= 0) ping else 0L)
                                                                    val updated = activeProfile.copy(
                                                                        pingMs = ping,
                                                                        serverIp = info?.maskedIp,
                                                                        serverLocation = info?.let { "${it.country}${if (it.city.isNotBlank()) ", ${it.city}" else ""}" },
                                                                        serverFlag = info?.flag
                                                                    )
                                                                    AppStateStore.updateProfile(updated)
                                                                    connectionInfoResult = info
                                                                    isPingingActive = false
                                                                }
                                                            }
                                                        },
                                                        enabled = !isPingingActive,
                                                        colors = ButtonDefaults.outlinedButtonColors(contentColor = NeonCyan),
                                                        border = BorderStroke(1.dp, NeonCyan.copy(alpha = 0.6f)),
                                                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                                                        shape = RoundedCornerShape(8.dp)
                                                    ) {
                                                        if (isPingingActive) {
                                                            CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp, color = NeonCyan)
                                                            Spacer(Modifier.width(6.dp))
                                                            Text("Тест...", fontSize = 12.sp)
                                                        } else {
                                                            Text("⚡", fontSize = 13.sp)
                                                            Spacer(Modifier.width(4.dp))
                                                            Text(if (activeProfile.pingMs >= 0) "${activeProfile.pingMs} ms" else "Пинг", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                                        }
                                                    }
                                                }
                                            }

                                            // Результат пинга: IP + локация + задержка
                                            val displayInfo = connectionInfoResult ?: if (!activeProfile.serverLocation.isNullOrBlank()) {
                                                ConnectionInfo(
                                                    ip = activeProfile.serverIp ?: activeProfile.serverAddress,
                                                    maskedIp = activeProfile.serverIp ?: ConnectionInfoHelper.maskIp(activeProfile.serverAddress),
                                                    country = activeProfile.serverLocation ?: "",
                                                    countryCode = "",
                                                    city = "",
                                                    latencyMs = activeProfile.pingMs,
                                                    flag = activeProfile.serverFlag ?: "🌍"
                                                )
                                            } else null

                                            displayInfo?.let { info ->
                                                Surface(
                                                    shape = RoundedCornerShape(10.dp),
                                                    color = NeonCyan.copy(alpha = 0.08f),
                                                    border = BorderStroke(1.dp, NeonCyan.copy(alpha = 0.25f)),
                                                    modifier = Modifier.fillMaxWidth()
                                                ) {
                                                    Row(
                                                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                                                        verticalAlignment = Alignment.CenterVertically,
                                                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                                                    ) {
                                                        Text(info.flag, fontSize = 20.sp)
                                                        Column(modifier = Modifier.weight(1f)) {
                                                            Text(
                                                                text = "${info.country}${if (info.city.isNotBlank()) ", ${info.city}" else ""}",
                                                                fontSize = 13.sp,
                                                                fontWeight = FontWeight.SemiBold,
                                                                color = OnDarkSurface
                                                            )
                                                            Text(
                                                                text = info.maskedIp,
                                                                fontSize = 12.sp,
                                                                color = NeonCyan,
                                                                fontWeight = FontWeight.Medium
                                                            )
                                                        }
                                                        Surface(
                                                            shape = RoundedCornerShape(6.dp),
                                                            color = when {
                                                                info.latencyMs < 100 -> NeonCyan.copy(alpha = 0.2f)
                                                                info.latencyMs < 300 -> Color(0xFFFFD740).copy(alpha = 0.2f)
                                                                else -> CyberRed.copy(alpha = 0.2f)
                                                            }
                                                        ) {
                                                            Text(
                                                                text = "${info.latencyMs} ms",
                                                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                                                fontSize = 12.sp,
                                                                fontWeight = FontWeight.Bold,
                                                                color = when {
                                                                    info.latencyMs < 100 -> NeonCyan
                                                                    info.latencyMs < 300 -> Color(0xFFFFD740)
                                                                    else -> CyberRed
                                                                }
                                                            )
                                                        }
                                                    }
                                                }
                                            }
                                        }
                                    } else {
                                        Surface(
                                            shape = RoundedCornerShape(12.dp),
                                            color = DarkSurface,
                                            modifier = Modifier.fillMaxWidth().clickable { currentScreen = Screen.SERVERS }
                                        ) {
                                            Row(
                                                modifier = Modifier.padding(16.dp),
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.Center
                                            ) {
                                                Text(
                                                    text = strings.noProfileSelected,
                                                    color = OnDarkSurfaceVariant,
                                                    fontWeight = FontWeight.SemiBold
                                                )
                                            }
                                        }
                                    }
                                }
                            }

                            Screen.SERVERS -> {
                                Column(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .padding(20.dp)
                                ) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            text = "${strings.serversTitle} (${profiles.size})",
                                            style = if (isWideScreen) MaterialTheme.typography.titleLarge else MaterialTheme.typography.titleMedium,
                                            fontWeight = FontWeight.Bold,
                                            color = OnDarkSurface,
                                            modifier = Modifier.weight(1f, fill = false),
                                            maxLines = 1,
                                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                                        )
                                        Row(
                                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            // Batch Ping Button
                                            OutlinedButton(
                                                onClick = {
                                                    if (!isBatchPinging) {
                                                        isBatchPinging = true
                                                        scope.launch {
                                                            profiles.forEach { p ->
                                                                launch {
                                                                    val ping = PingHelper.measurePing(p.serverAddress, p.serverPort)
                                                                    val info = ConnectionInfoHelper.fetchServerInfo(p.serverAddress, if (ping >= 0) ping else 0L)
                                                                    val updated = p.copy(
                                                                        pingMs = ping,
                                                                        serverIp = info?.maskedIp,
                                                                        serverLocation = info?.let { "${it.country}${if (it.city.isNotBlank()) ", ${it.city}" else ""}" },
                                                                        serverFlag = info?.flag
                                                                    )
                                                                    AppStateStore.updateProfile(updated)
                                                                }
                                                            }
                                                            isBatchPinging = false
                                                        }
                                                    }
                                                },
                                                enabled = !isBatchPinging,
                                                colors = ButtonDefaults.outlinedButtonColors(contentColor = NeonCyan),
                                                border = BorderStroke(1.dp, NeonCyan.copy(alpha = 0.6f)),
                                                contentPadding = PaddingValues(horizontal = if (isWideScreen) 10.dp else 8.dp, vertical = 6.dp),
                                                shape = RoundedCornerShape(8.dp)
                                            ) {
                                                if (isBatchPinging) {
                                                    CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp, color = NeonCyan)
                                                } else {
                                                    Text("⚡", fontSize = 13.sp)
                                                }
                                                if (isWideScreen) {
                                                    Spacer(Modifier.width(4.dp))
                                                    Text("Пинг", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                                }
                                            }

                                            OutlinedButton(
                                                onClick = {
                                                    val clip = ClipboardHelper.getText()?.trim()
                                                    if (clip.isNullOrBlank()) {
                                                        subImportError = if (settings.language.code == "ru") "Буфер обмена пуст" else "Clipboard is empty"
                                                        return@OutlinedButton
                                                    }
                                                    handleImportContent(clip)
                                                },
                                                colors = ButtonDefaults.outlinedButtonColors(contentColor = NeonCyan),
                                                border = BorderStroke(1.dp, NeonCyan.copy(alpha = 0.6f)),
                                                contentPadding = PaddingValues(horizontal = if (isWideScreen) 10.dp else 8.dp, vertical = 6.dp),
                                                shape = RoundedCornerShape(8.dp)
                                            ) {
                                                Icon(PasteIcon, contentDescription = "Из буфера", modifier = Modifier.size(16.dp))
                                                if (isWideScreen) {
                                                    Spacer(Modifier.width(4.dp))
                                                    Text(if (settings.language.code == "ru") "Из буфера" else "From Clipboard", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                                }
                                            }

                                            OutlinedButton(
                                                onClick = onQrClick,
                                                colors = ButtonDefaults.outlinedButtonColors(contentColor = NeonCyan),
                                                border = BorderStroke(1.dp, NeonCyan.copy(alpha = 0.6f)),
                                                contentPadding = PaddingValues(horizontal = if (isWideScreen) 10.dp else 8.dp, vertical = 6.dp),
                                                shape = RoundedCornerShape(8.dp)
                                            ) {
                                                Icon(QrCodeScannerIcon, contentDescription = "QR-код", modifier = Modifier.size(16.dp))
                                                if (isWideScreen) {
                                                    Spacer(Modifier.width(4.dp))
                                                    Text("QR-код", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                                }
                                            }

                                            Button(
                                                onClick = { currentScreen = Screen.SUBSCRIPTIONS },
                                                colors = ButtonDefaults.buttonColors(containerColor = NeonCyan, contentColor = Color.Black),
                                                contentPadding = PaddingValues(horizontal = if (isWideScreen) 12.dp else 10.dp, vertical = 6.dp),
                                                shape = RoundedCornerShape(8.dp)
                                            ) {
                                                Icon(Icons.Default.Add, contentDescription = strings.addServer, modifier = Modifier.size(16.dp))
                                                if (isWideScreen) {
                                                    Spacer(Modifier.width(4.dp))
                                                    Text(strings.addServer, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                                }
                                            }
                                        }
                                    }

                                    Spacer(modifier = Modifier.height(14.dp))

                                    // Group servers by Subscription
                                    val subGroups = remember(profiles, subscriptions) {
                                        val result = mutableListOf<Pair<nullvp.domain.model.Subscription?, List<Profile>>>()
                                        val knownSubIds = subscriptions.map { it.id }.toSet()

                                        // 1. Subscriptions with servers
                                        subscriptions.forEach { sub ->
                                            val group = profiles.filter { it.subscriptionId == sub.id }
                                            if (group.isNotEmpty()) {
                                                result.add(sub to group)
                                            }
                                        }

                                        // 2. Standalone/manual servers without a known subscription
                                        val manual = profiles.filter { it.subscriptionId == null || !knownSubIds.contains(it.subscriptionId) }
                                        if (manual.isNotEmpty()) {
                                            result.add(null to manual)
                                        }

                                        result
                                    }

                                    var expandedSubIds by remember { mutableStateOf<Set<String>>(emptySet()) }

                                    val hasSubscriptions = subGroups.any { it.first != null }

                                    LaunchedEffect(subGroups.size, selectedProfileId) {
                                        if (expandedSubIds.isEmpty()) {
                                            val initial = mutableSetOf<String>()
                                            subGroups.forEach { (sub, groupProfiles) ->
                                                val key = sub?.id ?: "manual"
                                                if (groupProfiles.any { it.id == selectedProfileId } || subGroups.size == 1) {
                                                    initial.add(key)
                                                }
                                            }
                                            expandedSubIds = initial
                                        }
                                    }

                                    LazyColumn(
                                        verticalArrangement = Arrangement.spacedBy(10.dp),
                                        modifier = Modifier.fillMaxSize()
                                    ) {
                                        if (!hasSubscriptions) {
                                            // Single manual group without subscriptions: list directly
                                            itemsIndexed(profiles, key = { index, profile -> "${profile.id}_$index" }) { _, profile ->
                                                ServerCard(
                                                    profile = profile,
                                                    isSelected = profile.id == selectedProfileId,
                                                    onSelect = {
                                                        val prevId = selectedProfileId
                                                        AppStateStore.selectProfile(profile.id)
                                                        if (isRunning && prevId != profile.id) {
                                                            scope.launch {
                                                                coreEngine.start(profile)
                                                            }
                                                        }
                                                        currentScreen = Screen.DASHBOARD
                                                    },
                                                    onEdit = { profileToEdit = profile },
                                                    onDuplicate = { AppStateStore.duplicateProfile(profile) },
                                                    onDelete = { AppStateStore.deleteProfile(profile.id) }
                                                )
                                            }
                                        } else {
                                            // Multiple subscriptions / Subscriptions + Manual: collapsible groups
                                            subGroups.forEach { (sub, groupProfiles) ->
                                                val groupId = sub?.id ?: "manual"
                                                val isExpanded = expandedSubIds.contains(groupId)
                                                val groupTitle = sub?.name ?: if (settings.language.code == "ru") "Без подписки / Ручные" else "Manual / Custom"

                                                item(key = "header_$groupId") {
                                                    Surface(
                                                        shape = RoundedCornerShape(10.dp),
                                                        color = DarkSurfaceVariant.copy(alpha = 0.5f),
                                                        border = BorderStroke(1.dp, NeonCyan.copy(alpha = if (isExpanded) 0.35f else 0.12f)),
                                                        modifier = Modifier
                                                            .fillMaxWidth()
                                                            .clickable {
                                                                expandedSubIds = if (isExpanded) expandedSubIds - groupId else expandedSubIds + groupId
                                                            }
                                                    ) {
                                                        Row(
                                                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                                                            verticalAlignment = Alignment.CenterVertically,
                                                            horizontalArrangement = Arrangement.SpaceBetween
                                                        ) {
                                                            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                                                                Icon(
                                                                    imageVector = if (isExpanded) Icons.Default.ArrowDropDown else Icons.AutoMirrored.Filled.List,
                                                                    contentDescription = null,
                                                                    tint = NeonCyan,
                                                                    modifier = Modifier.size(20.dp)
                                                                )
                                                                Spacer(Modifier.width(8.dp))
                                                                Text(
                                                                    text = groupTitle,
                                                                    fontWeight = FontWeight.Bold,
                                                                    fontSize = 14.sp,
                                                                    color = OnDarkSurface,
                                                                    maxLines = 1,
                                                                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                                                                )
                                                                Spacer(Modifier.width(8.dp))
                                                                Surface(
                                                                    shape = RoundedCornerShape(6.dp),
                                                                    color = NeonCyan.copy(alpha = 0.15f)
                                                                ) {
                                                                    Text(
                                                                        text = "${groupProfiles.size}",
                                                                        fontSize = 11.sp,
                                                                        fontWeight = FontWeight.Bold,
                                                                        color = NeonCyan,
                                                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                                                    )
                                                                }
                                                            }

                                                            Text(
                                                                text = if (isExpanded) "Скрыть" else "Показать",
                                                                fontSize = 11.sp,
                                                                color = NeonCyan.copy(alpha = 0.8f),
                                                                fontWeight = FontWeight.Medium
                                                            )
                                                        }
                                                    }
                                                }

                                                if (isExpanded) {
                                                    itemsIndexed(groupProfiles, key = { index, profile -> "${profile.id}_$index" }) { _, profile ->
                                                        ServerCard(
                                                            profile = profile,
                                                            isSelected = profile.id == selectedProfileId,
                                                            onSelect = {
                                                                val prevId = selectedProfileId
                                                                AppStateStore.selectProfile(profile.id)
                                                                if (isRunning && prevId != profile.id) {
                                                                    scope.launch {
                                                                        coreEngine.start(profile)
                                                                    }
                                                                }
                                                                currentScreen = Screen.DASHBOARD
                                                            },
                                                            onEdit = { profileToEdit = profile },
                                                            onDuplicate = { AppStateStore.duplicateProfile(profile) },
                                                            onDelete = { AppStateStore.deleteProfile(profile.id) }
                                                        )
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }

                            Screen.SUBSCRIPTIONS -> {
                                Column(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .padding(20.dp)
                                ) {
                                    Text(
                                        text = strings.subscriptionsTitle,
                                        style = MaterialTheme.typography.titleLarge,
                                        fontWeight = FontWeight.Bold,
                                        color = OnDarkSurface
                                    )
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text(
                                        text = if (settings.language.code == "ru")
                                            "Импорт подписок 3x-ui по URL или единичных ссылок mdns://, vkturn://, hy2://, vless://"
                                        else
                                            "Import 3x-ui subscription URL or single node links (mdns://, vkturn://, hy2://, vless://)",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = OnDarkSurfaceVariant
                                    )

                                    Spacer(modifier = Modifier.height(16.dp))

                                    // Add Subscription / Import Box
                                    NullVpTextField(
                                        value = newSubName,
                                        onValueChange = { newSubName = it },
                                        placeholder = { Text(if (settings.language.code == "ru") "Название подписки (опционально)" else "Subscription name (optional)") },
                                        singleLine = true,
                                        showClearButton = true,
                                        modifier = Modifier.fillMaxWidth()
                                    )

                                    Spacer(modifier = Modifier.height(8.dp))

                                    NullVpTextField(
                                        value = newSubUrl,
                                        onValueChange = {
                                            newSubUrl = it
                                            subImportError = null
                                        },
                                        placeholder = { Text("https://... or vless:// or mdns:// or vkturn://...") },
                                        singleLine = true,
                                        showPasteButton = true,
                                        showClearButton = true,
                                        modifier = Modifier.fillMaxWidth()
                                    )

                                    if (subImportError != null) {
                                        Spacer(modifier = Modifier.height(6.dp))
                                        Text(text = subImportError!!, color = CyberRed, style = MaterialTheme.typography.bodySmall)
                                    }

                                    Spacer(modifier = Modifier.height(12.dp))

                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                                    ) {
                                        Button(
                                            onClick = {
                                                val input = newSubUrl.trim()
                                                val activeHttpPort = if (isRunning) settings.httpPort else null
                                                if (input.startsWith("http://") || input.startsWith("https://")) {
                                                    isSubLoading = true
                                                    scope.launch {
                                                        val res = subscriptionManager.fetchSubscription(input, newSubName.ifBlank { null }, proxyPort = activeHttpPort)
                                                        isSubLoading = false
                                                        res.onSuccess { (sub, nodes) ->
                                                            AppStateStore.addSubscription(sub, nodes)
                                                            newSubUrl = ""
                                                            newSubName = ""
                                                        }.onFailure { err ->
                                                            subImportError = "Fetch error: ${err.message}"
                                                        }
                                                    }
                                                } else {
                                                    val profile = ProtocolRegistry.parse(input)
                                                    if (profile != null) {
                                                        val finalProfile = if (newSubName.isNotBlank()) profile.copy(name = newSubName.trim()) else profile
                                                        AppStateStore.addProfile(finalProfile)
                                                        newSubUrl = ""
                                                        newSubName = ""
                                                        currentScreen = Screen.SERVERS
                                                    } else {
                                                        subImportError = "Unsupported or invalid link format"
                                                    }
                                                }
                                            },
                                            enabled = !isSubLoading && newSubUrl.isNotBlank(),
                                            modifier = Modifier.weight(1f),
                                            shape = RoundedCornerShape(10.dp),
                                            colors = ButtonDefaults.buttonColors(containerColor = NeonCyan, contentColor = Color.Black)
                                        ) {
                                            Text(
                                                if (isSubLoading) "Importing..." else strings.addSubscription,
                                                fontWeight = FontWeight.Bold
                                            )
                                        }

                                        OutlinedButton(
                                            onClick = onQrClick,
                                            colors = ButtonDefaults.outlinedButtonColors(contentColor = NeonCyan),
                                            border = BorderStroke(1.dp, NeonCyan.copy(alpha = 0.6f)),
                                            shape = RoundedCornerShape(10.dp)
                                        ) {
                                            Icon(QrCodeScannerIcon, contentDescription = "QR-код", modifier = Modifier.size(18.dp))
                                            Spacer(Modifier.width(6.dp))
                                            Text("QR-код", fontWeight = FontWeight.Bold)
                                        }
                                    }

                                    Spacer(modifier = Modifier.height(20.dp))

                                    Text(
                                        text = "${strings.subscriptionsTitle} (${subscriptions.size})",
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.Bold,
                                        color = OnDarkSurface
                                    )

                                    Spacer(modifier = Modifier.height(12.dp))

                                    LazyColumn(
                                        verticalArrangement = Arrangement.spacedBy(10.dp),
                                        modifier = Modifier.fillMaxSize()
                                    ) {
                                        itemsIndexed(subscriptions, key = { index, sub -> "${sub.id}_$index" }) { _, sub ->
                                            SubscriptionCard(
                                                subscription = sub,
                                                strings = strings,
                                                onRefresh = {
                                                    scope.launch {
                                                        val activeHttpPort = if (isRunning) settings.httpPort else null
                                                        subscriptionManager.fetchSubscription(sub.url, sub.name, proxyPort = activeHttpPort)
                                                            .onSuccess { (updatedSub, nodes) ->
                                                                AppStateStore.addSubscription(updatedSub, nodes)
                                                            }
                                                    }
                                                },
                                                onDelete = {
                                                    AppStateStore.deleteSubscription(sub.id)
                                                },
                                                onRename = { newName ->
                                                    AppStateStore.renameSubscription(sub.id, newName)
                                                }
                                            )
                                        }
                                    }
                                }
                            }

                            Screen.SETTINGS -> {
                                SettingsScreen()
                            }

                            Screen.LOGS -> {
                                LogsScreen()
                            }
                        }
                    }
                }
            }
        }

        // QR Code Source Choice Dialog (Android Camera vs Gallery)
        if (showQrSourceDialog) {
            AlertDialog(
                onDismissRequest = { showQrSourceDialog = false },
                containerColor = DarkSurface,
                title = {
                    Text(
                        text = if (settings.language.code == "ru") "Импорт по QR-коду" else "Import via QR Code",
                        color = OnDarkSurface,
                        fontWeight = FontWeight.Bold
                    )
                },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(
                            text = if (settings.language.code == "ru")
                                "Выберите способ сканирования QR-кода профиля или подписки:"
                            else
                                "Choose QR code import method:",
                            color = OnDarkSurfaceVariant,
                            fontSize = 13.sp
                        )

                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = DarkSurfaceVariant.copy(alpha = 0.5f),
                            border = BorderStroke(1.dp, NeonCyan.copy(alpha = 0.4f)),
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    showQrSourceDialog = false
                                    qrScanner.launchCamera()
                                }
                        ) {
                            Row(
                                modifier = Modifier.padding(14.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text("📷", fontSize = 20.sp)
                                Spacer(Modifier.width(12.dp))
                                Column {
                                    Text(
                                        text = if (settings.language.code == "ru") "Камера устройства" else "Device Camera",
                                        fontWeight = FontWeight.Bold,
                                        color = OnDarkSurface,
                                        fontSize = 14.sp
                                    )
                                    Text(
                                        text = if (settings.language.code == "ru") "Навести камеру на QR-код" else "Scan QR code with camera",
                                        color = OnDarkSurfaceVariant,
                                        fontSize = 11.sp
                                    )
                                }
                            }
                        }

                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = DarkSurfaceVariant.copy(alpha = 0.5f),
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.2f)),
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    showQrSourceDialog = false
                                    qrScanner.launchGallery()
                                }
                        ) {
                            Row(
                                modifier = Modifier.padding(14.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text("🖼️", fontSize = 20.sp)
                                Spacer(Modifier.width(12.dp))
                                Column {
                                    Text(
                                        text = if (settings.language.code == "ru") "Галерея / Скриншот" else "Gallery / Screenshot",
                                        fontWeight = FontWeight.Bold,
                                        color = OnDarkSurface,
                                        fontSize = 14.sp
                                    )
                                    Text(
                                        text = if (settings.language.code == "ru") "Выбрать изображение с QR-кодом" else "Choose saved image with QR code",
                                        color = OnDarkSurfaceVariant,
                                        fontSize = 11.sp
                                    )
                                }
                            }
                        }
                    }
                },
                confirmButton = {},
                dismissButton = {
                    TextButton(onClick = { showQrSourceDialog = false }) {
                        Text(strings.cancel, color = OnDarkSurfaceVariant)
                    }
                }
            )
        }

        // Sudo Password Dialog for TUN Mode
        if (showSudoDialog) {
            SudoPasswordDialog(
                isOpen = showSudoDialog,
                onDismiss = {
                    showSudoDialog = false
                    pendingConnectOnSudo = false
                },
                onConfirmed = {
                    showSudoDialog = false
                    AppStateStore.setOperationMode(OperationMode.TUN)
                    if (pendingConnectOnSudo && activeProfile != null) {
                        pendingConnectOnSudo = false
                        coreEngine.start(activeProfile)
                    }
                },
                onVerify = { pwd ->
                    PrivilegeHelper.verifyAndCacheSudoPassword(pwd)
                }
            )
        }

        // VK Captcha Dialog
        if (captchaUrl != null) {
            VkCaptchaDialog(
                captchaUrl = captchaUrl!!,
                onSubmit = { token -> coreEngine.submitCaptcha(token) },
                onDismiss = { coreEngine.submitCaptcha("") }
            )
        }

        // Edit Profile Dialog
        profileToEdit?.let { profile ->
            EditProfileDialog(
                profile = profile,
                onDismiss = { profileToEdit = null },
                onSave = { updated ->
                    AppStateStore.updateProfile(updated)
                    if (isRunning && updated.id == selectedProfileId) {
                        scope.launch {
                            coreEngine.start(updated)
                        }
                    }
                },
                onDelete = { id -> AppStateStore.deleteProfile(id) },
                onDuplicate = { p -> AppStateStore.duplicateProfile(p) }
            )
        }

        // Exit Confirmation Dialog (Android back button on Dashboard)
        if (showExitDialog) {
            AlertDialog(
                onDismissRequest = { showExitDialog = false },
                title = {
                    Text(
                        text = if (settings.language.code == "ru") "Выйти из приложения?" else "Exit application?",
                        fontWeight = FontWeight.Bold
                    )
                },
                text = {
                    Text(
                        text = if (settings.language.code == "ru")
                            if (isRunning) "VPN туннель будет остановлен." else "Вы уверены, что хотите выйти?"
                        else
                            if (isRunning) "VPN tunnel will be stopped." else "Are you sure you want to exit?",
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            showExitDialog = false
                            if (isRunning) coreEngine.stop()
                            AppExitHelper.exitApp()
                        }
                    ) {
                        Text(
                            text = if (settings.language.code == "ru") "Выйти" else "Exit",
                            color = CyberRed,
                            fontWeight = FontWeight.Bold
                        )
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showExitDialog = false }) {
                        Text(
                            text = if (settings.language.code == "ru") "Отмена" else "Cancel",
                            color = NeonCyan
                        )
                    }
                },
                containerColor = MaterialTheme.colorScheme.surface,
                shape = RoundedCornerShape(16.dp)
            )
        }
    }
}

@Composable
private fun NullVpSidebar(
    currentScreen: Screen,
    onSelectScreen: (Screen) -> Unit,
    strings: nullvp.i18n.AppStrings,
    version: String
) {
    Surface(
        color = DarkSurface,
        modifier = Modifier.fillMaxHeight().width(94.dp),
        border = BorderStroke(1.dp, DarkSurfaceVariant.copy(alpha = 0.5f))
    ) {
        Column(
            modifier = Modifier.fillMaxSize().padding(vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "NullVP",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.ExtraBold,
                color = NeonCyan
            )
            Text(
                text = version,
                style = MaterialTheme.typography.labelSmall,
                color = OnDarkSurfaceVariant,
                fontSize = 10.sp
            )

            Spacer(Modifier.height(28.dp))

            val navItems: List<Pair<Screen, Pair<ImageVector, String>>> = listOf(
                Screen.DASHBOARD to (Icons.Default.Home to strings.navHome),
                Screen.SERVERS to (Icons.AutoMirrored.Filled.List to strings.navServers),
                Screen.SUBSCRIPTIONS to (Icons.Default.Refresh to strings.navSubscriptions),
                Screen.SETTINGS to (Icons.Default.Settings to strings.navSettings),
                Screen.LOGS to (Icons.Default.Info to strings.navLogs)
            )

            navItems.forEach { (screen, item) ->
                val selected = currentScreen == screen
                val (icon, label) = item
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = if (selected) NeonCyan.copy(alpha = 0.15f) else Color.Transparent,
                    modifier = Modifier
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                        .fillMaxWidth()
                        .clickable { onSelectScreen(screen) }
                ) {
                    Column(
                        modifier = Modifier.padding(vertical = 10.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Icon(
                            imageVector = icon,
                            contentDescription = label,
                            tint = if (selected) NeonCyan else OnDarkSurfaceVariant,
                            modifier = Modifier.size(22.dp)
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = label,
                            fontSize = 11.sp,
                            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                            color = if (selected) NeonCyan else OnDarkSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}
