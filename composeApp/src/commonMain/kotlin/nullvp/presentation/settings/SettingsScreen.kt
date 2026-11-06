package nullvp.presentation.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import nullvp.domain.model.AppSettings
import nullvp.domain.model.RoutingStrategy
import nullvp.theme.*

@Composable
fun SettingsScreen(
    settings: AppSettings,
    onSettingsChanged: (AppSettings) -> Unit,
    modifier: Modifier = Modifier
) {
    var remoteDns by remember { mutableStateOf(settings.remoteDns) }
    var directDns by remember { mutableStateOf(settings.directDns) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(20.dp)
            .verticalScroll(rememberScrollState())
    ) {
        Text(
            text = "Settings & Routing",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            color = OnDarkSurface
        )
        Text(
            text = "Configure routing rules, DNS servers, and network mode",
            style = MaterialTheme.typography.bodySmall,
            color = OnDarkSurfaceVariant
        )

        Spacer(modifier = Modifier.height(20.dp))

        // --- Section 1: Routing Presets ---
        Text(
            text = "ROUTING STRATEGY",
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            color = NeonCyan
        )
        Spacer(modifier = Modifier.height(8.dp))

        RoutingOptionCard(
            title = "Bypass LAN and Russia (Рекомендуется)",
            description = "Direct routing for Russian domains (.ru, .рф, .su) and IPs. Only blocked/foreign traffic goes through VPN.",
            isSelected = settings.routingStrategy == RoutingStrategy.BYPASS_LAN_AND_RU,
            onSelect = { onSettingsChanged(settings.copy(routingStrategy = RoutingStrategy.BYPASS_LAN_AND_RU, bypassRu = true, bypassLan = true)) }
        )

        Spacer(modifier = Modifier.height(8.dp))

        RoutingOptionCard(
            title = "Global Proxy (Все через VPN)",
            description = "All network traffic is routed through the proxy tunnel without exceptions.",
            isSelected = settings.routingStrategy == RoutingStrategy.GLOBAL,
            onSelect = { onSettingsChanged(settings.copy(routingStrategy = RoutingStrategy.GLOBAL, bypassRu = false, bypassLan = false)) }
        )

        Spacer(modifier = Modifier.height(8.dp))

        RoutingOptionCard(
            title = "Smart Whitelist (Умный белый список)",
            description = "Only blocked resources and selected services go through VPN, standard traffic goes directly.",
            isSelected = settings.routingStrategy == RoutingStrategy.CUSTOM_WHITELIST,
            onSelect = { onSettingsChanged(settings.copy(routingStrategy = RoutingStrategy.CUSTOM_WHITELIST)) }
        )

        Spacer(modifier = Modifier.height(20.dp))

        // --- Section 2: Detailed Switches ---
        Text(
            text = "FINE-GRAINED RULES",
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            color = NeonCyan
        )
        Spacer(modifier = Modifier.height(8.dp))

        SettingSwitchRow(
            title = "Обход сайтов РФ напрямую (Bypass Russia)",
            subtitle = "Прямой доступ к .ru, .su, .рф, Yandex, VK и Госуслугам мимо VPN",
            checked = settings.bypassRu,
            onCheckedChange = { onSettingsChanged(settings.copy(bypassRu = it)) }
        )

        SettingSwitchRow(
            title = "Обход локальной сети (Bypass LAN)",
            subtitle = "Прямой доступ к домашним роутерам и локальным ресурсам (192.168.x, 10.x)",
            checked = settings.bypassLan,
            onCheckedChange = { onSettingsChanged(settings.copy(bypassLan = it)) }
        )

        SettingSwitchRow(
            title = "Блокировка рекламы (Block Ads & Tracking)",
            subtitle = "Блокировать рекламные домены и аналитические трекеры на уровне ядра",
            checked = settings.blockAds,
            onCheckedChange = { onSettingsChanged(settings.copy(blockAds = it)) }
        )

        Spacer(modifier = Modifier.height(20.dp))

        // --- Section 3: DNS Settings ---
        Text(
            text = "DNS SERVERS",
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            color = NeonCyan
        )
        Spacer(modifier = Modifier.height(8.dp))

        OutlinedTextField(
            value = remoteDns,
            onValueChange = {
                remoteDns = it
                onSettingsChanged(settings.copy(remoteDns = it))
            },
            label = { Text("Удаленный DNS (Remote DNS / DoH)") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = NeonCyan,
                unfocusedBorderColor = DarkSurfaceVariant,
                focusedTextColor = OnDarkSurface,
                unfocusedTextColor = OnDarkSurface
            )
        )

        Spacer(modifier = Modifier.height(10.dp))

        OutlinedTextField(
            value = directDns,
            onValueChange = {
                directDns = it
                onSettingsChanged(settings.copy(directDns = it))
            },
            label = { Text("Прямой DNS для локального трафика (Direct DNS)") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = NeonCyan,
                unfocusedBorderColor = DarkSurfaceVariant,
                focusedTextColor = OnDarkSurface,
                unfocusedTextColor = OnDarkSurface
            )
        )

        Spacer(modifier = Modifier.height(28.dp))

        // --- Section 4: App Info & Version ---
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .border(1.dp, DarkSurfaceVariant, RoundedCornerShape(12.dp)),
            color = DarkSurface
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = "NullVP Client",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = OnDarkSurface
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Version: ${settings.appVersion} (Stable)",
                    style = MaterialTheme.typography.bodySmall,
                    color = CyberGreen
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = "Multiplatform Architecture: Android APK & Linux AppImage / Windows PE",
                    style = MaterialTheme.typography.labelSmall,
                    color = OnDarkSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun RoutingOptionCard(
    title: String,
    description: String,
    isSelected: Boolean,
    onSelect: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .border(1.5.dp, if (isSelected) NeonCyan else DarkSurfaceVariant, RoundedCornerShape(12.dp))
            .clickable(onClick = onSelect),
        colors = CardDefaults.cardColors(
            containerColor = if (isSelected) DarkSurfaceVariant else DarkSurface
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            RadioButton(
                selected = isSelected,
                onClick = onSelect,
                colors = RadioButtonDefaults.colors(selectedColor = NeonCyan)
            )
            Spacer(modifier = Modifier.width(10.dp))
            Column {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = OnDarkSurface
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall,
                    color = OnDarkSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun SettingSwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                color = OnDarkSurface
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = OnDarkSurfaceVariant
            )
        }
        Spacer(modifier = Modifier.width(12.dp))
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = NeonCyan,
                checkedTrackColor = DarkSurfaceVariant
            )
        )
    }
}

