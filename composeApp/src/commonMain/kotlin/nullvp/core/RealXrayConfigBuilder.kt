package nullvp.core

import kotlinx.serialization.json.*
import nullvp.domain.model.AppSettings
import nullvp.domain.model.Profile
import nullvp.domain.model.ProtocolType

object RealXrayConfigBuilder {
    private val json = Json { prettyPrint = true }

    fun buildJson(profile: Profile, settings: AppSettings, localRelayPort: Int = 10815): String {
        val root = buildJsonObject {
            // 1. Log configuration
            putJsonObject("log") {
                put("loglevel", "warning")
            }

            // 2. Inbounds (Mixed SOCKS5/HTTP on 10808 + HTTP on 10809)
            putJsonArray("inbounds") {
                addJsonObject {
                    put("tag", "socks-in")
                    put("port", settings.socksPort)
                    put("listen", if (settings.allowLan) "0.0.0.0" else "127.0.0.1")
                    put("protocol", "mixed")
                    putJsonObject("settings") {
                        put("auth", "noauth")
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

                addJsonObject {
                    put("tag", "http-in")
                    put("port", settings.httpPort)
                    put("listen", if (settings.allowLan) "0.0.0.0" else "127.0.0.1")
                    put("protocol", "mixed")
                    putJsonObject("settings") {}
                }
            }

            // 3. Outbounds (Proxy, Direct, Block)
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

            // 4. DNS configuration
            putJsonObject("dns") {
                putJsonArray("servers") {
                    add(settings.remoteDns)
                    add(settings.directDns)
                    add("localhost")
                }
            }

            // 5. Routing rules
            putJsonObject("routing") {
                put("domainStrategy", "IPIfNonMatch")
                putJsonArray("rules") {
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

    private fun buildProxyOutbound(profile: Profile, settings: AppSettings, localRelayPort: Int = 10815): JsonObject {
        // Try parsing protocol raw config as JSON if available
        val rawJson: JsonObject? = runCatching {
            Json.parseToJsonElement(profile.protocolRawConfig).jsonObject
        }.getOrNull()

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
                                        put("id", rawJson?.get("uuid")?.jsonPrimitive?.content ?: "11111111-2222-3333-4444-555555555555")
                                        put("encryption", "none")
                                        rawJson?.get("flow")?.jsonPrimitive?.content?.let { put("flow", it) }
                                    }
                                }
                            }
                        }
                    }

                    // Stream settings
                    putJsonObject("streamSettings") {
                        val security = rawJson?.get("security")?.jsonPrimitive?.content ?: "reality"
                        put("security", security)
                        val net = rawJson?.get("type")?.jsonPrimitive?.content ?: "tcp"
                        put("network", net)

                        if (security == "reality") {
                            putJsonObject("realitySettings") {
                                put("show", false)
                                put("fingerprint", rawJson?.get("fp")?.jsonPrimitive?.content ?: "chrome")
                                put("serverName", rawJson?.get("sni")?.jsonPrimitive?.content ?: profile.serverAddress)
                                put("publicKey", rawJson?.get("pbk")?.jsonPrimitive?.content ?: "")
                                put("shortId", rawJson?.get("sid")?.jsonPrimitive?.content ?: "")
                                put("spiderX", rawJson?.get("spx")?.jsonPrimitive?.content ?: "/")
                            }
                        } else if (security == "tls") {
                            putJsonObject("tlsSettings") {
                                put("allowInsecure", false)
                                put("serverName", rawJson?.get("sni")?.jsonPrimitive?.content ?: profile.serverAddress)
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
                                        put("id", rawJson?.get("id")?.jsonPrimitive?.content ?: "11111111-2222-3333-4444-555555555555")
                                        put("alterId", 0)
                                        put("security", "auto")
                                    }
                                }
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
                                put("password", rawJson?.get("password")?.jsonPrimitive?.content ?: "")
                            }
                        }
                    }
                    putJsonObject("streamSettings") {
                        put("security", "tls")
                        putJsonObject("tlsSettings") {
                            put("serverName", rawJson?.get("sni")?.jsonPrimitive?.content ?: profile.serverAddress)
                        }
                    }
                }

                ProtocolType.HYSTERIA2 -> {
                    put("protocol", "hysteria")
                    putJsonObject("settings") {
                        put("version", 2)
                        put("address", profile.serverAddress)
                        put("port", profile.serverPort)
                        put("auth", rawJson?.get("auth")?.jsonPrimitive?.content ?: "")
                        put("up", "0")
                        put("down", "0")
                    }
                    putJsonObject("streamSettings") {
                        put("network", "hysteria")
                        put("security", "tls")
                        putJsonObject("tlsSettings") {
                            put("serverName", rawJson?.get("sni")?.jsonPrimitive?.content ?: profile.serverAddress)
                            putJsonArray("alpn") {
                                add("h3")
                            }
                            if (rawJson?.get("insecure")?.jsonPrimitive?.booleanOrNull == true) {
                                put("allowInsecure", true)
                            }
                        }
                        val obfsPass = rawJson?.get("obfsPassword")?.jsonPrimitive?.content ?: ""
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
                                put("method", rawJson?.get("method")?.jsonPrimitive?.content ?: "aes-128-gcm")
                                put("password", rawJson?.get("password")?.jsonPrimitive?.content ?: "")
                            }
                        }
                    }
                }

                ProtocolType.VK_TURN -> {
                    val wgStr = rawJson?.get("wgConf")?.jsonPrimitive?.contentOrNull
                        ?: rawJson?.get("wg")?.jsonPrimitive?.contentOrNull
                    val targetProto = rawJson?.get("targetProtocol")?.jsonPrimitive?.contentOrNull
                        ?: rawJson?.get("protocol")?.jsonPrimitive?.contentOrNull

                    val isWireguard = !wgStr.isNullOrBlank() || targetProto.equals("wireguard", true) || rawJson?.containsKey("secretKey") == true

                    if (isWireguard) {
                        var privKey = rawJson?.get("secretKey")?.jsonPrimitive?.contentOrNull ?: ""
                        var pubKey = rawJson?.get("publicKey")?.jsonPrimitive?.contentOrNull ?: ""
                        var address = rawJson?.get("address")?.jsonPrimitive?.contentOrNull ?: "10.0.0.2/32"

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
                    // For upstream relay via local port
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
            if (settings.muxEnabled && profile.protocolType != ProtocolType.HYSTERIA2) {
                putJsonObject("mux") {
                    put("enabled", true)
                    put("concurrency", settings.muxConcurrency)
                }
            }
        }
    }
}

