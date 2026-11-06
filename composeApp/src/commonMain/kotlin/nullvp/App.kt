package nullvp

import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import nullvp.data.subscription.SubscriptionManager
import nullvp.domain.model.OperationMode
import nullvp.domain.model.Profile
import nullvp.domain.protocol.ProtocolRegistry
import nullvp.i18n.Localization
import nullvp.presentation.components.ConnectButton
import nullvp.presentation.components.ConnectState
import nullvp.presentation.components.ServerCard
import nullvp.presentation.components.SpeedRow
import nullvp.presentation.components.SubscriptionCard
import nullvp.presentation.dialogs.EditProfileDialog
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
    val stats by coreEngine.stats.collectAsState()

    val strings = Localization.get(settings.language)

    var currentScreen by remember { mutableStateOf(Screen.DASHBOARD) }
    var profileToEdit by remember { mutableStateOf<Profile?>(null) }

    // Subscriptions import state
    var newSubUrl by remember { mutableStateOf("") }
    var newSubName by remember { mutableStateOf("") }
    var subImportError by remember { mutableStateOf<String?>(null) }
    var isSubLoading by remember { mutableStateOf(false) }

    val subscriptionManager = remember {
        SubscriptionManager(HttpClient())
    }

    val activeProfile = profiles.find { it.id == selectedProfileId } ?: profiles.firstOrNull()

    val connectState = when {
        isRunning -> ConnectState.CONNECTED
        else -> ConnectState.DISCONNECTED
    }

    NullVpTheme {
        BoxWithConstraints(modifier = Modifier.fillMaxSize().background(DarkBackground)) {
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
                                            border = androidx.compose.foundation.BorderStroke(
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

                                    Spacer(modifier = Modifier.height(10.dp))

                                    // Big Connect Button with Neon Ring
                                    ConnectButton(
                                        state = connectState,
                                        onClick = {
                                            if (isRunning) {
                                                coreEngine.stop()
                                            } else if (activeProfile != null) {
                                                coreEngine.start(activeProfile)
                                            }
                                        }
                                    )

                                    // Operation Mode Switcher Pills
                                    Card(
                                        shape = RoundedCornerShape(14.dp),
                                        colors = CardDefaults.cardColors(containerColor = DarkSurface),
                                        border = androidx.compose.foundation.BorderStroke(1.dp, DarkSurfaceVariant)
                                    ) {
                                        Column(
                                            modifier = Modifier.padding(12.dp),
                                            horizontalAlignment = Alignment.CenterHorizontally
                                        ) {
                                            Text(
                                                text = strings.modeTitle,
                                                style = MaterialTheme.typography.labelMedium,
                                                color = OnDarkSurfaceVariant,
                                                fontWeight = FontWeight.SemiBold
                                            )
                                            Spacer(Modifier.height(8.dp))
                                            Row(
                                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                val modeItems = listOf(
                                                    OperationMode.TUN to "TUN",
                                                    OperationMode.SYSTEM_PROXY to "Системный прокси",
                                                    OperationMode.PROXY_ONLY to "Только прокси"
                                                )
                                                modeItems.forEach { (mode, label) ->
                                                    val selected = settings.operationMode == mode
                                                    FilterChip(
                                                        selected = selected,
                                                        onClick = { AppStateStore.setOperationMode(mode) },
                                                        label = { Text(label, fontSize = 12.sp, fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal) },
                                                        colors = FilterChipDefaults.filterChipColors(
                                                            selectedContainerColor = NeonCyan.copy(alpha = 0.25f),
                                                            selectedLabelColor = NeonCyan
                                                        ),
                                                        border = FilterChipDefaults.filterChipBorder(
                                                            enabled = true,
                                                            selected = selected,
                                                            borderColor = if (selected) NeonCyan else Color.Transparent
                                                        )
                                                    )
                                                }
                                            }
                                        }
                                    }

                                    // Realtime Speed Stats
                                    SpeedRow(
                                        rxSpeedBps = stats.rxSpeedBps,
                                        txSpeedBps = stats.txSpeedBps
                                    )

                                    // Selected Server Card
                                    if (activeProfile != null) {
                                        ServerCard(
                                            profile = activeProfile,
                                            isSelected = true,
                                            onSelect = { currentScreen = Screen.SERVERS }
                                        )
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
                                            style = MaterialTheme.typography.titleLarge,
                                            fontWeight = FontWeight.Bold,
                                            color = OnDarkSurface
                                        )
                                        Button(
                                            onClick = { currentScreen = Screen.SUBSCRIPTIONS },
                                            colors = ButtonDefaults.buttonColors(containerColor = NeonCyan, contentColor = Color.Black),
                                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                                            shape = RoundedCornerShape(8.dp)
                                        ) {
                                            Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                                            Spacer(Modifier.width(4.dp))
                                            Text(strings.addServer, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                        }
                                    }

                                    Spacer(modifier = Modifier.height(14.dp))

                                    LazyColumn(
                                        verticalArrangement = Arrangement.spacedBy(10.dp),
                                        modifier = Modifier.fillMaxSize()
                                    ) {
                                        items(profiles, key = { it.id }) { profile ->
                                            Row(
                                                modifier = Modifier.fillMaxWidth(),
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                Box(modifier = Modifier.weight(1f)) {
                                                    ServerCard(
                                                        profile = profile,
                                                        isSelected = profile.id == selectedProfileId,
                                                        onSelect = {
                                                            AppStateStore.selectProfile(profile.id)
                                                            currentScreen = Screen.DASHBOARD
                                                        }
                                                    )
                                                }
                                                IconButton(
                                                    onClick = { profileToEdit = profile },
                                                    modifier = Modifier.padding(start = 4.dp)
                                                ) {
                                                    Icon(
                                                        Icons.Default.Edit,
                                                        contentDescription = strings.editServer,
                                                        tint = OnDarkSurfaceVariant
                                                    )
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
                                    OutlinedTextField(
                                        value = newSubUrl,
                                        onValueChange = {
                                            newSubUrl = it
                                            subImportError = null
                                        },
                                        placeholder = { Text("https://... or vless:// or mdns://...") },
                                        singleLine = true,
                                        modifier = Modifier.fillMaxWidth(),
                                        shape = RoundedCornerShape(10.dp),
                                        colors = OutlinedTextFieldDefaults.colors(
                                            focusedBorderColor = NeonCyan,
                                            unfocusedBorderColor = DarkSurfaceVariant,
                                            focusedTextColor = OnDarkSurface,
                                            unfocusedTextColor = OnDarkSurface
                                        )
                                    )

                                    if (subImportError != null) {
                                        Spacer(modifier = Modifier.height(6.dp))
                                        Text(text = subImportError!!, color = CyberRed, style = MaterialTheme.typography.bodySmall)
                                    }

                                    Spacer(modifier = Modifier.height(12.dp))

                                    Button(
                                        onClick = {
                                            val input = newSubUrl.trim()
                                            if (input.startsWith("http://") || input.startsWith("https://")) {
                                                isSubLoading = true
                                                scope.launch {
                                                    val res = subscriptionManager.fetchSubscription(input, newSubName.ifBlank { null })
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
                                                    AppStateStore.addProfile(profile)
                                                    newSubUrl = ""
                                                    currentScreen = Screen.SERVERS
                                                } else {
                                                    subImportError = "Unsupported or invalid link format"
                                                }
                                            }
                                        },
                                        enabled = !isSubLoading && newSubUrl.isNotBlank(),
                                        modifier = Modifier.fillMaxWidth(),
                                        shape = RoundedCornerShape(10.dp),
                                        colors = ButtonDefaults.buttonColors(containerColor = NeonCyan, contentColor = Color.Black)
                                    ) {
                                        Text(
                                            if (isSubLoading) "Importing..." else strings.addSubscription,
                                            fontWeight = FontWeight.Bold
                                        )
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
                                        items(subscriptions, key = { it.id }) { sub ->
                                            SubscriptionCard(
                                                subscription = sub,
                                                onRefresh = {
                                                    scope.launch {
                                                        subscriptionManager.fetchSubscription(sub.url, sub.name)
                                                            .onSuccess { (updatedSub, nodes) ->
                                                                AppStateStore.addSubscription(updatedSub, nodes)
                                                            }
                                                    }
                                                },
                                                onDelete = {
                                                    AppStateStore.deleteSubscription(sub.id)
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

        // Edit Profile Dialog
        profileToEdit?.let { profile ->
            EditProfileDialog(
                profile = profile,
                onDismiss = { profileToEdit = null },
                onSave = { updated -> AppStateStore.updateProfile(updated) },
                onDelete = { id -> AppStateStore.deleteProfile(id) },
                onDuplicate = { p -> AppStateStore.duplicateProfile(p) }
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
        border = androidx.compose.foundation.BorderStroke(1.dp, DarkSurfaceVariant.copy(alpha = 0.5f))
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
