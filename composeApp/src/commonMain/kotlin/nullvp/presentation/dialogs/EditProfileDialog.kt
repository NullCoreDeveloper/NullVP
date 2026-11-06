package nullvp.presentation.dialogs

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import nullvp.domain.model.Profile
import nullvp.presentation.components.NullVpTextField
import nullvp.theme.*

@Composable
fun EditProfileDialog(
    profile: Profile,
    onDismiss: () -> Unit,
    onSave: (Profile) -> Unit,
    onDelete: (String) -> Unit,
    onDuplicate: (Profile) -> Unit
) {
    var name by remember { mutableStateOf(profile.name) }
    var serverAddress by remember { mutableStateOf(profile.serverAddress) }
    var serverPort by remember { mutableStateOf(profile.serverPort.toString()) }
    var rawConfig by remember { mutableStateOf(profile.protocolRawConfig) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.95f)
                .wrapContentHeight()
                .clip(RoundedCornerShape(16.dp)),
            color = DarkSurface
        ) {
            Column(
                modifier = Modifier
                    .padding(24.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                // Header
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Edit Profile",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = OnDarkSurface
                    )
                    Text(
                        text = profile.protocolType.name,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = NeonCyan
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Profile Name
                NullVpTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Profile Name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(12.dp))

                // Server Address & Port Row
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    NullVpTextField(
                        value = serverAddress,
                        onValueChange = { serverAddress = it },
                        label = { Text("Server Host / IP") },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )

                    NullVpTextField(
                        value = serverPort,
                        onValueChange = { serverPort = it.filter { ch -> ch.isDigit() } },
                        label = { Text("Port") },
                        singleLine = true,
                        showPasteButton = false,
                        modifier = Modifier.width(100.dp)
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))

                // Protocol Raw Config (JSON / parameters)
                Text(
                    text = "Raw Protocol Configuration (JSON)",
                    style = MaterialTheme.typography.labelSmall,
                    color = OnDarkSurfaceVariant
                )
                Spacer(modifier = Modifier.height(4.dp))
                NullVpTextField(
                    value = rawConfig,
                    onValueChange = { rawConfig = it },
                    placeholder = { Text("Protocol JSON config...") },
                    singleLine = false,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 120.dp, max = 220.dp),
                    textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace)
                )

                if (errorMessage != null) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(text = errorMessage!!, color = CyberRed, style = MaterialTheme.typography.bodySmall)
                }

                Spacer(modifier = Modifier.height(20.dp))

                // Action Buttons
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        IconButton(onClick = { onDelete(profile.id); onDismiss() }) {
                            Icon(Icons.Default.Delete, contentDescription = "Delete", tint = CyberRed)
                        }
                        IconButton(onClick = { onDuplicate(profile); onDismiss() }) {
                            Icon(Icons.Default.Share, contentDescription = "Duplicate", tint = OnDarkSurfaceVariant)
                        }
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        TextButton(onClick = onDismiss) {
                            Text("Cancel", color = OnDarkSurfaceVariant)
                        }
                        Button(
                            onClick = {
                                val portNum = serverPort.toIntOrNull()
                                if (name.isBlank() || serverAddress.isBlank() || portNum == null || portNum <= 0) {
                                    errorMessage = "Please enter valid name, host and port"
                                    return@Button
                                }
                                val updated = profile.copy(
                                    name = name.trim(),
                                    serverAddress = serverAddress.trim(),
                                    serverPort = portNum,
                                    protocolRawConfig = rawConfig.trim()
                                )
                                onSave(updated)
                                onDismiss()
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = NeonCyan, contentColor = Color.Black)
                        ) {
                            Text("Save Changes", fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
    }
}
