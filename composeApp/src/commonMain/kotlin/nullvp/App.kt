package nullvp

import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.draw.clip
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import nullvp.data.protocol.MdnsProtocolHandler
import nullvp.data.protocol.VkTurnProtocolHandler
import nullvp.data.protocol.VlessProtocolHandler
import nullvp.domain.model.Profile
import nullvp.domain.model.ProtocolType
import nullvp.domain.protocol.ProtocolRegistry
import nullvp.presentation.components.ConnectButton
import nullvp.presentation.components.ConnectState
import nullvp.presentation.components.ServerCard
import nullvp.presentation.components.SpeedRow
import nullvp.theme.*

enum class Screen(val title: String) {
    DASHBOARD("Dashboard"),
    SERVERS("Servers"),
    SUBSCRIPTIONS("Subscriptions"),
    LOGS("Logs")
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun App() {
    // Initialize default protocol handlers
    LaunchedEffect(Unit) {
        ProtocolRegistry.register(MdnsProtocolHandler())
        ProtocolRegistry.register(VkTurnProtocolHandler())
        ProtocolRegistry.register(VlessProtocolHandler())
    }

    // Demo state for the UI
    var currentScreen by remember { mutableStateOf(Screen.DASHBOARD) }
    var connectState by remember { mutableStateOf(ConnectState.DISCONNECTED) }
    var selectedProfileId by remember { mutableStateOf("demo_mdns") }

    val sampleProfiles = remember {
        mutableStateListOf(
            Profile(
                id = "demo_mdns",
                name = "NullDNS Turbo (Bypass Tier)",
                serverAddress = "ns1.tunnel.cloud",
                serverPort = 18000,
                protocolType = ProtocolType.MASTER_DNS,
                pingMs = 48
            ),
            Profile(
                id = "demo_vkturn",
                name = "VK TURN Relay (Opus Audio)",
                serverAddress = "turn.vk.me",
                serverPort = 443,
                protocolType = ProtocolType.VK_TURN,
                pingMs = 32
            ),
            Profile(
                id = "demo_vless",
                name = "Frankfurt Reality (High Speed)",
                serverAddress = "de1.node.vpn",
                serverPort = 443,
                protocolType = ProtocolType.VLESS,
                pingMs = 84
            )
        )
    }

    var importUrlInput by remember { mutableStateOf("") }
    var importError by remember { mutableStateOf<String?>(null) }

    NullVpTheme {
        BoxWithConstraints(modifier = Modifier.fillMaxSize().background(DarkBackground)) {
            val isWideScreen = maxWidth >= 600.dp

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
                            icon = { Icon(Icons.Default.Home, contentDescription = "Dashboard") },
                            label = { Text("Home") }
                        )
                        NavigationRailItem(
                            selected = currentScreen == Screen.SERVERS,
                            onClick = { currentScreen = Screen.SERVERS },
                            icon = { Icon(Icons.Default.List, contentDescription = "Servers") },
                            label = { Text("Servers") }
                        )
                        NavigationRailItem(
                            selected = currentScreen == Screen.SUBSCRIPTIONS,
                            onClick = { currentScreen = Screen.SUBSCRIPTIONS },
                            icon = { Icon(Icons.Default.Add, contentDescription = "Subs") },
                            label = { Text("Subs") }
                        )
                        NavigationRailItem(
                            selected = currentScreen == Screen.LOGS,
                            onClick = { currentScreen = Screen.LOGS },
                            icon = { Icon(Icons.Default.Info, contentDescription = "Logs") },
                            label = { Text("Logs") }
                        )
                    }
                }

                // Main Content Area
                Scaffold(
                    containerColor = DarkBackground,
                    bottomBar = {
                        // Compact Screen Bottom Bar (Mobile Phone)
                        if (!isWideScreen) {
                            NavigationBar(
                                containerColor = DarkSurface,
                                contentColor = OnDarkSurface
                            ) {
                                NavigationBarItem(
                                    selected = currentScreen == Screen.DASHBOARD,
                                    onClick = { currentScreen = Screen.DASHBOARD },
                                    icon = { Icon(Icons.Default.Home, contentDescription = "Dashboard") },
                                    label = { Text("Home") }
                                )
                                NavigationBarItem(
                                    selected = currentScreen == Screen.SERVERS,
                                    onClick = { currentScreen = Screen.SERVERS },
                                    icon = { Icon(Icons.Default.List, contentDescription = "Servers") },
                                    label = { Text("Servers") }
                                )
                                NavigationBarItem(
                                    selected = currentScreen == Screen.SUBSCRIPTIONS,
                                    onClick = { currentScreen = Screen.SUBSCRIPTIONS },
                                    icon = { Icon(Icons.Default.Add, contentDescription = "Subs") },
                                    label = { Text("Subs") }
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
                                val activeProfile = sampleProfiles.find { it.id == selectedProfileId }
                                    ?: sampleProfiles.firstOrNull()

                                Column(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .padding(24.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.SpaceBetween
                                ) {
                                    // Top Header
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            text = "NullVP",
                                            style = MaterialTheme.typography.headlineMedium,
                                            fontWeight = FontWeight.Bold,
                                            color = NeonCyan
                                        )
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

                                    // Center Action Button
                                    ConnectButton(
                                        state = connectState,
                                        onClick = {
                                            connectState = when (connectState) {
                                                ConnectState.DISCONNECTED -> ConnectState.CONNECTED
                                                ConnectState.CONNECTING -> ConnectState.DISCONNECTED
                                                ConnectState.CONNECTED -> ConnectState.DISCONNECTED
                                            }
                                        }
                                    )

                                    // Bottom Status & Selected Server
                                    Column(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalAlignment = Alignment.CenterHorizontally
                                    ) {
                                        SpeedRow(
                                            rxSpeedBps = if (connectState == ConnectState.CONNECTED) 14_500_000 else 0,
                                            txSpeedBps = if (connectState == ConnectState.CONNECTED) 3_200_000 else 0
                                        )
                                        Spacer(modifier = Modifier.height(16.dp))
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
                                    Text(
                                        text = "Available Profiles (${sampleProfiles.size})",
                                        style = MaterialTheme.typography.titleLarge,
                                        fontWeight = FontWeight.Bold,
                                        color = OnDarkSurface
                                    )
                                    Spacer(modifier = Modifier.height(16.dp))

                                    LazyColumn(
                                        verticalArrangement = Arrangement.spacedBy(10.dp),
                                        modifier = Modifier.fillMaxSize()
                                    ) {
                                        items(sampleProfiles) { profile ->
                                            ServerCard(
                                                profile = profile,
                                                isSelected = profile.id == selectedProfileId,
                                                onSelect = {
                                                    selectedProfileId = profile.id
                                                    currentScreen = Screen.DASHBOARD
                                                }
                                            )
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
                                        text = "Import Configurations",
                                        style = MaterialTheme.typography.titleLarge,
                                        fontWeight = FontWeight.Bold,
                                        color = OnDarkSurface
                                    )
                                    Spacer(modifier = Modifier.height(8.dp))
                                    Text(
                                        text = "Paste any link (vless://, mdns://, vkturn://, freeturn://) or subscription URL:",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = OnDarkSurfaceVariant
                                    )
                                    Spacer(modifier = Modifier.height(16.dp))

                                    OutlinedTextField(
                                        value = importUrlInput,
                                        onValueChange = {
                                            importUrlInput = it
                                            importError = null
                                        },
                                        placeholder = { Text("Link or Subscription URL...") },
                                        singleLine = true,
                                        modifier = Modifier.fillMaxWidth(),
                                        colors = OutlinedTextFieldDefaults.colors(
                                            focusedBorderColor = NeonCyan,
                                            unfocusedBorderColor = DarkSurfaceVariant,
                                            focusedTextColor = OnDarkSurface,
                                            unfocusedTextColor = OnDarkSurface
                                        )
                                    )

                                    if (importError != null) {
                                        Spacer(modifier = Modifier.height(8.dp))
                                        Text(text = importError!!, color = CyberRed, style = MaterialTheme.typography.bodySmall)
                                    }

                                    Spacer(modifier = Modifier.height(16.dp))

                                    Button(
                                        onClick = {
                                            val parsed = ProtocolRegistry.parse(importUrlInput)
                                            if (parsed != null) {
                                                sampleProfiles.add(0, parsed)
                                                selectedProfileId = parsed.id
                                                importUrlInput = ""
                                                currentScreen = Screen.SERVERS
                                            } else {
                                                importError = "Unsupported or invalid link format"
                                            }
                                        },
                                        modifier = Modifier.fillMaxWidth(),
                                        colors = ButtonDefaults.buttonColors(containerColor = NeonCyan, contentColor = Color.Black)
                                    ) {
                                        Text("Import Profile", fontWeight = FontWeight.Bold)
                                    }
                                }
                            }

                            Screen.LOGS -> {
                                Column(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .padding(20.dp)
                                ) {
                                    Text(
                                        text = "Core Engine Logs",
                                        style = MaterialTheme.typography.titleLarge,
                                        fontWeight = FontWeight.Bold,
                                        color = OnDarkSurface
                                    )
                                    Spacer(modifier = Modifier.height(16.dp))
                                    Box(
                                        modifier = Modifier
                                            .fillMaxSize()
                                            .clip(RoundedCornerShape(8.dp))
                                            .background(DarkSurfaceVariant)
                                            .padding(16.dp)
                                    ) {
                                        Text(
                                            text = "[INFO] NullVP Core Initialized\n" +
                                                   "[INFO] Pluggable Protocol Registry: [Mdns, VkTurn, Vless] loaded\n" +
                                                   "[INFO] Platform: Compose Multiplatform (JVM/Android)\n" +
                                                   "[INFO] Driver Registry: MasterDnsVPN + VK TURN active\n" +
                                                   "[READY] Awaiting connection...",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = CyberGreen
                                        )
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
