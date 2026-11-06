package nullvp

import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.ktor.client.*
import kotlinx.coroutines.launch
import nullvp.core.getPlatformCoreEngine
import nullvp.data.protocol.*
import nullvp.data.store.AppStateStore
import nullvp.data.subscription.SubscriptionManager
import nullvp.domain.model.Profile
import nullvp.domain.protocol.ProtocolRegistry
import nullvp.presentation.components.ConnectButton
import nullvp.presentation.components.ConnectState
import nullvp.presentation.components.ServerCard
import nullvp.presentation.components.SpeedRow
import nullvp.presentation.components.SubscriptionCard
import nullvp.presentation.dialogs.EditProfileDialog
import nullvp.presentation.settings.SettingsScreen
import nullvp.theme.*

enum class Screen(val title: String) {
    DASHBOARD("Home"),
    SERVERS("Servers"),
    SUBSCRIPTIONS("Subscriptions"),
    SETTINGS("Settings"),
    LOGS("Logs")
}

@OptIn(ExperimentalMaterial3Api::class)
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
    val logs by coreEngine.logs.collectAsState()

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
            val isWideScreen = maxWidth >= 640.dp

            Row(modifier = Modifier.fillMaxSize()) {
                // Wide Screen Navigation Rail (Desktop / Tablet)
                if (isWideScreen) {
                    NavigationRail(
                        containerColor = DarkSurface,
                        contentColor = OnDarkSurface,
                        modifier = Modifier.fillMaxHeight()
                    ) {
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = "NullVP",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            color = NeonCyan,
                            modifier = Modifier.padding(bottom = 24.dp)
                        )
                        NavigationRailItem(
                            selected = currentScreen == Screen.DASHBOARD,
                            onClick = { currentScreen = Screen.DASHBOARD },
                            icon = { Icon(Icons.Default.Home, contentDescription = "Home") },
                            label = { Text("Home") }
                        )
                        NavigationRailItem(
                            selected = currentScreen == Screen.SERVERS,
                            onClick = { currentScreen = Screen.SERVERS },
                            icon = { Icon(Icons.AutoMirrored.Filled.List, contentDescription = "Servers") },
                            label = { Text("Servers") }
                        )
                        NavigationRailItem(
                            selected = currentScreen == Screen.SUBSCRIPTIONS,
                            onClick = { currentScreen = Screen.SUBSCRIPTIONS },
                            icon = { Icon(Icons.Default.Refresh, contentDescription = "Subs") },
                            label = { Text("Subs") }
                        )
                        NavigationRailItem(
                            selected = currentScreen == Screen.SETTINGS,
                            onClick = { currentScreen = Screen.SETTINGS },
                            icon = { Icon(Icons.Default.Settings, contentDescription = "Settings") },
                            label = { Text("Settings") }
                        )
                        NavigationRailItem(
                            selected = currentScreen == Screen.LOGS,
                            onClick = { currentScreen = Screen.LOGS },
                            icon = { Icon(Icons.Default.Info, contentDescription = "Logs") },
                            label = { Text("Logs") }
                        )
                    }
                }

                // Main Content Screen
                Scaffold(
                    containerColor = DarkBackground,
                    bottomBar = {
                        // Compact Mobile Bottom Bar
                        if (!isWideScreen) {
                            NavigationBar(
                                containerColor = DarkSurface,
                                contentColor = OnDarkSurface
                            ) {
                                NavigationBarItem(
                                    selected = currentScreen == Screen.DASHBOARD,
                                    onClick = { currentScreen = Screen.DASHBOARD },
                                    icon = { Icon(Icons.Default.Home, contentDescription = "Home") },
                                    label = { Text("Home") }
                                )
                                NavigationBarItem(
                                    selected = currentScreen == Screen.SERVERS,
                                    onClick = { currentScreen = Screen.SERVERS },
                                    icon = { Icon(Icons.AutoMirrored.Filled.List, contentDescription = "Servers") },
                                    label = { Text("Servers") }
                                )
                                NavigationBarItem(
                                    selected = currentScreen == Screen.SUBSCRIPTIONS,
                                    onClick = { currentScreen = Screen.SUBSCRIPTIONS },
                                    icon = { Icon(Icons.Default.Refresh, contentDescription = "Subs") },
                                    label = { Text("Subs") }
                                )
                                NavigationBarItem(
                                    selected = currentScreen == Screen.SETTINGS,
                                    onClick = { currentScreen = Screen.SETTINGS },
                                    icon = { Icon(Icons.Default.Settings, contentDescription = "Settings") },
                                    label = { Text("Settings") }
                                )
                                NavigationBarItem(
                                    selected = currentScreen == Screen.LOGS,
                                    onClick = { currentScreen = Screen.LOGS },
                                    icon = { Icon(Icons.Default.Info, contentDescription = "Logs") },
                                    label = { Text("Logs") }
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
                                        .padding(24.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.SpaceBetween
                                ) {
                                    // Header
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Column {
                                            Text(
                                                text = "NullVP",
                                                style = MaterialTheme.typography.headlineMedium,
                                                fontWeight = FontWeight.Bold,
                                                color = NeonCyan
                                            )
                                            Text(
                                                text = settings.appVersion,
                                                style = MaterialTheme.typography.labelSmall,
                                                color = OnDarkSurfaceVariant
                                            )
                                        }
                                        Text(
                                            text = when (connectState) {
                                                ConnectState.CONNECTED -> "CONNECTED"
                                                ConnectState.CONNECTING -> "CONNECTING..."
                                                ConnectState.DISCONNECTED -> "READY"
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

                                    // Big Action Button
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

                                    // Speed Meters & Active Node Card
                                    Column(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalAlignment = Alignment.CenterHorizontally
                                    ) {
                                        SpeedRow(
                                            rxSpeedBps = stats.rxSpeedBps,
                                            txSpeedBps = stats.txSpeedBps
                                        )
                                        Spacer(modifier = Modifier.height(14.dp))
                                        if (activeProfile != null) {
                                            ServerCard(
                                                profile = activeProfile,
                                                isSelected = true,
                                                onSelect = { currentScreen = Screen.SERVERS }
                                            )
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
                                            text = "Servers (${profiles.size})",
                                            style = MaterialTheme.typography.titleLarge,
                                            fontWeight = FontWeight.Bold,
                                            color = OnDarkSurface
                                        )
                                        TextButton(onClick = { currentScreen = Screen.SUBSCRIPTIONS }) {
                                            Text("+ Add Node", color = NeonCyan)
                                        }
                                    }

                                    Spacer(modifier = Modifier.height(14.dp))

                                    LazyColumn(
                                        verticalArrangement = Arrangement.spacedBy(10.dp),
                                        modifier = Modifier.fillMaxSize()
                                    ) {
                                        items(profiles) { profile ->
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
                                                        contentDescription = "Edit Config",
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
                                        text = "Subscriptions & Import",
                                        style = MaterialTheme.typography.titleLarge,
                                        fontWeight = FontWeight.Bold,
                                        color = OnDarkSurface
                                    )
                                    Spacer(modifier = Modifier.height(6.dp))
                                    Text(
                                        text = "Import 3x-ui subscription URL, or paste vless://, mdns://, vkturn://, hy2:// links",
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
                                                // Fetch remote subscription
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
                                                // Single Link import
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
                                        colors = ButtonDefaults.buttonColors(containerColor = NeonCyan, contentColor = Color.Black)
                                    ) {
                                        Text(if (isSubLoading) "Importing..." else "Import to NullVP", fontWeight = FontWeight.Bold)
                                    }

                                    Spacer(modifier = Modifier.height(24.dp))

                                    Text(
                                        text = "Active Subscriptions (${subscriptions.size})",
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.Bold,
                                        color = OnDarkSurface
                                    )

                                    Spacer(modifier = Modifier.height(12.dp))

                                    LazyColumn(
                                        verticalArrangement = Arrangement.spacedBy(10.dp),
                                        modifier = Modifier.fillMaxSize()
                                    ) {
                                        items(subscriptions) { sub ->
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
                                SettingsScreen(
                                    settings = settings,
                                    onSettingsChanged = { AppStateStore.updateSettings(it) }
                                )
                            }

                            Screen.LOGS -> {
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
                                            text = "Core Engine Logs",
                                            style = MaterialTheme.typography.titleLarge,
                                            fontWeight = FontWeight.Bold,
                                            color = OnDarkSurface
                                        )
                                        Text(
                                            text = "${logs.size} entries",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = OnDarkSurfaceVariant
                                        )
                                    }
                                    Spacer(modifier = Modifier.height(14.dp))
                                    Box(
                                        modifier = Modifier
                                            .fillMaxSize()
                                            .clip(RoundedCornerShape(8.dp))
                                            .background(DarkSurfaceVariant)
                                            .padding(14.dp)
                                    ) {
                                        LazyColumn(modifier = Modifier.fillMaxSize()) {
                                            items(logs) { logLine ->
                                                Text(
                                                    text = logLine,
                                                    style = MaterialTheme.typography.bodySmall,
                                                    color = if (logLine.contains("ERROR", true) || logLine.contains("ERR", true)) CyberRed else CyberGreen
                                                )
                                                Spacer(modifier = Modifier.height(2.dp))
                                            }
                                        }
                                    }
                                }
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
