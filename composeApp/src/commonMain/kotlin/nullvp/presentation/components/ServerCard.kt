package nullvp.presentation.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
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
    modifier: Modifier = Modifier
) {
    val borderColor = if (isSelected) NeonCyan else Color.Transparent

    Card(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .border(1.5.dp, borderColor, RoundedCornerShape(12.dp))
            .clickable(onClick = onSelect),
        colors = CardDefaults.cardColors(
            containerColor = if (isSelected) DarkSurfaceVariant else DarkSurface
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Protocol Type Badge
            ProtocolBadge(protocolType = profile.protocolType)

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = profile.name,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = OnDarkSurface
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = "${profile.serverAddress}:${profile.serverPort}",
                    style = MaterialTheme.typography.bodySmall,
                    color = OnDarkSurfaceVariant
                )
            }

            Spacer(modifier = Modifier.width(8.dp))

            // Ping badge
            PingBadge(pingMs = profile.pingMs)
        }
    }
}

@Composable
fun ProtocolBadge(protocolType: ProtocolType) {
    val (label, bg) = when (protocolType) {
        ProtocolType.MASTER_DNS -> "DNS" to Color(0xFF6200EA)
        ProtocolType.VK_TURN -> "VK TURN" to Color(0xFF0077FF)
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

