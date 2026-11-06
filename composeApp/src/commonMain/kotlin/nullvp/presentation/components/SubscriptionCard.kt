package nullvp.presentation.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import nullvp.domain.model.Subscription
import nullvp.theme.*

@Composable
fun SubscriptionCard(
    subscription: Subscription,
    onRefresh: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .border(1.dp, DarkSurfaceVariant, RoundedCornerShape(14.dp)),
        colors = CardDefaults.cardColors(containerColor = DarkSurface)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            // Header: Name and Action buttons
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = subscription.name,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = OnDarkSurface
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = subscription.url,
                        style = MaterialTheme.typography.bodySmall,
                        color = OnDarkSurfaceVariant,
                        maxLines = 1
                    )
                }

                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    IconButton(onClick = onRefresh) {
                        Icon(Icons.Default.Refresh, contentDescription = "Refresh", tint = NeonCyan)
                    }
                    IconButton(onClick = onDelete) {
                        Icon(Icons.Default.Delete, contentDescription = "Delete", tint = CyberRed)
                    }
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            // Traffic Usage Progress Bar
            val usedBytes = subscription.uploadBytes + subscription.downloadBytes
            val totalBytes = if (subscription.totalBytes > 0) subscription.totalBytes else 1L
            val progress = if (subscription.totalBytes > 0) {
                (usedBytes.toFloat() / totalBytes.toFloat()).coerceIn(0f, 1f)
            } else 0f

            LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(6.dp)
                    .clip(RoundedCornerShape(3.dp)),
                color = if (progress > 0.85f) CyberRed else NeonCyan,
                trackColor = DarkSurfaceVariant
            )

            Spacer(modifier = Modifier.height(8.dp))

            // Traffic & Expiry info row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = if (subscription.totalBytes > 0) {
                        "Used: ${formatBytes(usedBytes)} / ${formatBytes(subscription.totalBytes)}"
                    } else {
                        "Used: ${formatBytes(usedBytes)} (Unlimited)"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = OnDarkSurfaceVariant
                )

                Text(
                    text = if (subscription.expireTimestamp > 0) {
                        val daysRemaining = (subscription.expireTimestamp - System.currentTimeMillis()) / (1000 * 3600 * 24)
                        if (daysRemaining > 0) "$daysRemaining days left" else "Expired"
                    } else "No Expiry",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = if (subscription.expireTimestamp > 0 && subscription.expireTimestamp < System.currentTimeMillis()) CyberRed else CyberGreen
                )
            }
        }
    }
}

private fun formatBytes(bytes: Long): String {
    return when {
        bytes >= 1024L * 1024L * 1024L -> String.format("%.1f GB", bytes / (1024.0 * 1024.0 * 1024.0))
        bytes >= 1024L * 1024L -> String.format("%.1f MB", bytes / (1024.0 * 1024.0))
        bytes >= 1024L -> String.format("%.1f KB", bytes / 1024.0)
        else -> "$bytes B"
    }
}

