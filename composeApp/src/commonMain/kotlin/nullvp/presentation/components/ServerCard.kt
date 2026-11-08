package nullvp.presentation.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import nullvp.domain.model.Profile
import nullvp.domain.model.ProtocolType
import nullvp.theme.*

@Composable
fun ServerCard(
    profile: Profile,
    isSelected: Boolean,
    onSelect: () -> Unit,
    onEdit: (() -> Unit)? = null,
    onDuplicate: (() -> Unit)? = null,
    onDelete: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    var showMenu by remember { mutableStateOf(false) }
    val borderColor = if (isSelected) NeonCyan else Color.Transparent

    Card(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .border(1.5.dp, borderColor, RoundedCornerShape(12.dp)),
        colors = CardDefaults.cardColors(
            containerColor = if (isSelected) DarkSurfaceVariant else DarkSurface
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Main selectable body (tapping selects server)
            Row(
                modifier = Modifier
                    .weight(1f)
                    .clickable(onClick = onSelect),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Protocol Type Badge
                ProtocolBadge(protocolType = profile.protocolType)

                Spacer(modifier = Modifier.width(10.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = profile.name,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = OnDarkSurface,
                        maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    val subtitle = if (!profile.serverLocation.isNullOrBlank()) {
                        "${profile.serverFlag ?: "🌍"} ${profile.serverLocation} • ${profile.serverIp ?: profile.serverAddress}"
                    } else {
                        "${profile.serverAddress}:${profile.serverPort}"
                    }
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = OnDarkSurfaceVariant,
                        maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                    )
                    if (!profile.pingDetails.isNullOrBlank()) {
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = profile.pingDetails,
                            style = MaterialTheme.typography.labelSmall,
                            color = if (profile.pingMs >= 0) NeonCyan else CyberRed,
                            maxLines = 2,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.width(8.dp))

            // Ping badge
            PingBadge(pingMs = profile.pingMs)

            // Integrated 3-dots Menu INSIDE the card with isolated click target
            if (onEdit != null || onDelete != null || onDuplicate != null) {
                Box {
                    IconButton(
                        onClick = { showMenu = true },
                        modifier = Modifier.size(36.dp)
                    ) {
                        Icon(
                            Icons.Default.MoreVert,
                            contentDescription = "Опции",
                            tint = OnDarkSurfaceVariant,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    DropdownMenu(
                        expanded = showMenu,
                        onDismissRequest = { showMenu = false },
                        modifier = Modifier.background(DarkSurface)
                    ) {
                        onEdit?.let { editAction ->
                            DropdownMenuItem(
                                text = { Text("Настройки профиля") },
                                leadingIcon = { Icon(Icons.Default.Edit, contentDescription = null, tint = OnDarkSurface) },
                                onClick = {
                                    showMenu = false
                                    editAction()
                                }
                            )
                        }
                        onDuplicate?.let { dupAction ->
                            DropdownMenuItem(
                                text = { Text("Дублировать") },
                                leadingIcon = { Icon(Icons.Default.Share, contentDescription = null, tint = OnDarkSurface) },
                                onClick = {
                                    showMenu = false
                                    dupAction()
                                }
                            )
                        }
                        onDelete?.let { delAction ->
                            DropdownMenuItem(
                                text = { Text("Удалить", color = CyberRed) },
                                leadingIcon = { Icon(Icons.Default.Delete, contentDescription = null, tint = CyberRed) },
                                onClick = {
                                    showMenu = false
                                    delAction()
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun ProtocolBadge(protocolType: ProtocolType) {
    val (label, bg) = when (protocolType) {
        ProtocolType.MASTER_DNS -> "DNS" to Color(0xFF6200EA)
        ProtocolType.VK_TURN -> "VK TURN" to Color(0xFF0077FF)
        ProtocolType.MAILRU -> "MAILRU" to Color(0xFF005FF9)
        ProtocolType.VLESS -> "VLESS" to Color(0xFF00B0FF)
        ProtocolType.VMESS -> "VMESS" to Color(0xFF00897B)
        ProtocolType.TROJAN -> "TROJAN" to Color(0xFFE91E63)
        ProtocolType.WIREGUARD -> "WG" to Color(0xFFF57C00)
        ProtocolType.HYSTERIA2 -> "HY2" to Color(0xFFFF5252)
        ProtocolType.SHADOWSOCKS -> "SS" to Color(0xFF43A047)
        ProtocolType.CUSTOM -> "CUSTOM" to Color.Gray
    }

    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(bg.copy(alpha = 0.2f))
            .border(1.dp, bg.copy(alpha = 0.5f), RoundedCornerShape(6.dp))
            .padding(horizontal = 8.dp, vertical = 4.dp)
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            color = bg
        )
    }
}

@Composable
fun PingBadge(pingMs: Long) {
    val color = when {
        pingMs < 0 -> Color.Gray
        pingMs < 150 -> CyberGreen
        pingMs < 300 -> CyberAmber
        else -> CyberRed
    }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .background(color.copy(alpha = 0.15f))
            .padding(horizontal = 8.dp, vertical = 4.dp)
    ) {
        Box(
            modifier = Modifier
                .size(6.dp)
                .clip(CircleShape)
                .background(color)
        )
        Spacer(modifier = Modifier.width(6.dp))
        Text(
            text = if (pingMs >= 0) "${pingMs}ms" else "---",
            style = MaterialTheme.typography.labelSmall,
            color = color,
            fontWeight = FontWeight.Medium
        )
    }
}

