package nullvp.core

import kotlinx.serialization.json.*
import nullvp.domain.model.AppSettings
import nullvp.domain.model.Profile
import nullvp.domain.model.ProtocolType
import java.net.URLDecoder

object RealXrayConfigBuilder {
    private val json = Json { prettyPrint = true }

    fun buildJson(profile: Profile, settings: AppSettings, localRelayPort: Int = 10815): String {
        val root = buildJsonObject {
            // 1. Log configuration
            putJsonObject("log") {
                put("loglevel", "warning")
            }

            // 2. Stats and API configuration for real network stats polling
            putJsonObject("stats") {}

            putJsonObject("api") {
                put("tag", "api")
                putJsonArray("services") {
                    add("StatsService")
                }
            }

            putJsonObject("policy") {
                putJsonObject("system") {
                    put("statsInboundUplink", true)
                    put("statsInboundDownlink", true)
                    put("statsOutboundUplink", true)
                    put("statsOutboundDownlink", true)
                }
            }

            // 3. Inbounds (Mixed SOCKS5/HTTP on socksPort + HTTP on httpPort + API on 10085)
            putJsonArray("inbounds") {
                // SOCKS5 / Mixed Inbound
                addJsonObject {
                    put("tag", "socks-in")
                    put("port", settings.socksPort)
                    put("listen", if (settings.allowLan) "0.0.0.0" else "127.0.0.1")
                    put("protocol", "mixed")
                    putJsonObject("settings") {
                        if (settings.proxyAuthEnabled && settings.proxyUsername.isNotBlank()) {
                            put("auth", "password")
                            putJsonArray("accounts") {
                                addJsonObject {
                                    put("user", settings.proxyUsername)
                                    put("pass", settings.proxyPassword)
                                }
                            }
                        } else {
                            put("auth", "noauth")
                        }
                        put("udp", true)
                    }
                    if (settings.sniffingEnabled) {
                        putJsonObject("sniffing") {
                            put("enabled", true)
                            putJsonArray("destOverride") {
                                add("http")
                                add("tls")
                                add("fakedns")
                            }
                            put("routeOnly", true)
                        }
                    }
                }

                // HTTP Inbound
                addJsonObject {
                    put("tag", "http-in")
                    put("port", settings.httpPort)
                    put("listen", if (settings.allowLan) "0.0.0.0" else "127.0.0.1")
                    put("protocol", "mixed")
                    putJsonObject("settings") {
                        if (settings.proxyAuthEnabled && settings.proxyUsername.isNotBlank()) {
                            putJsonArray("accounts") {
                                addJsonObject {
                                    put("user", settings.proxyUsername)
                                    put("pass", settings.proxyPassword)
                                }
                            }
                        }
                    }
                }

                // API Inbound for statsquery
                addJsonObject {
                    put("tag", "api")
                    put("port", 10085)
                    put("listen", "127.0.0.1")
                    put("protocol", "dokodemo-door")
                    putJsonObject("settings") {
                        put("address", "127.0.0.1")
                    }
                }
            }

            // 4. Outbounds (Proxy, Direct, Block)
            putJsonArray("outbounds") {
                add(buildProxyOutbound(profile, settings, localRelayPort))

                // Freedom / Direct outbound
                addJsonObject {
                    put("tag", "direct")
                    put("protocol", "freedom")
                    putJsonObject("settings") {}
                }

                // Blackhole / Block outbound
                addJsonObject {
                    put("tag", "block")
                    put("protocol", "blackhole")
                    putJsonObject("settings") {}
                }
            }

            // 5. DNS configuration
            putJsonObject("dns") {
                putJsonArray("servers") {
                    add(settings.remoteDns)
                    add(settings.directDns)
                    add("localhost")
                }
            }

            // 6. Routing rules
            putJsonObject("routing") {
                put("domainStrategy", "IPIfNonMatch")
                putJsonArray("rules") {
                    // API stats route
                    addJsonObject {
                        put("type", "field")
                        putJsonArray("inboundTag") {
                            add("api")
                        }
                        put("outboundTag", "api")
                    }

                    // Block Ads
                    if (settings.blockAds) {
                        addJsonObject {
                            put("type", "field")
                            put("outboundTag", "block")
                            putJsonArray("domain") {
                                add("geosite:category-ads-all")
                            }
                        }
                    }

                    // Bypass LAN
                    if (settings.bypassLan) {
                        addJsonObject {
                            put("type", "field")
                            put("outboundTag", "direct")
                            putJsonArray("ip") {
                                add("geoip:private")
                            }
                        }
                    }

                    // Bypass RU Services & IPs
                    if (settings.bypassRu) {
                        addJsonObject {
                            put("type", "field")
                            put("outboundTag", "direct")
                            putJsonArray("ip") {
                                add("geoip:ru")
                            }
                        }
                        addJsonObject {
                            put("type", "field")
                            put("outboundTag", "direct")
                            putJsonArray("domain") {
                                add("geosite:category-gov-ru")
                                add("geosite:yandex")
                                add("geosite:mailru")
                                add("geosite:vk")
                                add("domain:sberbank.ru")
                                add("domain:sber.ru")
                                add("domain:tinkoff.ru")
                                add("domain:tbank.ru")
                            }
                        }
                    }

                    // Custom user rules
                    settings.customRules.filter { it.enabled }.forEach { rule ->
                        addJsonObject {
                            put("type", "field")
                            put("outboundTag", rule.outbound)
                            if (rule.domains.isNotEmpty()) {
                                putJsonArray("domain") {
                                    rule.domains.forEach { add(it) }
                                }
                            }
                            if (rule.ips.isNotEmpty()) {
                                putJsonArray("ip") {
                                    rule.ips.forEach { add(it) }
                                }
                            }
                        }
                    }

                    // Default rule -> proxy
                    addJsonObject {
                        put("type", "field")
                        put("outboundTag", "proxy")
                        putJsonArray("network") {
                            add("tcp")
                            add("udp")
                        }
                    }
                }
            }
        }

        return json.encodeToString(JsonObject.serializer(), root)
    }

    private fun extractParams(rawConfig: String): Map<String, String> {
        val params = mutableMapOf<String, String>()
        val rawJson = runCatching {
            Json.parseToJsonElement(rawConfig).jsonObject
        }.getOrNull() ?: return params

        // 1. Parse query string if present
        rawJson["query"]?.jsonPrimitive?.contentOrNull?.let { queryStr ->
            if (queryStr.isNotBlank()) {
                queryStr.split('&').forEach { pair ->
                    val idx = pair.indexOf('=')
                    if (idx > 0) {
                        val key = pair.substring(0, idx)
                        val rawVal = pair.substring(idx + 1)
                        val value = runCatching { URLDecoder.decode(rawVal, "UTF-8") }.getOrDefault(rawVal)
                        params[key] = value
                    }
                }
            }
        }

        // 2. Overlay direct JSON fields
        rawJson.forEach { (k, v) ->
            if (k != "query") {
                if (v is JsonPrimitive && v.contentOrNull != null) {
                    params[k] = v.content
                } else {
                    params[k] = v.toString()
                }
            }
        }

        return params
    }

    private fun buildProxyOutbound(profile: Profile, settings: AppSettings, localRelayPort: Int = 10815): JsonObject {
        val params = extractParams(profile.protocolRawConfig)

        return buildJsonObject {
            put("tag", "proxy")

            when (profile.protocolType) {
                ProtocolType.VLESS -> {
                    put("protocol", "vless")
                    putJsonObject("settings") {
                        putJsonArray("vnext") {
                            addJsonObject {
                                put("address", profile.serverAddress)
                                put("port", profile.serverPort)
                                putJsonArray("users") {
                                    addJsonObject {
                                        put("id", params["uuid"] ?: params["id"] ?: "11111111-2222-3333-4444-555555555555")
                                        put("encryption", params["encryption"] ?: "none")
                                        val flowVal = params["flow"]
                                        if (!flowVal.isNullOrBlank()) {
                                            put("flow", flowVal)
                                        }
                                    }
                                }
                            }
                        }
                    }

                    // Stream settings
                    putJsonObject("streamSettings") {
                        val pbk = params["pbk"] ?: params["publicKey"] ?: ""
                        val explicitSecurity = params["security"]?.lowercase()
                        val security = when {
                            explicitSecurity == "tls" -> "tls"
                            explicitSecurity == "reality" && pbk.isNotBlank() -> "reality"
                            pbk.isNotBlank() -> "reality"
                            explicitSecurity == "none" -> "none"
                            else -> if (profile.serverPort == 443 || profile.serverPort == 8443) "tls" else "none"
                        }

                        put("security", security)
                        val net = params["type"]?.lowercase()
                            ?: params["network"]?.lowercase()
                            ?: params["net"]?.lowercase()
                            ?: "tcp"
                        put("network", net)

                        val sni = params["sni"] ?: params["host"] ?: profile.serverAddress
                        val fp = params["fp"] ?: params["fingerprint"] ?: "chrome"
                        val alpnList = params["alpn"]?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() }
                            ?: listOf("h2", "http/1.1")

                        if (security == "reality") {
                            putJsonObject("realitySettings") {
                                put("show", false)
                                put("fingerprint", fp)
                                put("serverName", sni)
                                put("publicKey", pbk)
                                put("shortId", params["sid"] ?: params["shortId"] ?: "")
                                put("spiderX", params["spx"] ?: params["spiderX"] ?: "/")
                            }
                        } else if (security == "tls") {
                            putJsonObject("tlsSettings") {
                                put("allowInsecure", params["insecure"] == "1" || params["insecure"].equals("true", true))
                                put("serverName", sni)
                                put("fingerprint", fp)
                                putJsonArray("alpn") {
                                    alpnList.forEach { add(it) }
                                }
                            }
                        }

                        // Transport settings
                        val path = params["path"] ?: "/"
                        val host = params["host"] ?: sni

                        when (net) {
                            "xhttp", "splithttp" -> {
                                putJsonObject("xhttpSettings") {
                                    put("mode", params["mode"] ?: "auto")
                                    put("path", path)
                                    put("host", host)
                                    params["extra"]?.let { extraStr ->
                                        runCatching {
                                            Json.parseToJsonElement(extraStr).jsonObject
                                        }.getOrNull()?.let { put("extra", it) }
                                    }
                                }
                            }
                            "ws" -> {
                                putJsonObject("wsSettings") {
                                    put("path", path)
                                    putJsonObject("headers") {
                                        put("Host", host)
                                    }
                                }
                            }
                            "grpc" -> {
                                putJsonObject("grpcSettings") {
                                    put("serviceName", params["serviceName"] ?: path.trimStart('/'))
                                    put("authority", host)
                                    put("multiMode", true)
                                }
                            }
                            "httpupgrade" -> {
                                putJsonObject("httpupgradeSettings") {
                                    put("path", path)
                                    put("host", host)
                                }
                            }
                            "tcp" -> {
                                if (params["headerType"] == "http") {
                                    putJsonObject("tcpSettings") {
                                        putJsonObject("header") {
                                            put("type", "http")
                                            putJsonObject("request") {
                                                put("version", "1.1")
                                                put("method", "GET")
                                                putJsonArray("path") { add(path) }
                                                putJsonObject("headers") {
                                                    putJsonArray("Host") { add(host) }
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                ProtocolType.VMESS -> {
                    put("protocol", "vmess")
                    putJsonObject("settings") {
                        putJsonArray("vnext") {
                            addJsonObject {
                                put("address", profile.serverAddress)
                                put("port", profile.serverPort)
                                putJsonArray("users") {
                                    addJsonObject {
                                        put("id", params["id"] ?: params["uuid"] ?: "11111111-2222-3333-4444-555555555555")
                                        put("alterId", params["alterId"]?.toIntOrNull() ?: 0)
                                        put("security", params["security"] ?: "auto")
                                    }
                                }
                            }
                        }
                    }
                    val net = params["type"] ?: params["network"] ?: "tcp"
                    putJsonObject("streamSettings") {
                        put("network", net)
                        if (params["security"] == "tls") {
                            put("security", "tls")
                            putJsonObject("tlsSettings") {
                                put("serverName", params["sni"] ?: params["host"] ?: profile.serverAddress)
                            }
                        }
                    }
                }

                ProtocolType.TROJAN -> {
                    put("protocol", "trojan")
                    putJsonObject("settings") {
                        putJsonArray("servers") {
                            addJsonObject {
                                put("address", profile.serverAddress)
                                put("port", profile.serverPort)
                                put("password", params["password"] ?: "")
                            }
                        }
                    }
                    putJsonObject("streamSettings") {
                        put("security", "tls")
                        putJsonObject("tlsSettings") {
                            put("serverName", params["sni"] ?: profile.serverAddress)
                            if (params["insecure"] == "1" || params["insecure"].equals("true", true)) {
                                put("allowInsecure", true)
                            }
                        }
                    }
                }

                ProtocolType.HYSTERIA2 -> {
                    val hyAuth = params["auth"] ?: params["password"] ?: ""
                    put("protocol", "hysteria")
                    putJsonObject("settings") {
                        put("version", 2)
                        put("address", profile.serverAddress)
                        put("port", profile.serverPort)
                        put("auth", hyAuth)
                        put("up", "0")
                        put("down", "0")
                    }
                    putJsonObject("streamSettings") {
                        put("network", "hysteria")
                        put("security", "tls")
                        putJsonObject("tlsSettings") {
                            put("serverName", params["sni"] ?: profile.serverAddress)
                            putJsonArray("alpn") {
                                add("h3")
                            }
                            if (params["insecure"] == "1" || params["insecure"].equals("true", true)) {
                                put("allowInsecure", true)
                            }
                        }
                        putJsonObject("hysteriaSettings") {
                            put("version", 2)
                            put("auth", hyAuth)
                        }
                        val obfsPass = params["obfsPassword"] ?: params["obfs-password"] ?: ""
                        if (obfsPass.isNotBlank()) {
                            putJsonObject("finalmask") {
                                putJsonArray("udp") {
                                    addJsonObject {
                                        put("type", "salamander")
                                        putJsonObject("settings") {
                                            put("password", obfsPass)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                ProtocolType.SHADOWSOCKS -> {
                    put("protocol", "shadowsocks")
                    putJsonObject("settings") {
                        putJsonArray("servers") {
                            addJsonObject {
                                put("address", profile.serverAddress)
                                put("port", profile.serverPort)
                                put("method", params["method"] ?: "aes-128-gcm")
                                put("password", params["password"] ?: "")
                            }
                        }
                    }
                }

                ProtocolType.VK_TURN -> {
                    val wgStr = params["wgConf"] ?: params["wg"]
                    val targetProto = params["targetProtocol"] ?: params["protocol"]

                    val isWireguard = !wgStr.isNullOrBlank() ||
                            targetProto.equals("wireguard", true) ||
                            params.containsKey("secretKey")

                    if (isWireguard) {
                        var privKey = params["secretKey"] ?: ""
                        var pubKey = params["publicKey"] ?: ""
                        var address = params["address"] ?: "10.0.0.2/32"

                        if (privKey.isBlank() && !wgStr.isNullOrBlank()) {
                            for (line in wgStr.lines()) {
                                val trimmed = line.trim()
                                val lower = trimmed.lowercase()
                                if (lower.startsWith("privatekey")) {
                                    privKey = trimmed.substringAfter("=").trim()
                                } else if (lower.startsWith("publickey")) {
                                    pubKey = trimmed.substringAfter("=").trim()
                                } else if (lower.startsWith("address")) {
                                    address = trimmed.substringAfter("=").trim()
                                }
                            }
                        }

                        put("protocol", "wireguard")
                        putJsonObject("settings") {
                            put("secretKey", privKey)
                            putJsonArray("address") {
                                add(address)
                            }
                            putJsonArray("peers") {
                                addJsonObject {
                                    put("publicKey", pubKey)
                                    put("endpoint", "127.0.0.1:$localRelayPort")
                                }
                            }
                        }
                    } else {
                        put("protocol", "socks")
                        putJsonObject("settings") {
                            putJsonArray("servers") {
                                addJsonObject {
                                    put("address", "127.0.0.1")
                                    put("port", localRelayPort)
                                }
                            }
                        }
                    }
                }

                ProtocolType.MASTER_DNS -> {
                    put("protocol", "socks")
                    putJsonObject("settings") {
                        putJsonArray("servers") {
                            addJsonObject {
                                put("address", "127.0.0.1")
                                put("port", localRelayPort)
                            }
                        }
                    }
                }

                else -> {
                    // Upstream relay via local port
                    put("protocol", "socks")
                    putJsonObject("settings") {
                        putJsonArray("servers") {
                            addJsonObject {
                                put("address", "127.0.0.1")
                                put("port", localRelayPort)
                            }
                        }
                    }
                }
            }

            // MUX Settings
            if (settings.muxEnabled && profile.protocolType != ProtocolType.HYSTERIA2 && profile.protocolType != ProtocolType.MASTER_DNS && profile.protocolType != ProtocolType.VK_TURN) {
                putJsonObject("mux") {
                    put("enabled", true)
                    put("concurrency", settings.muxConcurrency)
                }
            }
        }
    }
}
