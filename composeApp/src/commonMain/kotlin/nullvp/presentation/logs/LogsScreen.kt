package nullvp.presentation.logs

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import nullvp.presentation.components.NullVpTextField
import nullvp.data.store.AppStateStore
import nullvp.data.store.LogLevel
import nullvp.i18n.Localization

@Composable
fun LogsScreen() {
    val settings by AppStateStore.settings.collectAsState()
    val strings = Localization.get(settings.language)
    val logs by AppStateStore.logs.collectAsState()

    val clipboardManager = LocalClipboardManager.current
    val coroutineScope = rememberCoroutineScope()
    val listState = rememberLazyListState()

    var searchQuery by remember { mutableStateOf("") }
    var selectedLevel by remember { mutableStateOf<LogLevel?>(null) }
    var autoscroll by remember { mutableStateOf(true) }
    var snackbarMessage by remember { mutableStateOf<String?>(null) }

    // Filtered logs
    val filteredLogs = remember(logs, searchQuery, selectedLevel) {
        logs.filter { entry ->
            val matchLevel = selectedLevel == null || entry.level == selectedLevel
            val matchQuery = searchQuery.isBlank() ||
                    entry.message.contains(searchQuery, ignoreCase = true) ||
                    entry.tag.contains(searchQuery, ignoreCase = true)
            matchLevel && matchQuery
        }
    }

    // Auto-scroll when new logs arrive
    LaunchedEffect(filteredLogs.size, autoscroll) {
        if (autoscroll && filteredLogs.isNotEmpty()) {
            listState.animateScrollToItem(filteredLogs.size - 1)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        // Top Header and Action Buttons
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    text = strings.logsTitle,
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onBackground
                )
                Text(
                    text = "${filteredLogs.size} ${if (settings.language.code == "ru") "записей" else "entries"}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // Action Buttons
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                // Copy Button
                Button(
                    onClick = {
                        val text = AppStateStore.exportLogsToString()
                        clipboardManager.setText(AnnotatedString(text))
                        snackbarMessage = strings.logsCopied
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text(strings.copyLogs, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }

                // Export to File Button
                Button(
                    onClick = {
                        try {
                            val userHome = System.getProperty("user.home") ?: "/tmp"
                            val filename = "$userHome/nullvp_logs_${System.currentTimeMillis()}.log"
                            java.io.File(filename).writeText(AppStateStore.exportLogsToString())
                            snackbarMessage = "${strings.logsExported}$filename"
                        } catch (e: Exception) {
                            snackbarMessage = "Export failed: ${e.message}"
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text(strings.exportFile, fontSize = 12.sp)
                }

                // Clear Button
                IconButton(
                    onClick = { AppStateStore.clearLogs() },
                    modifier = Modifier.size(36.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Delete,
                        contentDescription = strings.clearLogs,
                        tint = MaterialTheme.colorScheme.error
                    )
                }
            }
        }

        Spacer(Modifier.height(12.dp))

        // Search Bar and Filter Chips
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            NullVpTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                placeholder = { Text(strings.searchLogs, fontSize = 13.sp) },
                modifier = Modifier.weight(1f),
                singleLine = true
            )

            // Autoscroll toggle
            FilterChip(
                selected = autoscroll,
                onClick = { autoscroll = !autoscroll },
                label = { Text(strings.autoscroll, fontSize = 12.sp) },
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.2f),
                    selectedLabelColor = MaterialTheme.colorScheme.primary
                )
            )
        }

        Spacer(Modifier.height(8.dp))

        // Level Filter Chips
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            FilterChip(
                selected = selectedLevel == null,
                onClick = { selectedLevel = null },
                label = { Text(strings.filterLevelAll, fontSize = 11.sp) }
            )
            LogLevel.values().forEach { level ->
                val selected = selectedLevel == level
                val chipColor = when (level) {
                    LogLevel.INFO -> Color(0xFF00E5FF)
                    LogLevel.WARN -> Color(0xFFFFD600)
                    LogLevel.ERROR -> Color(0xFFFF5252)
                    LogLevel.DEBUG -> Color(0xFF90A4AE)
                }
                FilterChip(
                    selected = selected,
                    onClick = { selectedLevel = if (selected) null else level },
                    label = { Text(level.name, fontSize = 11.sp) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = chipColor.copy(alpha = 0.2f),
                        selectedLabelColor = chipColor
                    )
                )
            }
        }

        Spacer(Modifier.height(12.dp))

        // Terminal-style Log Viewport with SelectionContainer
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF0A0E17)),
            border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF1F293D))
        ) {
            SelectionContainer(modifier = Modifier.fillMaxSize().padding(12.dp)) {
                if (filteredLogs.isEmpty()) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(
                            text = if (settings.language.code == "ru") "Логов пока нет" else "No logs recorded",
                            color = Color(0xFF546E7A),
                            fontFamily = FontFamily.Monospace,
                            fontSize = 13.sp
                        )
                    }
                } else {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        itemsIndexed(filteredLogs, key = { index, log -> "${log.id}_$index" }) { _, log ->
                            LogItemRow(log)
                        }
                    }
                }
            }
        }

        // Snackbar feedback
        snackbarMessage?.let { msg ->
            Spacer(Modifier.height(8.dp))
            Snackbar(
                action = {
                    TextButton(onClick = { snackbarMessage = null }) {
                        Text(strings.close, color = MaterialTheme.colorScheme.primary)
                    }
                }
            ) {
                Text(msg)
            }
        }
    }
}

@Composable
private fun LogItemRow(log: nullvp.data.store.LogEntry) {
    val levelColor = when (log.level) {
        LogLevel.INFO -> Color(0xFF00E5FF)
        LogLevel.WARN -> Color(0xFFFFD600)
        LogLevel.ERROR -> Color(0xFFFF5252)
        LogLevel.DEBUG -> Color(0xFF78909C)
    }

    val seconds = (log.timestamp / 1000) % 86400
    val hours = (seconds / 3600) % 24
    val minutes = (seconds % 3600) / 60
    val secs = seconds % 60
    val millis = log.timestamp % 1000
    val timeStr = "${hours.toString().padStart(2, '0')}:${minutes.toString().padStart(2, '0')}:${secs.toString().padStart(2, '0')}.${millis.toString().padStart(3, '0')}"

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Top
    ) {
        // Timestamp
        Text(
            text = timeStr,
            fontFamily = FontFamily.Monospace,
            fontSize = 11.sp,
            color = Color(0xFF546E7A),
            modifier = Modifier.padding(end = 8.dp)
        )

        // Level Badge
        Text(
            text = log.level.name.padEnd(5),
            fontFamily = FontFamily.Monospace,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            color = levelColor,
            modifier = Modifier.padding(end = 8.dp)
        )

        // Tag
        Text(
            text = "[${log.tag}]",
            fontFamily = FontFamily.Monospace,
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            color = Color(0xFFB0BEC5),
            modifier = Modifier.padding(end = 8.dp)
        )

        // Message
        Text(
            text = log.message,
            fontFamily = FontFamily.Monospace,
            fontSize = 11.sp,
            color = Color(0xFFECEFF1)
        )
    }
}

