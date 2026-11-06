package nullvp.presentation.dialogs

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
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
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import kotlinx.serialization.json.*
import nullvp.domain.model.Profile
import nullvp.domain.model.ProtocolType
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
    val initialJson = remember(profile) {
        runCatching { Json.parseToJsonElement(profile.protocolRawConfig).jsonObject }.getOrNull()
    }

    var name by remember { mutableStateOf(profile.name) }
    var serverAddress by remember { mutableStateOf(profile.serverAddress) }
    var serverPort by remember { mutableStateOf(profile.serverPort.toString()) }

    // Protocol specific friendly states
    var uuid by remember {
        mutableStateOf(initialJson?.get("uuid")?.jsonPrimitive?.contentOrNull
            ?: initialJson?.get("id")?.jsonPrimitive?.contentOrNull ?: "")
    }
    var password by remember {
        mutableStateOf(initialJson?.get("password")?.jsonPrimitive?.contentOrNull
            ?: initialJson?.get("auth")?.jsonPrimitive?.contentOrNull ?: "")
    }
    var sni by remember {
        mutableStateOf(initialJson?.get("sni")?.jsonPrimitive?.contentOrNull
            ?: initialJson?.get("host")?.jsonPrimitive?.contentOrNull ?: profile.serverAddress)
    }
    var flow by remember {
        mutableStateOf(initialJson?.get("flow")?.jsonPrimitive?.contentOrNull ?: "xtls-rprx-vision")
    }
    var security by remember {
        mutableStateOf(initialJson?.get("security")?.jsonPrimitive?.contentOrNull ?: "reality")
    }
    var pbk by remember {
        mutableStateOf(initialJson?.get("pbk")?.jsonPrimitive?.contentOrNull ?: "")
    }
    var sid by remember {
        mutableStateOf(initialJson?.get("sid")?.jsonPrimitive?.contentOrNull ?: "")
    }
    var fp by remember {
        mutableStateOf(initialJson?.get("fp")?.jsonPrimitive?.contentOrNull ?: "chrome")
    }
    var network by remember {
        mutableStateOf(initialJson?.get("net")?.jsonPrimitive?.contentOrNull
            ?: initialJson?.get("network")?.jsonPrimitive?.contentOrNull ?: "tcp")
    }
    var path by remember {
        mutableStateOf(initialJson?.get("path")?.jsonPrimitive?.contentOrNull ?: "/")
    }
    var obfsPassword by remember {
        mutableStateOf(initialJson?.get("obfsPassword")?.jsonPrimitive?.contentOrNull ?: "")
    }
    var insecure by remember {
        mutableStateOf(initialJson?.get("insecure")?.jsonPrimitive?.booleanOrNull ?: false)
    }
    var ssMethod by remember {
        mutableStateOf(initialJson?.get("method")?.jsonPrimitive?.contentOrNull ?: "aes-128-gcm")
    }
    var vkCallUrl by remember {
        mutableStateOf(initialJson?.get("vk")?.jsonPrimitive?.contentOrNull ?: "")
    }
    var vkPeer by remember {
        mutableStateOf(initialJson?.get("peer")?.jsonPrimitive?.contentOrNull ?: "${profile.serverAddress}:${profile.serverPort}")
    }
    var streamsCount by remember {
        mutableStateOf((initialJson?.get("n")?.jsonPrimitive?.intOrNull ?: 3).toString())
    }
    var mdnsKey by remember {
        mutableStateOf(initialJson?.get("ENCRYPTION_KEY")?.jsonPrimitive?.contentOrNull ?: "")
    }
    var mdnsDomains by remember {
        mutableStateOf(initialJson?.get("DOMAINS")?.jsonArray?.joinToString(", ") { it.jsonPrimitive.content } ?: profile.serverAddress)
    }

    var showAdvancedJson by remember { mutableStateOf(false) }
    var rawConfig by remember { mutableStateOf(profile.protocolRawConfig) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.95f)
                .fillMaxHeight(0.90f)
                .clip(RoundedCornerShape(16.dp)),
            color = DarkSurface
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(24.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                // Header
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Настройки сервера",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = OnDarkSurface
                    )
                    Surface(
                        color = NeonCyan.copy(alpha = 0.2f),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text(
                            text = profile.protocolType.name,
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            color = NeonCyan,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                        )
                    }
                }

                // Profile Name
                NullVpTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Название сервера") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                // Server Address & Port Row
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    NullVpTextField(
                        value = serverAddress,
                        onValueChange = { serverAddress = it },
                        label = { Text("Адрес сервера / IP") },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )

                    NullVpTextField(
                        value = serverPort,
                        onValueChange = { serverPort = it.filter { ch -> ch.isDigit() } },
                        label = { Text("Порт") },
                        singleLine = true,
                        showPasteButton = false,
                        modifier = Modifier.width(100.dp)
                    )
                }

                // Friendly fields by protocol
                when (profile.protocolType) {
                    ProtocolType.VLESS -> {
                        NullVpTextField(
                            value = uuid,
                            onValueChange = { uuid = it },
                            label = { Text("UUID пользователя") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        NullVpTextField(
                            value = sni,
                            onValueChange = { sni = it },
                            label = { Text("SNI (Server Name)") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            NullVpTextField(
                                value = pbk,
                                onValueChange = { pbk = it },
                                label = { Text("Reality Public Key (pbk)") },
                                singleLine = true,
                                modifier = Modifier.weight(1f)
                            )
                            NullVpTextField(
                                value = sid,
                                onValueChange = { sid = it },
                                label = { Text("Short ID (sid)") },
                                singleLine = true,
                                modifier = Modifier.width(120.dp)
                            )
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            NullVpTextField(
                                value = flow,
                                onValueChange = { flow = it },
                                label = { Text("Flow (xtls-rprx-vision)") },
                                singleLine = true,
                                modifier = Modifier.weight(1f)
                            )
                            NullVpTextField(
                                value = fp,
                                onValueChange = { fp = it },
                                label = { Text("Fingerprint") },
                                singleLine = true,
                                modifier = Modifier.width(120.dp)
                            )
                        }
                    }

                    ProtocolType.HYSTERIA2 -> {
                        NullVpTextField(
                            value = password,
                            onValueChange = { password = it },
                            label = { Text("Пароль / Auth токен") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        NullVpTextField(
                            value = sni,
                            onValueChange = { sni = it },
                            label = { Text("SNI (Server Name)") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        NullVpTextField(
                            value = obfsPassword,
                            onValueChange = { obfsPassword = it },
                            label = { Text("Пароль обфускации (Salamander / опционально)") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(
                                checked = insecure,
                                onCheckedChange = { insecure = it },
                                colors = CheckboxDefaults.colors(checkedColor = NeonCyan)
                            )
                            Spacer(Modifier.width(4.dp))
                            Text("Разрешить небезопасный TLS (Insecure)", color = OnDarkSurface, fontSize = 13.sp)
                        }
                    }

                    ProtocolType.VMESS -> {
                        NullVpTextField(
                            value = uuid,
                            onValueChange = { uuid = it },
                            label = { Text("UUID пользователя") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        NullVpTextField(
                            value = network,
                            onValueChange = { network = it },
                            label = { Text("Тип сети (tcp / ws / grpc)") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }

                    ProtocolType.TROJAN -> {
                        NullVpTextField(
                            value = password,
                            onValueChange = { password = it },
                            label = { Text("Пароль Trojan") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        NullVpTextField(
                            value = sni,
                            onValueChange = { sni = it },
                            label = { Text("SNI (Server Name)") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }

                    ProtocolType.SHADOWSOCKS -> {
                        NullVpTextField(
                            value = ssMethod,
                            onValueChange = { ssMethod = it },
                            label = { Text("Метод шифрования (aes-128-gcm, etc.)") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        NullVpTextField(
                            value = password,
                            onValueChange = { password = it },
                            label = { Text("Пароль Shadowsocks") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }

                    ProtocolType.VK_TURN -> {
                        NullVpTextField(
                            value = vkPeer,
                            onValueChange = { vkPeer = it },
                            label = { Text("Peer адрес (IP:Port)") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        NullVpTextField(
                            value = vkCallUrl,
                            onValueChange = { vkCallUrl = it },
                            label = { Text("VK Call Join URL (vk.com/call/join/...)") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        NullVpTextField(
                            value = streamsCount,
                            onValueChange = { streamsCount = it.filter { ch -> ch.isDigit() } },
                            label = { Text("Количество параллельных потоков (1-8)") },
                            singleLine = true,
                            showPasteButton = false,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }

                    ProtocolType.MASTER_DNS -> {
                        NullVpTextField(
                            value = mdnsDomains,
                            onValueChange = { mdnsDomains = it },
                            label = { Text("Домены туннеля (через запятую)") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        NullVpTextField(
                            value = mdnsKey,
                            onValueChange = { mdnsKey = it },
                            label = { Text("Ключ шифрования (ENCRYPTION_KEY)") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }

                    else -> Unit
                }

                // Advanced / Raw JSON Expander
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = DarkSurfaceVariant.copy(alpha = 0.3f),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Расширенные параметры (Raw JSON)",
                                style = MaterialTheme.typography.labelMedium,
                                color = OnDarkSurfaceVariant,
                                fontWeight = FontWeight.SemiBold
                            )
                            IconButton(onClick = { showAdvancedJson = !showAdvancedJson }) {
                                Icon(
                                    if (showAdvancedJson) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                                    contentDescription = null,
                                    tint = NeonCyan
                                )
                            }
                        }

                        AnimatedVisibility(visible = showAdvancedJson) {
                            Column(modifier = Modifier.padding(top = 8.dp)) {
                                NullVpTextField(
                                    value = rawConfig,
                                    onValueChange = { rawConfig = it },
                                    placeholder = { Text("Raw JSON config...") },
                                    singleLine = false,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .heightIn(min = 100.dp, max = 200.dp),
                                    textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace)
                                )
                            }
                        }
                    }
                }

                if (errorMessage != null) {
                    Text(text = errorMessage!!, color = CyberRed, style = MaterialTheme.typography.bodySmall)
                }

                Spacer(modifier = Modifier.height(10.dp))

                // Action Buttons
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        IconButton(onClick = { onDelete(profile.id); onDismiss() }) {
                            Icon(Icons.Default.Delete, contentDescription = "Удалить", tint = CyberRed)
                        }
                        IconButton(onClick = { onDuplicate(profile); onDismiss() }) {
                            Icon(Icons.Default.Share, contentDescription = "Дублировать", tint = OnDarkSurfaceVariant)
                        }
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        TextButton(onClick = onDismiss) {
                            Text("Отмена", color = OnDarkSurfaceVariant)
                        }
                        Button(
                            onClick = {
                                val portNum = serverPort.toIntOrNull()
                                if (name.isBlank() || serverAddress.isBlank() || portNum == null || portNum <= 0) {
                                    errorMessage = "Укажите корректное имя, адрес и порт"
                                    return@Button
                                }

                                // Build updated JSON if user didn't manually edit raw JSON
                                val updatedRawConfig = if (!showAdvancedJson) {
                                    buildJsonObject {
                                        when (profile.protocolType) {
                                            ProtocolType.VLESS -> {
                                                put("uuid", uuid)
                                                put("host", serverAddress)
                                                put("port", portNum)
                                                put("sni", sni)
                                                put("flow", flow)
                                                put("security", security)
                                                put("pbk", pbk)
                                                put("sid", sid)
                                                put("fp", fp)
                                                put("name", name)
                                            }
                                            ProtocolType.HYSTERIA2 -> {
                                                put("server", "$serverAddress:$portNum")
                                                put("auth", password)
                                                put("sni", sni)
                                                put("obfsPassword", obfsPassword)
                                                put("insecure", insecure)
                                                put("name", name)
                                            }
                                            ProtocolType.TROJAN -> {
                                                put("password", password)
                                                put("host", serverAddress)
                                                put("port", portNum)
                                                put("sni", sni)
                                                put("insecure", insecure)
                                                put("name", name)
                                            }
                                            ProtocolType.SHADOWSOCKS -> {
                                                put("method", ssMethod)
                                                put("password", password)
                                                put("host", serverAddress)
                                                put("port", portNum)
                                                put("name", name)
                                            }
                                            ProtocolType.VK_TURN -> {
                                                put("peer", vkPeer)
                                                put("vk", vkCallUrl)
                                                put("n", streamsCount.toIntOrNull() ?: 3)
                                                put("name", name)
                                            }
                                            ProtocolType.MASTER_DNS -> {
                                                putJsonArray("DOMAINS") {
                                                    mdnsDomains.split(",").map { it.trim() }.filter { it.isNotEmpty() }.forEach { add(it) }
                                                }
                                                put("ENCRYPTION_KEY", mdnsKey)
                                                put("LISTEN_PORT", portNum)
                                                put("DATA_ENCRYPTION_METHOD", 1)
                                            }
                                            else -> {
                                                put("name", name)
                                            }
                                        }
                                    }.toString()
                                } else {
                                    rawConfig.trim()
                                }

                                val updated = profile.copy(
                                    name = name.trim(),
                                    serverAddress = serverAddress.trim(),
                                    serverPort = portNum,
                                    protocolRawConfig = updatedRawConfig
                                )
                                onSave(updated)
                                onDismiss()
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = NeonCyan, contentColor = Color.Black)
                        ) {
                            Text("Сохранить", fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
    }
}
