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
import nullvp.data.store.AppStateStore
import nullvp.domain.model.Subscription
import nullvp.i18n.AppLanguage
import nullvp.i18n.AppStrings
import nullvp.theme.*

@Composable
fun SubscriptionCard(
    subscription: Subscription,
    onRefresh: () -> Unit,
    onDelete: () -> Unit,
    onRename: (String) -> Unit = {},
    strings: AppStrings? = null,
    modifier: Modifier = Modifier
) {
    var showRenameDialog by remember { mutableStateOf(false) }
    var editName by remember(subscription.name) { mutableStateOf(subscription.name) }
    val settings by AppStateStore.settings.collectAsState()
    val isRu = settings.language == AppLanguage.RU

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
                        if (isRu) "Трафик: ${formatBytes(usedBytes)} из ${formatBytes(subscription.totalBytes)}"
                        else "Used: ${formatBytes(usedBytes)} / ${formatBytes(subscription.totalBytes)}"
                    } else {
                        if (isRu) "Трафик: ${formatBytes(usedBytes)} (безлимит)"
                        else "Used: ${formatBytes(usedBytes)} (Unlimited)"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = OnDarkSurfaceVariant
                )

                // Normalize timestamp: seconds (< 100 млрд) vs milliseconds
                val expireMs = when {
                    subscription.expireTimestamp <= 0L -> 0L
                    subscription.expireTimestamp < 100_000_000_000L -> subscription.expireTimestamp * 1000L
                    else -> subscription.expireTimestamp
                }

                if (expireMs > 0L) {
                    val diffMs = expireMs - System.currentTimeMillis()
                    val (expiryText, expiryColor) = when {
                        diffMs <= 0L -> {
                            (if (isRu) "Срок истёк" else "Expired") to CyberRed
                        }
                        diffMs < 60L * 60L * 1000L -> {
                            val mins = (diffMs / (60L * 1000L)).coerceAtLeast(1L)
                            (if (isRu) "Осталось: $mins мин" else "$mins min left") to CyberRed
                        }
                        diffMs < 24L * 60L * 60L * 1000L -> {
                            val hours = (diffMs / (60L * 60L * 1000L)).coerceAtLeast(1L)
                            formatHoursLeft(hours, isRu) to CyberRed
                        }
                        else -> {
                            val days = (diffMs / (24L * 60L * 60L * 1000L)).coerceAtLeast(1L)
                            val color = if (days < 3L) CyberAmber else OnDarkSurfaceVariant
                            formatDaysLeft(days, isRu) to color
                        }
                    }

                    Text(
                        text = expiryText,
                        style = MaterialTheme.typography.labelSmall,
                        color = expiryColor,
                        fontWeight = if (diffMs <= 0L || diffMs < 3L * 24L * 60L * 60L * 1000L) FontWeight.SemiBold else FontWeight.Normal
                    )
                }
            }
        }
    }

    if (showRenameDialog) {
        AlertDialog(
            onDismissRequest = { showRenameDialog = false },
            title = {
                Text(
                    text = if (isRu) "Переименовать подписку" else "Rename Subscription",
                    color = OnDarkSurface,
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Column {
                    Text(
                        text = if (isRu) "Введите новое название подписки:" else "Enter new subscription name:",
                        style = MaterialTheme.typography.bodyMedium,
                        color = OnDarkSurfaceVariant
                    )
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
                    Text(if (isRu) "Сохранить" else "Save", fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showRenameDialog = false }) {
                    Text(if (isRu) "Отмена" else "Cancel", color = OnDarkSurfaceVariant)
                }
            },
            containerColor = DarkSurface
        )
    }
}

private fun formatDaysLeft(days: Long, isRu: Boolean): String {
    if (!isRu) {
        return if (days == 1L) "1 day left" else "$days days left"
    }
    val rem10 = days % 10
    val rem100 = days % 100
    val word = when {
        rem100 in 11..19 -> "дней"
        rem10 == 1L -> "день"
        rem10 in 2..4 -> "дня"
        else -> "дней"
    }
    val verb = if (rem10 == 1L && rem100 != 11L) "Остался" else "Осталось"
    return "$verb $days $word"
}

private fun formatHoursLeft(hours: Long, isRu: Boolean): String {
    if (!isRu) {
        return if (hours == 1L) "1 hour left" else "$hours hours left"
    }
    val rem10 = hours % 10
    val rem100 = hours % 100
    val word = when {
        rem100 in 11..19 -> "часов"
        rem10 == 1L -> "час"
        rem10 in 2..4 -> "часа"
        else -> "часов"
    }
    val verb = if (rem10 == 1L && rem100 != 11L) "Остался" else "Осталось"
    return "$verb $hours $word"
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
