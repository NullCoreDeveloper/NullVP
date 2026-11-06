package nullvp.presentation.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import nullvp.domain.model.Subscription
import nullvp.theme.*

@Composable
fun SubscriptionCard(
    subscription: Subscription,
    onRefresh: () -> Unit,
    onDelete: () -> Unit,
    onRename: (String) -> Unit = {},
    modifier: Modifier = Modifier
) {
    var showRenameDialog by remember { mutableStateOf(false) }
    var editName by remember(subscription.name) { mutableStateOf(subscription.name) }

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
                    IconButton(onClick = { showRenameDialog = true }) {
                        Icon(Icons.Default.Edit, contentDescription = "Rename", tint = OnDarkSurfaceVariant)
                    }
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

                if (subscription.expireTimestamp > 0) {
                    val daysLeft = ((subscription.expireTimestamp - System.currentTimeMillis()) / (1000 * 60 * 60 * 24)).coerceAtLeast(0)
                    Text(
                        text = "$daysLeft days left",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (daysLeft < 3) CyberAmber else OnDarkSurfaceVariant
                    )
                }
            }
        }
    }

    if (showRenameDialog) {
        AlertDialog(
            onDismissRequest = { showRenameDialog = false },
            title = { Text("Переименовать подписку", color = OnDarkSurface, fontWeight = FontWeight.Bold) },
            text = {
                Column {
                    Text("Введите новое название подписки:", style = MaterialTheme.typography.bodyMedium, color = OnDarkSurfaceVariant)
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = editName,
                        onValueChange = { editName = it },
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = NeonCyan,
                            unfocusedBorderColor = DarkSurfaceVariant,
                            focusedTextColor = OnDarkSurface,
                            unfocusedTextColor = OnDarkSurface
                        )
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (editName.isNotBlank()) {
                            onRename(editName.trim())
                        }
                        showRenameDialog = false
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = NeonCyan, contentColor = Color.Black)
                ) {
                    Text("Сохранить", fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showRenameDialog = false }) {
                    Text("Отмена", color = OnDarkSurfaceVariant)
                }
            },
            containerColor = DarkSurface
        )
    }
}

private fun formatBytes(bytes: Long): String {
    if (bytes <= 0) return "0 B"
    val units = arrayOf("B", "KB", "MB", "GB", "TB")
    var b = bytes.toDouble()
    var i = 0
    while (b >= 1024 && i < units.size - 1) {
        b /= 1024
        i++
    }
    val rounded = (b * 100).toLong() / 100.0
    return "$rounded ${units[i]}"
}
