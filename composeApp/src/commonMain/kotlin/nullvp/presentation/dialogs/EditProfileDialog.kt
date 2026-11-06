package nullvp.presentation.dialogs

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import kotlinx.serialization.json.*
import nullvp.domain.model.Profile
import nullvp.domain.model.ProtocolType
import nullvp.presentation.components.NullVpTextField
import nullvp.theme.*
import java.net.URLDecoder

@Composable
fun EditProfileDialog(
    profile: Profile,
    onDismiss: () -> Unit,
    onSave: (Profile) -> Unit,
    onDelete: (String) -> Unit,
    onDuplicate: (Profile) -> Unit
) {
    val parsedParams = remember(profile) {
        val map = mutableMapOf<String, String>()
        val obj = runCatching { Json.parseToJsonElement(profile.protocolRawConfig).jsonObject }.getOrNull()

        // Unpack query params
        obj?.get("query")?.jsonPrimitive?.contentOrNull?.let { queryStr ->
            if (queryStr.isNotBlank()) {
                queryStr.split('&').forEach { param ->
                    val eqIdx = param.indexOf('=')
                    if (eqIdx > 0) {
                        val k = param.substring(0, eqIdx)
                        val rawV = param.substring(eqIdx + 1)
                        val v = runCatching { URLDecoder.decode(rawV, "UTF-8") }.getOrDefault(rawV)
                        map[k] = v
                    }
                }
            }
        }

        // Overlay direct fields
        obj?.forEach { (k, v) ->
            if (k != "query") {
                if (v is JsonPrimitive && v.contentOrNull != null) {
                    map[k] = v.content
                } else {
                    map[k] = v.toString()
                }
            }
        }
        map
    }

    // Tabs: 0: General, 1: Transport, 2: Security (TLS/Reality), 3: Protocol Specific, 4: Raw JSON
    var activeTab by remember { mutableStateOf(0) }

    // General
    var name by remember { mutableStateOf(profile.name) }
    var serverAddress by remember { mutableStateOf(profile.serverAddress) }
    var serverPort by remember { mutableStateOf(profile.serverPort.toString()) }

    // Security & Auth
    var uuid by remember {
        mutableStateOf(parsedParams["uuid"] ?: parsedParams["id"] ?: "")
    }
    var password by remember {
        mutableStateOf(parsedParams["password"] ?: parsedParams["auth"] ?: "")
    }
    var flow by remember {
        mutableStateOf(parsedParams["flow"] ?: "xtls-rprx-vision")
    }
    var encryption by remember {
        mutableStateOf(parsedParams["encryption"] ?: "none")
    }

    // TLS / Reality
    var security by remember {
        mutableStateOf(
            parsedParams["security"]?.lowercase() ?: if (parsedParams.containsKey("pbk")) "reality" else "tls"
        )
    }
    var sni by remember {
        mutableStateOf(parsedParams["sni"] ?: parsedParams["host"] ?: profile.serverAddress)
    }
    var alpn by remember {
        mutableStateOf(parsedParams["alpn"] ?: "h2,http/1.1,h3")
    }
    var fp by remember {
        mutableStateOf(parsedParams["fp"] ?: parsedParams["fingerprint"] ?: "chrome")
    }
    var pbk by remember {
        mutableStateOf(parsedParams["pbk"] ?: parsedParams["publicKey"] ?: "")
    }
    var sid by remember {
        mutableStateOf(parsedParams["sid"] ?: parsedParams["shortId"] ?: "")
    }
    var spx by remember {
        mutableStateOf(parsedParams["spx"] ?: parsedParams["spiderX"] ?: "/")
    }
    var insecure by remember {
        mutableStateOf(parsedParams["insecure"] == "1" || parsedParams["insecure"].equals("true", true))
    }

    // Transport & Network
    var network by remember {
        mutableStateOf(parsedParams["type"] ?: parsedParams["network"] ?: parsedParams["net"] ?: "tcp")
    }
    var headerType by remember {
        mutableStateOf(parsedParams["headerType"] ?: "none")
    }
    var path by remember {
        mutableStateOf(parsedParams["path"] ?: "/")
    }
    var hostHeader by remember {
        mutableStateOf(parsedParams["host"] ?: sni)
    }
    var xhttpMode by remember {
        mutableStateOf(parsedParams["mode"] ?: "auto")
    }
    var serviceName by remember {
        mutableStateOf(parsedParams["serviceName"] ?: "")
    }

    // Protocol Specific: VK_TURN
    var vkCallUrl by remember {
        mutableStateOf(parsedParams["vkLink"] ?: parsedParams["vk"] ?: "")
    }
    var vkPeer by remember {
        mutableStateOf(parsedParams["peer"] ?: "${profile.serverAddress}:${profile.serverPort}")
    }
    var streamsCount by remember {
        mutableStateOf(parsedParams["streams"] ?: parsedParams["n"] ?: "10")
    }
    var wgSecretKey by remember {
        mutableStateOf(parsedParams["secretKey"] ?: "")
    }
    var wgPublicKey by remember {
        mutableStateOf(parsedParams["publicKey"] ?: "")
    }
    var wgAddress by remember {
        mutableStateOf(parsedParams["address"] ?: "10.0.0.2/32")
    }
    var wgMtu by remember {
        mutableStateOf(parsedParams["mtu"] ?: "1280")
    }
    var obfProfile by remember {
        mutableStateOf(parsedParams["obfProfile"] ?: parsedParams["obf"] ?: "rtpopus3")
    }
    var obfKey by remember {
        mutableStateOf(parsedParams["obfKey"] ?: parsedParams["key"] ?: "")
    }

    // Protocol Specific: Hysteria 2
    var obfsPassword by remember {
        mutableStateOf(parsedParams["obfsPassword"] ?: parsedParams["obfs-password"] ?: "")
    }

    // Protocol Specific: Shadowsocks
    var ssMethod by remember {
        mutableStateOf(parsedParams["method"] ?: "aes-128-gcm")
    }

    // Protocol Specific: Master DNS
    var mdnsKey by remember {
        mutableStateOf(parsedParams["ENCRYPTION_KEY"] ?: "")
    }
    var mdnsDomains by remember {
        mutableStateOf(parsedParams["DOMAINS"] ?: profile.serverAddress)
    }

    // Advanced Raw JSON
    var rawConfig by remember { mutableStateOf(profile.protocolRawConfig) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.96f)
                .fillMaxHeight(0.92f)
                .clip(RoundedCornerShape(16.dp)),
            color = DarkSurface
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(20.dp)
            ) {
                // Header
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            text = "Настройки профиля",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            color = OnDarkSurface
                        )
                        Text(
                            text = "${profile.protocolType.name} (${profile.serverAddress}:${profile.serverPort})",
                            style = MaterialTheme.typography.bodySmall,
                            color = OnDarkSurfaceVariant
                        )
                    }

                    Surface(
                        color = NeonCyan.copy(alpha = 0.2f),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text(
                            text = profile.protocolType.name,
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            color = NeonCyan,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                        )
                    }
                }

                Spacer(Modifier.height(14.dp))

                // Navigation Tabs
                val tabTitles = listOf("Общие", "Транспорт", "Безопасность", "Протокол", "Raw JSON")
                ScrollableTabRow(
                    selectedTabIndex = activeTab,
                    edgePadding = 0.dp,
                    containerColor = Color.Transparent,
                    contentColor = NeonCyan,
                    divider = {}
                ) {
                    tabTitles.forEachIndexed { index, title ->
                        Tab(
                            selected = activeTab == index,
                            onClick = { activeTab = index },
                            text = {
                                Text(
                                    text = title,
                                    fontWeight = if (activeTab == index) FontWeight.Bold else FontWeight.Normal,
                                    fontSize = 13.sp
                                )
                            }
                        )
                    }
                }

                Spacer(Modifier.height(14.dp))

                // Tab Content
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        when (activeTab) {
                            // 0: General Tab
                            0 -> {
                                NullVpTextField(
                                    value = name,
                                    onValueChange = { name = it },
                                    label = { Text("Название сервера (Remark)") },
                                    singleLine = true,
                                    modifier = Modifier.fillMaxWidth()
                                )

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                                ) {
                                    NullVpTextField(
                                        value = serverAddress,
                                        onValueChange = { serverAddress = it },
                                        label = { Text("Адрес сервера / IP / Домен") },
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

                                if (profile.protocolType == ProtocolType.VLESS || profile.protocolType == ProtocolType.VMESS) {
                                    NullVpTextField(
                                        value = uuid,
                                        onValueChange = { uuid = it },
                                        label = { Text("UUID пользователя") },
                                        singleLine = true,
                                        modifier = Modifier.fillMaxWidth()
                                    )
                                    NullVpTextField(
                                        value = flow,
                                        onValueChange = { flow = it },
                                        label = { Text("Flow (xtls-rprx-vision)") },
                                        placeholder = { Text("xtls-rprx-vision (или оставьте пустым)") },
                                        singleLine = true,
                                        modifier = Modifier.fillMaxWidth()
                                    )
                                    NullVpTextField(
                                        value = encryption,
                                        onValueChange = { encryption = it },
                                        label = { Text("Encryption") },
                                        singleLine = true,
                                        modifier = Modifier.fillMaxWidth()
                                    )
                                } else if (profile.protocolType == ProtocolType.HYSTERIA2 ||
                                    profile.protocolType == ProtocolType.TROJAN ||
                                    profile.protocolType == ProtocolType.SHADOWSOCKS
                                ) {
                                    NullVpTextField(
                                        value = password,
                                        onValueChange = { password = it },
                                        label = { Text("Пароль / Auth Token") },
                                        singleLine = true,
                                        modifier = Modifier.fillMaxWidth()
                                    )
                                }
                            }

                            // 1: Transport Tab
                            1 -> {
                                Text(
                                    text = "Тип сети / Протокол передачи (Network)",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = NeonCyan,
                                    fontWeight = FontWeight.SemiBold
                                )

                                val networks = listOf("tcp", "xhttp", "ws", "grpc", "httpupgrade")
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    networks.forEach { netOption ->
                                        val isSel = network.equals(netOption, ignoreCase = true)
                                        Surface(
                                            shape = RoundedCornerShape(8.dp),
                                            color = if (isSel) NeonCyan.copy(alpha = 0.25f) else DarkSurfaceVariant,
                                            border = androidx.compose.foundation.BorderStroke(
                                                1.dp,
                                                if (isSel) NeonCyan else Color.Transparent
                                            ),
                                            modifier = Modifier
                                                .clickable { network = netOption }
                                                .padding(vertical = 2.dp)
                                        ) {
                                            Text(
                                                text = netOption.uppercase(),
                                                color = if (isSel) NeonCyan else OnDarkSurfaceVariant,
                                                fontSize = 12.sp,
                                                fontWeight = FontWeight.Bold,
                                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                                            )
                                        }
                                    }
                                }

                                NullVpTextField(
                                    value = path,
                                    onValueChange = { path = it },
                                    label = { Text("Path (Путь запроса: /media/videos, /ws, etc.)") },
                                    singleLine = true,
                                    modifier = Modifier.fillMaxWidth()
                                )

                                NullVpTextField(
                                    value = hostHeader,
                                    onValueChange = { hostHeader = it },
                                    label = { Text("Host (Заголовок Host / Authority)") },
                                    singleLine = true,
                                    modifier = Modifier.fillMaxWidth()
                                )

                                if (network == "xhttp" || network == "splithttp") {
                                    NullVpTextField(
                                        value = xhttpMode,
                                        onValueChange = { xhttpMode = it },
                                        label = { Text("Режим xHTTP (auto, download-only, packet-up)") },
                                        singleLine = true,
                                        modifier = Modifier.fillMaxWidth()
                                    )
                                }

                                if (network == "grpc") {
                                    NullVpTextField(
                                        value = serviceName,
                                        onValueChange = { serviceName = it },
                                        label = { Text("gRPC ServiceName") },
                                        singleLine = true,
                                        modifier = Modifier.fillMaxWidth()
                                    )
                                }

                                if (network == "tcp") {
                                    NullVpTextField(
                                        value = headerType,
                                        onValueChange = { headerType = it },
                                        label = { Text("Header Type (none / http)") },
                                        singleLine = true,
                                        modifier = Modifier.fillMaxWidth()
                                    )
                                }
                            }

                            // 2: Security (TLS / Reality) Tab
                            2 -> {
                                Text(
                                    text = "Безопасность потока (Stream Security)",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = NeonCyan,
                                    fontWeight = FontWeight.SemiBold
                                )

                                val secOptions = listOf("tls", "reality", "none")
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    secOptions.forEach { secOption ->
                                        val isSel = security.equals(secOption, ignoreCase = true)
                                        Surface(
                                            shape = RoundedCornerShape(8.dp),
                                            color = if (isSel) NeonCyan.copy(alpha = 0.25f) else DarkSurfaceVariant,
                                            border = androidx.compose.foundation.BorderStroke(
                                                1.dp,
                                                if (isSel) NeonCyan else Color.Transparent
                                            ),
                                            modifier = Modifier
                                                .clickable { security = secOption }
                                                .padding(vertical = 2.dp)
                                        ) {
                                            Text(
                                                text = secOption.uppercase(),
                                                color = if (isSel) NeonCyan else OnDarkSurfaceVariant,
                                                fontSize = 12.sp,
                                                fontWeight = FontWeight.Bold,
                                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                                            )
                                        }
                                    }
                                }

                                NullVpTextField(
                                    value = sni,
                                    onValueChange = { sni = it },
                                    label = { Text("SNI (Server Name Indication)") },
                                    singleLine = true,
                                    modifier = Modifier.fillMaxWidth()
                                )

                                NullVpTextField(
                                    value = alpn,
                                    onValueChange = { alpn = it },
                                    label = { Text("ALPN (через запятую: h2,http/1.1,h3)") },
                                    singleLine = true,
                                    modifier = Modifier.fillMaxWidth()
                                )

                                NullVpTextField(
                                    value = fp,
                                    onValueChange = { fp = it },
                                    label = { Text("Fingerprint (chrome, qq, firefox, safari, etc.)") },
                                    singleLine = true,
                                    modifier = Modifier.fillMaxWidth()
                                )

                                if (security == "reality") {
                                    NullVpTextField(
                                        value = pbk,
                                        onValueChange = { pbk = it },
                                        label = { Text("Reality Public Key (pbk)") },
                                        singleLine = true,
                                        modifier = Modifier.fillMaxWidth()
                                    )

                                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                        NullVpTextField(
                                            value = sid,
                                            onValueChange = { sid = it },
                                            label = { Text("Short ID (sid)") },
                                            singleLine = true,
                                            modifier = Modifier.weight(1f)
                                        )
                                        NullVpTextField(
                                            value = spx,
                                            onValueChange = { spx = it },
                                            label = { Text("SpiderX (spx)") },
                                            singleLine = true,
                                            modifier = Modifier.weight(1f)
                                        )
                                    }
                                }

                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Checkbox(
                                        checked = insecure,
                                        onCheckedChange = { insecure = it },
                                        colors = CheckboxDefaults.colors(checkedColor = NeonCyan)
                                    )
                                    Spacer(Modifier.width(4.dp))
                                    Text("Разрешить небезопасный TLS (Insecure / игнорировать сертификат)", color = OnDarkSurface, fontSize = 13.sp)
                                }
                            }

                            // 3: Protocol Specific Tab
                            3 -> {
                                when (profile.protocolType) {
                                    ProtocolType.VK_TURN -> {
                                        NullVpTextField(
                                            value = vkCallUrl,
                                            onValueChange = { vkCallUrl = it },
                                            label = { Text("VK Call Link (vk.com/call/join/...)") },
                                            singleLine = true,
                                            modifier = Modifier.fillMaxWidth()
                                        )
                                        NullVpTextField(
                                            value = vkPeer,
                                            onValueChange = { vkPeer = it },
                                            label = { Text("Peer Relay (IP:Port)") },
                                            singleLine = true,
                                            modifier = Modifier.fillMaxWidth()
                                        )
                                        NullVpTextField(
                                            value = streamsCount,
                                            onValueChange = { streamsCount = it.filter { ch -> ch.isDigit() } },
                                            label = { Text("Количество потоков (Streams: 1-10)") },
                                            singleLine = true,
                                            showPasteButton = false,
                                            modifier = Modifier.fillMaxWidth()
                                        )
                                        NullVpTextField(
                                            value = wgSecretKey,
                                            onValueChange = { wgSecretKey = it },
                                            label = { Text("WireGuard SecretKey (Приватный ключ)") },
                                            singleLine = true,
                                            modifier = Modifier.fillMaxWidth()
                                        )
                                        NullVpTextField(
                                            value = wgPublicKey,
                                            onValueChange = { wgPublicKey = it },
                                            label = { Text("WireGuard PublicKey (Публичный ключ сервера)") },
                                            singleLine = true,
                                            modifier = Modifier.fillMaxWidth()
                                        )
                                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                            NullVpTextField(
                                                value = wgAddress,
                                                onValueChange = { wgAddress = it },
                                                label = { Text("WG Local IP") },
                                                singleLine = true,
                                                modifier = Modifier.weight(1f)
                                            )
                                            NullVpTextField(
                                                value = wgMtu,
                                                onValueChange = { wgMtu = it.filter { ch -> ch.isDigit() } },
                                                label = { Text("MTU") },
                                                singleLine = true,
                                                modifier = Modifier.width(100.dp)
                                            )
                                        }
                                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                            NullVpTextField(
                                                value = obfProfile,
                                                onValueChange = { obfProfile = it },
                                                label = { Text("Профиль обфускации") },
                                                singleLine = true,
                                                modifier = Modifier.weight(1f)
                                            )
                                            NullVpTextField(
                                                value = obfKey,
                                                onValueChange = { obfKey = it },
                                                label = { Text("Ключ обфускации (obfKey)") },
                                                singleLine = true,
                                                modifier = Modifier.weight(1f)
                                            )
                                        }
                                    }

                                    ProtocolType.HYSTERIA2 -> {
                                        NullVpTextField(
                                            value = obfsPassword,
                                            onValueChange = { obfsPassword = it },
                                            label = { Text("Salamander Obfs Password (опционально)") },
                                            singleLine = true,
                                            modifier = Modifier.fillMaxWidth()
                                        )
                                    }

                                    ProtocolType.SHADOWSOCKS -> {
                                        NullVpTextField(
                                            value = ssMethod,
                                            onValueChange = { ssMethod = it },
                                            label = { Text("Метод шифрования (aes-128-gcm, chacha20-poly1305, etc.)") },
                                            singleLine = true,
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

                                    else -> {
                                        Text(
                                            text = "Для данного протокола нет специфических параметров",
                                            color = OnDarkSurfaceVariant,
                                            fontSize = 13.sp
                                        )
                                    }
                                }
                            }

                            // 4: Raw JSON Tab
                            4 -> {
                                Text(
                                    text = "Прямой просмотр и редактирование конфигурационного JSON:",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = OnDarkSurfaceVariant
                                )
                                NullVpTextField(
                                    value = rawConfig,
                                    onValueChange = { rawConfig = it },
                                    placeholder = { Text("Raw JSON config...") },
                                    singleLine = false,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(260.dp),
                                    textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace)
                                )
                            }
                        }
                    }
                }

                if (errorMessage != null) {
                    Text(
                        text = errorMessage!!,
                        color = CyberRed,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(vertical = 4.dp)
                    )
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

                                val updatedRawConfig = if (activeTab == 4) {
                                    rawConfig.trim()
                                } else {
                                    // Build comprehensive JSON with all updated fields
                                    val baseObj = runCatching {
                                        Json.parseToJsonElement(profile.protocolRawConfig).jsonObject
                                    }.getOrNull() ?: JsonObject(emptyMap())

                                    val mutableMap = baseObj.toMutableMap()

                                    mutableMap["name"] = JsonPrimitive(name.trim())
                                    mutableMap["host"] = JsonPrimitive(serverAddress.trim())
                                    mutableMap["port"] = JsonPrimitive(portNum)
                                    mutableMap["sni"] = JsonPrimitive(sni.trim())
                                    mutableMap["security"] = JsonPrimitive(security)
                                    mutableMap["type"] = JsonPrimitive(network)
                                    mutableMap["network"] = JsonPrimitive(network)
                                    mutableMap["path"] = JsonPrimitive(path.trim())
                                    mutableMap["insecure"] = JsonPrimitive(insecure)

                                    if (uuid.isNotBlank()) mutableMap["uuid"] = JsonPrimitive(uuid.trim())
                                    if (password.isNotBlank()) mutableMap["password"] = JsonPrimitive(password.trim())
                                    if (flow.isNotBlank()) mutableMap["flow"] = JsonPrimitive(flow.trim())
                                    if (alpn.isNotBlank()) mutableMap["alpn"] = JsonPrimitive(alpn.trim())
                                    if (fp.isNotBlank()) mutableMap["fp"] = JsonPrimitive(fp.trim())
                                    if (pbk.isNotBlank()) mutableMap["pbk"] = JsonPrimitive(pbk.trim())
                                    if (sid.isNotBlank()) mutableMap["sid"] = JsonPrimitive(sid.trim())
                                    if (spx.isNotBlank()) mutableMap["spx"] = JsonPrimitive(spx.trim())
                                    if (hostHeader.isNotBlank()) mutableMap["host"] = JsonPrimitive(hostHeader.trim())
                                    if (xhttpMode.isNotBlank()) mutableMap["mode"] = JsonPrimitive(xhttpMode.trim())
                                    if (serviceName.isNotBlank()) mutableMap["serviceName"] = JsonPrimitive(serviceName.trim())
                                    if (headerType.isNotBlank()) mutableMap["headerType"] = JsonPrimitive(headerType.trim())
                                    if (obfsPassword.isNotBlank()) mutableMap["obfsPassword"] = JsonPrimitive(obfsPassword.trim())
                                    if (ssMethod.isNotBlank()) mutableMap["method"] = JsonPrimitive(ssMethod.trim())

                                    if (profile.protocolType == ProtocolType.VK_TURN) {
                                        mutableMap["peer"] = JsonPrimitive(vkPeer.trim())
                                        mutableMap["vk"] = JsonPrimitive(vkCallUrl.trim())
                                        mutableMap["vkLink"] = JsonPrimitive(vkCallUrl.trim())
                                        mutableMap["n"] = JsonPrimitive(streamsCount.toIntOrNull() ?: 10)
                                        mutableMap["streams"] = JsonPrimitive(streamsCount.toIntOrNull() ?: 10)
                                        if (wgSecretKey.isNotBlank()) mutableMap["secretKey"] = JsonPrimitive(wgSecretKey.trim())
                                        if (wgPublicKey.isNotBlank()) mutableMap["publicKey"] = JsonPrimitive(wgPublicKey.trim())
                                        if (wgAddress.isNotBlank()) mutableMap["address"] = JsonPrimitive(wgAddress.trim())
                                        if (wgMtu.isNotBlank()) mutableMap["mtu"] = JsonPrimitive(wgMtu.toIntOrNull() ?: 1280)
                                        if (obfProfile.isNotBlank()) mutableMap["obfProfile"] = JsonPrimitive(obfProfile.trim())
                                        if (obfKey.isNotBlank()) mutableMap["obfKey"] = JsonPrimitive(obfKey.trim())
                                    }

                                    JsonObject(mutableMap).toString()
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
