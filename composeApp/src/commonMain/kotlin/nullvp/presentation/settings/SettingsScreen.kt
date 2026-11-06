package nullvp.presentation.settings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.style.TextOverflow
import nullvp.data.store.AppStateStore
import nullvp.domain.model.AppSettings
import nullvp.domain.model.CustomRoutingRule
import nullvp.domain.model.OperationMode

import nullvp.i18n.AppLanguage
import nullvp.i18n.AppStrings
import nullvp.i18n.Localization
import nullvp.platform.AppListHelper
import nullvp.presentation.dialogs.AppSelectorDialog
import nullvp.presentation.dialogs.EditRuleDialog

@Composable
fun SettingsScreen() {
    val settings by AppStateStore.settings.collectAsState()
    val strings = Localization.get(settings.language)

    var activeTab by remember { mutableStateOf(0) }
    var editingRule by remember { mutableStateOf<CustomRoutingRule?>(null) }
    var isCreatingRule by remember { mutableStateOf(false) }
    var showAppSelector by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        // Header
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = strings.navSettings,
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground
            )

            // Language quick switch badge
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                modifier = Modifier.clickable {
                    val nextLang = if (settings.language == AppLanguage.RU) AppLanguage.EN else AppLanguage.RU
                    AppStateStore.setLanguage(nextLang)
                }
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = if (settings.language == AppLanguage.RU) "🇷🇺 RU" else "🇬🇧 EN",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }

        Spacer(Modifier.height(16.dp))

        // Categories Tab Row
        val categories = listOf(
            strings.catNetwork,
            strings.catRouting,
            strings.catDns,
            strings.catBypassDpi,
            strings.catUi
        )
        ScrollableTabRow(
            selectedTabIndex = activeTab,
            edgePadding = 0.dp,
            containerColor = Color.Transparent,
            contentColor = MaterialTheme.colorScheme.primary,
            divider = {}
        ) {
            categories.forEachIndexed { index, title ->
                Tab(
                    selected = activeTab == index,
                    onClick = { activeTab = index },
                    text = {
                        Text(
                            text = title,
                            fontWeight = if (activeTab == index) FontWeight.Bold else FontWeight.Normal,
                            fontSize = 13.sp
                        )
                    }
                )
            }
        }

        Spacer(Modifier.height(16.dp))

        // Tab Content
        Box(modifier = Modifier.weight(1f)) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                when (activeTab) {
                    0 -> NetworkCategorySection(
                        settings = settings,
                        strings = strings,
                        onOpenAppSelector = { showAppSelector = true }
                    )
                    1 -> RoutingCategorySection(
                        settings = settings,
                        strings = strings,
                        onAddRule = { isCreatingRule = true },
                        onEditRule = { editingRule = it }
                    )
                    2 -> DnsCategorySection(settings, strings)
                    3 -> BypassDpiCategorySection(settings, strings)
                    4 -> UiCategorySection(settings, strings)
                }
            }
        }
    }

    // App Selector Dialog for Split Tunneling
    if (showAppSelector) {
        AppSelectorDialog(
            initialSelected = settings.perAppSelectedPackages,
            onDismiss = { showAppSelector = false },
            onSave = { selected ->
                showAppSelector = false
                AppStateStore.updateSettings(settings.copy(perAppSelectedPackages = selected))
            }
        )
    }

    // Rule Edit/Create Dialog
    if (isCreatingRule || editingRule != null) {
        EditRuleDialog(
            rule = editingRule,
            strings = strings,
            onDismiss = {
                isCreatingRule = false
                editingRule = null
            },
            onSave = { rule ->
                if (editingRule != null) {
                    AppStateStore.updateCustomRule(rule)
                } else {
                    AppStateStore.addCustomRule(rule)
                }
            },
            onDelete = { id ->
                AppStateStore.deleteCustomRule(id)
            }
        )
    }
}

// ======================= 1. NETWORK & MODES CATEGORY =======================
@Composable
private fun NetworkCategorySection(
    settings: AppSettings,
    strings: AppStrings,
    onOpenAppSelector: () -> Unit
) {
    val isAndroid = AppListHelper.isAndroid()

    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.2f))
    ) {
        Column(modifier = Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text(
                text = strings.modeTitle,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )

            // Operation Mode Selector: 2 clean modes on Android, 3 on Desktop
            val modes = if (isAndroid) {
                listOf(
                    Triple(
                        OperationMode.TUN,
                        if (settings.language == AppLanguage.RU) "VPN (Режим VPN-туннеля)" else "VPN Tunnel Mode",
                        if (settings.language == AppLanguage.RU) "Туннелирование всего интернет-трафика системы через Android VPN" else "Route entire device traffic through Android VpnService"
                    ),
                    Triple(
                        OperationMode.PROXY_ONLY,
                        if (settings.language == AppLanguage.RU) "Прокси (Только локальный прокси)" else "Proxy Only (Local Port)",
                        if (settings.language == AppLanguage.RU) "Поднятие локального SOCKS5/HTTP порта без создания VPN (для ручной настройки в Telegram/Wi-Fi)" else "Runs local SOCKS5/HTTP inbound for manual app setup"
                    )
                )
            } else {
                listOf(
                    Triple(OperationMode.TUN, strings.modeTun, strings.modeTunDesc),
                    Triple(OperationMode.SYSTEM_PROXY, strings.modeSystemProxy, strings.modeSystemProxyDesc),
                    Triple(OperationMode.PROXY_ONLY, strings.modeProxyOnly, strings.modeProxyOnlyDesc)
                )
            }

            modes.forEach { (mode, title, desc) ->
                val selected = settings.operationMode == mode
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.12f) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
                    border = androidx.compose.foundation.BorderStroke(
                        width = if (selected) 1.5.dp else 1.dp,
                        color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline.copy(alpha = 0.2f)
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { AppStateStore.setOperationMode(mode) }
                ) {
                    Row(
                        modifier = Modifier.padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = selected,
                            onClick = { AppStateStore.setOperationMode(mode) },
                            colors = RadioButtonDefaults.colors(selectedColor = MaterialTheme.colorScheme.primary)
                        )
                        Spacer(Modifier.width(8.dp))
                        Column {
                            Text(text = title, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                            Text(text = desc, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }

            // Android Per-App Split Tunneling (Visible in VPN Mode on Android)
            if (isAndroid && settings.operationMode == OperationMode.TUN) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f))

                Text(
                    text = if (settings.language == AppLanguage.RU) "Раздельное туннелирование (Split Tunneling)" else "Per-App Split Tunneling",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )

                SettingsToggleRow(
                    title = if (settings.language == AppLanguage.RU) "Включить раздельное туннелирование" else "Enable Split Tunneling",
                    subtitle = if (settings.language == AppLanguage.RU) "Индивидуальный выбор приложений, работающих через VPN или в обход него" else "Select which apps route through VPN or bypass it",
                    checked = settings.perAppProxyEnabled,
                    onCheckedChange = { AppStateStore.updateSettings(settings.copy(perAppProxyEnabled = it)) }
                )

                AnimatedVisibility(visible = settings.perAppProxyEnabled) {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(top = 4.dp)) {
                        val bypassOptions = listOf(
                            true to if (settings.language == AppLanguage.RU) "В обход выбранных (Исключения)" else "Bypass selected (Blacklist)",
                            false to if (settings.language == AppLanguage.RU) "Только выбранные (Белый список)" else "Only selected (Whitelist)"
                        )

                        bypassOptions.forEach { (isBypass, optTitle) ->
                            val isSelected = settings.perAppBypassMode == isBypass
                            Surface(
                                shape = RoundedCornerShape(10.dp),
                                color = if (isSelected) MaterialTheme.colorScheme.primary.copy(alpha = 0.12f) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.2f),
                                border = androidx.compose.foundation.BorderStroke(
                                    width = if (isSelected) 1.5.dp else 1.dp,
                                    color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline.copy(alpha = 0.15f)
                                ),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { AppStateStore.updateSettings(settings.copy(perAppBypassMode = isBypass)) }
                            ) {
                                Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                    RadioButton(
                                        selected = isSelected,
                                        onClick = { AppStateStore.updateSettings(settings.copy(perAppBypassMode = isBypass)) },
                                        colors = RadioButtonDefaults.colors(selectedColor = MaterialTheme.colorScheme.primary)
                                    )
                                    Spacer(Modifier.width(8.dp))
                                    Column {
                                        Text(optTitle, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                                        Text(
                                            text = if (isBypass) {
                                                if (settings.language == AppLanguage.RU) "Выбранные приложения не попадают в VPN туннель и работают напрямую" else "Selected apps bypass VPN directly"
                                            } else {
                                                if (settings.language == AppLanguage.RU) "Только выбранные приложения идут через VPN туннель, все остальные напрямую" else "Only selected apps route through VPN"
                                            },
                                            fontSize = 11.sp,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            }
                        }

                        // App selector button
                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
                            border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.2f)),
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onOpenAppSelector() }
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column {
                                    Text(
                                        text = if (settings.language == AppLanguage.RU) "Выбрать приложения" else "Select Applications",
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 13.sp,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                    Text(
                                        text = if (settings.language == AppLanguage.RU) {
                                            "Выбрано: ${settings.perAppSelectedPackages.size} прилож."
                                        } else {
                                            "Selected: ${settings.perAppSelectedPackages.size} apps"
                                        },
                                        fontSize = 11.sp,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                }
                                Icon(Icons.Default.Edit, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                            }
                        }

                        // Dynamic RU scanner toggle
                        SettingsToggleRow(
                            title = strings.dynamicRuAppScanner,
                            subtitle = if (settings.language == AppLanguage.RU) {
                                "Автоматически направлять сервисы РФ напрямую (ручной выбор приложений имеет высший приоритет над динамическим)"
                            } else {
                                "Directly route RU services (manual app list takes priority)"
                            },
                            checked = settings.dynamicRuAppScannerEnabled,
                            onCheckedChange = { AppStateStore.updateSettings(settings.copy(dynamicRuAppScannerEnabled = it)) }
                        )
                    }
                }
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f))

            // Port Settings
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = settings.socksPort.toString(),
                    onValueChange = {
                        it.toIntOrNull()?.let { p -> AppStateStore.updateSettings(settings.copy(socksPort = p)) }
                    },
                    label = { Text(strings.socksPort) },
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(10.dp),
                    singleLine = true
                )
                OutlinedTextField(
                    value = settings.httpPort.toString(),
                    onValueChange = {
                        it.toIntOrNull()?.let { p -> AppStateStore.updateSettings(settings.copy(httpPort = p)) }
                    },
                    label = { Text(strings.httpPort) },
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(10.dp),
                    singleLine = true
                )
            }

            // LAN Sharing
            SettingsToggleRow(
                title = strings.allowLan,
                subtitle = strings.allowLanDesc,
                checked = settings.allowLan,
                onCheckedChange = { AppStateStore.updateSettings(settings.copy(allowLan = it)) }
            )

            // IPv6
            SettingsToggleRow(
                title = strings.enableIpv6,
                subtitle = strings.enableIpv6Desc,
                checked = settings.enableIpv6,
                onCheckedChange = { AppStateStore.updateSettings(settings.copy(enableIpv6 = it)) }
            )

            // MTU
            OutlinedTextField(
                value = settings.tunMtu.toString(),
                onValueChange = {
                    it.toIntOrNull()?.let { mtu -> AppStateStore.updateSettings(settings.copy(tunMtu = mtu)) }
                },
                label = { Text(strings.tunMtu) },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(10.dp),
                singleLine = true
            )

            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f))

            // Proxy Authentication
            SettingsToggleRow(
                title = strings.proxyAuth,
                subtitle = strings.proxyAuthDesc,
                checked = settings.proxyAuthEnabled,
                onCheckedChange = { AppStateStore.updateSettings(settings.copy(proxyAuthEnabled = it)) }
            )

            AnimatedVisibility(visible = settings.proxyAuthEnabled) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        value = settings.proxyUsername,
                        onValueChange = { AppStateStore.updateSettings(settings.copy(proxyUsername = it)) },
                        label = { Text(strings.proxyUsername) },
                        placeholder = { Text("admin") },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(10.dp),
                        singleLine = true
                    )

                    OutlinedTextField(
                        value = settings.proxyPassword,
                        onValueChange = { AppStateStore.updateSettings(settings.copy(proxyPassword = it)) },
                        label = { Text(strings.proxyPassword) },
                        placeholder = { Text("••••••••") },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(10.dp),
                        singleLine = true
                    )
                }
            }
        }
    }
}

// ======================= 2. ROUTING & RULES CATEGORY =======================
@Composable
private fun RoutingCategorySection(
    settings: AppSettings,
    strings: AppStrings,
    onAddRule: () -> Unit,
    onEditRule: (CustomRoutingRule) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        // 1. Built-in Base Routing Rules
        Card(
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.2f))
        ) {
            Column(modifier = Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Text(
                    text = if (settings.language == AppLanguage.RU) "Базовые правила маршрутизации" else "Base Routing Rules",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )

                // Quick Switches (Adblock, LAN, RU Bypass)
                SettingsToggleRow(
                    title = strings.blockAds,
                    subtitle = strings.blockAdsDesc,
                    checked = settings.blockAds,
                    onCheckedChange = { AppStateStore.updateSettings(settings.copy(blockAds = it)) }
                )

                SettingsToggleRow(
                    title = strings.bypassLan,
                    subtitle = strings.bypassLanDesc,
                    checked = settings.bypassLan,
                    onCheckedChange = { AppStateStore.updateSettings(settings.copy(bypassLan = it)) }
                )

                SettingsToggleRow(
                    title = strings.bypassRu,
                    subtitle = strings.bypassRuDesc,
                    checked = settings.bypassRu,
                    onCheckedChange = { AppStateStore.updateSettings(settings.copy(bypassRu = it)) }
                )
            }
        }

        // 2. Custom User Rules
        Card(
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.2f))
        ) {
            Column(modifier = Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = strings.rulesTitle,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = if (settings.language == AppLanguage.RU) "Индивидуальные правила доменов и IP адресов" else "Custom domain and IP rules",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Button(
                        onClick = onAddRule,
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(strings.addRule, fontSize = 12.sp)
                    }
                }

                if (settings.customRules.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 12.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = if (settings.language == AppLanguage.RU) {
                                "Нет пользовательских правил.\nВесь трафик маршрутизируется согласно базовым правилам выше."
                            } else {
                                "No custom rules configured.\nAll traffic follows base routing rules."
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center
                        )
                    }
                } else {
                    settings.customRules.forEach { rule ->
                        val outboundColor = when (rule.outbound) {
                            "direct" -> Color(0xFF00E676)
                            "proxy" -> Color(0xFF00E5FF)
                            else -> Color(0xFFFF5252)
                        }

                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
                            border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.2f)),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(
                                    modifier = Modifier
                                        .weight(1f)
                                        .clickable { onEditRule(rule) }
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Text(
                                            text = rule.name,
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 14.sp,
                                            color = MaterialTheme.colorScheme.onSurface,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            modifier = Modifier.weight(1f, fill = false)
                                        )
                                        Spacer(Modifier.width(8.dp))
                                        Surface(
                                            shape = RoundedCornerShape(4.dp),
                                            color = outboundColor.copy(alpha = 0.15f)
                                        ) {
                                            Text(
                                                text = rule.outbound.uppercase(),
                                                color = outboundColor,
                                                fontSize = 10.sp,
                                                fontWeight = FontWeight.Bold,
                                                maxLines = 1,
                                                softWrap = false,
                                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                            )
                                        }
                                    }

                                    Spacer(Modifier.height(4.dp))

                                    if (rule.domains.isNotEmpty()) {
                                        Text(
                                            text = "Domains: ${rule.domains.joinToString(", ")}",
                                            fontSize = 11.sp,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                    if (rule.ips.isNotEmpty()) {
                                        Text(
                                            text = "IPs: ${rule.ips.joinToString(", ")}",
                                            fontSize = 11.sp,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                }

                                Spacer(Modifier.width(8.dp))

                                IconButton(
                                    onClick = { onEditRule(rule) },
                                    modifier = Modifier.size(32.dp)
                                ) {
                                    Icon(Icons.Default.Edit, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(16.dp))
                                }

                                Switch(
                                    checked = rule.enabled,
                                    onCheckedChange = { AppStateStore.updateCustomRule(rule.copy(enabled = it)) },
                                    modifier = Modifier.padding(start = 4.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

// ======================= 3. DNS & SNIFFING CATEGORY =======================
@Composable
private fun DnsCategorySection(settings: AppSettings, strings: AppStrings) {
    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.2f))
    ) {
        Column(modifier = Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text(
                text = strings.catDns,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )

            // Remote DNS
            OutlinedTextField(
                value = settings.remoteDns,
                onValueChange = { AppStateStore.updateSettings(settings.copy(remoteDns = it)) },
                label = { Text(strings.remoteDns) },
                placeholder = { Text("https://1.1.1.1/dns-query") },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(10.dp),
                singleLine = true
            )

            // Direct DNS
            OutlinedTextField(
                value = settings.directDns,
                onValueChange = { AppStateStore.updateSettings(settings.copy(directDns = it)) },
                label = { Text(strings.directDns) },
                placeholder = { Text("77.88.8.8") },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(10.dp),
                singleLine = true
            )

            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f))

            // FakeDNS
            SettingsToggleRow(
                title = strings.fakeDns,
                subtitle = strings.fakeDnsDesc,
                checked = settings.fakeDns,
                onCheckedChange = { AppStateStore.updateSettings(settings.copy(fakeDns = it)) }
            )

            // Sniffing
            SettingsToggleRow(
                title = strings.sniffing,
                subtitle = strings.sniffingDesc,
                checked = settings.sniffingEnabled,
                onCheckedChange = { AppStateStore.updateSettings(settings.copy(sniffingEnabled = it)) }
            )
        }
    }
}

// ======================= 4. BYPASS DPI & MUX CATEGORY =======================
@Composable
private fun BypassDpiCategorySection(settings: AppSettings, strings: AppStrings) {
    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.2f))
    ) {
        Column(modifier = Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text(
                text = strings.catBypassDpi,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )

            // TLS Fragment
            SettingsToggleRow(
                title = strings.tlsFragment,
                subtitle = strings.tlsFragmentDesc,
                checked = settings.fragmentEnabled,
                onCheckedChange = { AppStateStore.updateSettings(settings.copy(fragmentEnabled = it)) }
            )

            if (settings.fragmentEnabled) {
                OutlinedTextField(
                    value = settings.fragmentPackets,
                    onValueChange = { AppStateStore.updateSettings(settings.copy(fragmentPackets = it)) },
                    label = { Text(strings.fragmentPackets) },
                    placeholder = { Text("tlshello, 1-3") },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(10.dp),
                    singleLine = true
                )

                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(
                        value = settings.fragmentLength,
                        onValueChange = { AppStateStore.updateSettings(settings.copy(fragmentLength = it)) },
                        label = { Text(strings.fragmentLength) },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(10.dp),
                        singleLine = true
                    )
                    OutlinedTextField(
                        value = settings.fragmentInterval,
                        onValueChange = { AppStateStore.updateSettings(settings.copy(fragmentInterval = it)) },
                        label = { Text(strings.fragmentInterval) },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(10.dp),
                        singleLine = true
                    )
                }
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f))

            // Mux
            SettingsToggleRow(
                title = strings.mux,
                subtitle = strings.muxDesc,
                checked = settings.muxEnabled,
                onCheckedChange = { AppStateStore.updateSettings(settings.copy(muxEnabled = it)) }
            )

            if (settings.muxEnabled) {
                OutlinedTextField(
                    value = settings.muxConcurrency.toString(),
                    onValueChange = {
                        it.toIntOrNull()?.let { c -> AppStateStore.updateSettings(settings.copy(muxConcurrency = c)) }
                    },
                    label = { Text(strings.muxConcurrency) },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(10.dp),
                    singleLine = true
                )
            }
        }
    }
}

// ======================= 5. UI & SYSTEM CATEGORY =======================
@Composable
private fun UiCategorySection(settings: AppSettings, strings: AppStrings) {
    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.2f))
    ) {
        Column(modifier = Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text(
                text = strings.catUi,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )

            // Language Selector
            Text(
                text = strings.language,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                AppLanguage.values().forEach { lang ->
                    val selected = settings.language == lang
                    FilterChip(
                        selected = selected,
                        onClick = { AppStateStore.setLanguage(lang) },
                        label = { Text(lang.displayName, fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.2f),
                            selectedLabelColor = MaterialTheme.colorScheme.primary
                        )
                    )
                }
            }

            // Minimize to Tray (Desktop only)
            if (!AppListHelper.isAndroid()) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f))

                SettingsToggleRow(
                    title = strings.minimizeToTray,
                    subtitle = strings.minimizeToTrayDesc,
                    checked = settings.minimizeToTrayOnClose,
                    onCheckedChange = { AppStateStore.updateSettings(settings.copy(minimizeToTrayOnClose = it)) }
                )
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f))

            // App Version Card
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = strings.appVersion,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = settings.appVersion,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }
    }
}

@Composable
private fun SettingsToggleRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onCheckedChange(!checked) },
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange
        )
    }
}
