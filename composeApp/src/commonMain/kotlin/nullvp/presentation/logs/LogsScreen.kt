package nullvp.presentation.logs

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import nullvp.presentation.components.AppVerticalScrollbar
import nullvp.presentation.components.NullVpTextField
import nullvp.data.store.AppStateStore
import nullvp.data.store.LogLevel
import nullvp.i18n.Localization
import nullvp.theme.*

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
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = strings.logsTitle,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onBackground,
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                )
                Text(
                    text = "${filteredLogs.size} ${if (settings.language.code == "ru") "записей" else "entries"}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(Modifier.width(8.dp))

            // Action Buttons
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                // Copy Button
                IconButton(
                    onClick = {
                        val text = AppStateStore.exportLogsToString()
                        clipboardManager.setText(AnnotatedString(text))
                        snackbarMessage = strings.logsCopied
                    },
                    modifier = Modifier.size(36.dp)
                ) {
                    Icon(PasteIcon, contentDescription = strings.copyLogs, tint = OnDarkSurfaceVariant, modifier = Modifier.size(20.dp))
                }

                // Export to File Button
                IconButton(
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
                    modifier = Modifier.size(36.dp)
                ) {
                    Icon(Icons.Default.Share, contentDescription = strings.exportFile, tint = NeonCyan, modifier = Modifier.size(20.dp))
                }

                // Clear Button
                IconButton(
                    onClick = { AppStateStore.clearLogs() },
                    modifier = Modifier.size(36.dp)
                ) {
                    Icon(
                        Icons.Default.Delete,
                        contentDescription = strings.clearLogs,
                        tint = CyberRed.copy(alpha = 0.8f),
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
        }

        Spacer(Modifier.height(12.dp))

        // Search Bar and Autoscroll
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            NullVpTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                placeholder = { Text(strings.searchLogs, fontSize = 13.sp) },
                modifier = Modifier.weight(1f),
                singleLine = true,
                showPasteButton = false,
                showClearButton = true
            )

            // Autoscroll toggle
            FilterChip(
                selected = autoscroll,
                onClick = { autoscroll = !autoscroll },
                label = { Text(strings.autoscroll, fontSize = 12.sp, maxLines = 1) },
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = NeonCyan.copy(alpha = 0.2f),
                    selectedLabelColor = NeonCyan
                )
            )
        }

        Spacer(Modifier.height(8.dp))

        // Level Filter Chips with horizontal scroll (prevents ERR\nOR word wrapping!)
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            FilterChip(
                selected = selectedLevel == null,
                onClick = { selectedLevel = null },
                label = { Text(strings.filterLevelAll, fontSize = 11.sp, maxLines = 1) }
            )
            LogLevel.values().forEach { level ->
                val selected = selectedLevel == level
                val chipColor = when (level) {
                    LogLevel.INFO -> NeonCyan
                    LogLevel.WARN -> CyberAmber
                    LogLevel.ERROR -> CyberRed
                    LogLevel.DEBUG -> Color(0xFF90A4AE)
                }
                FilterChip(
                    selected = selected,
                    onClick = { selectedLevel = if (selected) null else level },
                    label = { Text(level.name, fontSize = 11.sp, maxLines = 1) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = chipColor.copy(alpha = 0.2f),
                        selectedLabelColor = chipColor
                    )
                )
            }
        }

        Spacer(Modifier.height(12.dp))

        // Terminal-style Log Viewport with SelectionContainer & Vertical Scrollbar
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF0A0E17)),
            border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF1F293D))
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
                SelectionContainer(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(start = 12.dp, top = 12.dp, bottom = 12.dp, end = 20.dp)
                ) {
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
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            itemsIndexed(filteredLogs, key = { index, log -> "${log.id}_$index" }) { _, log ->
                                LogItemRow(log)
                            }
                        }
                    }
                }

                // Draggable vertical scrollbar on the right edge
                AppVerticalScrollbar(
                    listState = listState,
                    modifier = Modifier
                        .align(Alignment.CenterEnd)
                        .fillMaxHeight()
                        .padding(vertical = 4.dp, horizontal = 2.dp)
                )
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
        LogLevel.INFO -> NeonCyan
        LogLevel.WARN -> CyberAmber
        LogLevel.ERROR -> CyberRed
        LogLevel.DEBUG -> Color(0xFF78909C)
    }

    val seconds = (log.timestamp / 1000) % 86400
    val hours = (seconds / 3600) % 24
    val minutes = (seconds % 3600) / 60
    val secs = seconds % 60
    val millis = log.timestamp % 1000
    val timeStr = "${hours.toString().padStart(2, '0')}:${minutes.toString().padStart(2, '0')}:${secs.toString().padStart(2, '0')}.${millis.toString().padStart(3, '0')}"

    val annotated = remember(log) {
        buildAnnotatedString {
            withStyle(SpanStyle(color = Color(0xFF546E7A), fontFamily = FontFamily.Monospace, fontSize = 11.sp)) {
                append("$timeStr ")
            }
            withStyle(SpanStyle(color = levelColor, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace, fontSize = 11.sp)) {
                append("${log.level.name.padEnd(5)} ")
            }
            withStyle(SpanStyle(color = Color(0xFF80CBC4), fontWeight = FontWeight.SemiBold, fontFamily = FontFamily.Monospace, fontSize = 11.sp)) {
                append("[${log.tag}] ")
            }
            withStyle(SpanStyle(color = Color(0xFFECEFF1), fontFamily = FontFamily.Monospace, fontSize = 11.sp)) {
                append(log.message)
            }
        }
    }

    Text(
        text = annotated,
        modifier = Modifier.fillMaxWidth(),
        lineHeight = 16.sp
    )
}

