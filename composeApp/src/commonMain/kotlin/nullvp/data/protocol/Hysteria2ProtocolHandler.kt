package nullvp.data.protocol

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import nullvp.domain.model.Profile
import nullvp.domain.model.ProtocolType
import nullvp.domain.protocol.ProtocolCapabilities
import nullvp.domain.protocol.ProtocolHandler
import nullvp.domain.protocol.ValidationResult
import java.net.URLDecoder
import java.net.URLEncoder

class Hysteria2ProtocolHandler : ProtocolHandler {
    override val protocolType: ProtocolType = ProtocolType.HYSTERIA2
    override val scheme: String = "hy2"
    override val alternativeSchemes: List<String> = listOf("hysteria2")
    override val displayName: String = "Hysteria 2 (QUIC / UDP)"
    override val capabilities: ProtocolCapabilities = ProtocolCapabilities(
        supportsUdp = true,
        requiresTun = false,
        supportsProxyMode = true,
        supportsCaptcha = false
    )

    override fun parseUri(uri: String): Profile? {
        val trimmed = uri.trim()
        val isHy2 = trimmed.startsWith("hy2://", ignoreCase = true)
        val isHysteria2 = trimmed.startsWith("hysteria2://", ignoreCase = true)
        if (!isHy2 && !isHysteria2) return null

        val prefixLen = if (isHy2) 6 else 12
        return runCatching {
            val rest = trimmed.substring(prefixLen)
            val hashIdx = rest.indexOf('#')
            val urlPart = if (hashIdx != -1) rest.substring(0, hashIdx) else rest
            val rawName = if (hashIdx != -1) rest.substring(hashIdx + 1) else "Hysteria2 Node"
            val name = runCatching { URLDecoder.decode(rawName, "UTF-8") }.getOrDefault(rawName)

            // Parse password@host:port/?query
            val atIdx = urlPart.indexOf('@')
            val auth = if (atIdx != -1) urlPart.substring(0, atIdx) else ""
            val hostPortAndQuery = if (atIdx != -1) urlPart.substring(atIdx + 1) else urlPart

            val qIdx = hostPortAndQuery.indexOf('?')
            val hostPort = if (qIdx != -1) hostPortAndQuery.substring(0, qIdx) else hostPortAndQuery
            val queryStr = if (qIdx != -1) hostPortAndQuery.substring(qIdx + 1) else ""

            val cleanHostPort = hostPort.trimEnd('/')
            val colonIdx = cleanHostPort.lastIndexOf(':')
            val host: String
            val port: Int
            if (colonIdx != -1) {
                host = cleanHostPort.substring(0, colonIdx)
                port = cleanHostPort.substring(colonIdx + 1).toIntOrNull() ?: 443
            } else {
                host = cleanHostPort
                port = 443
            }

            // Extract query params (sni, insecure, obfs, obfs-password)
            val params = parseQueryParams(queryStr)
            val sni = params["sni"] ?: host
            val insecure = params["insecure"] == "1"
            val obfs = params["obfs"] ?: ""
            val obfsPassword = params["obfs-password"] ?: ""
            val pinSha256 = params["pinSHA256"] ?: ""

            val configJson = buildJsonObject {
                put("server", "$host:$port")
                put("auth", auth)
                put("sni", sni)
                put("insecure", insecure)
                put("obfs", obfs)
                put("obfsPassword", obfsPassword)
                put("pinSha256", pinSha256)
                put("name", name)
            }.toString()

            val id = "hy2_${host.replace('.', '_')}_$port"

            Profile(
                id = id,
                name = name,
                serverAddress = host,
                serverPort = port,
                protocolType = ProtocolType.HYSTERIA2,
                protocolRawConfig = configJson
            )
        }.getOrNull()
    }

    override fun formatUri(profile: Profile): String? {
        // Can format back to hy2://...
        return null
    }

    override fun validate(profile: Profile): ValidationResult {
        if (profile.serverAddress.isBlank() || profile.serverPort <= 0) {
            return ValidationResult.Invalid("Invalid server host or port")
        }
        return ValidationResult.Valid
    }

    override fun toCoreConfigJson(profile: Profile): String {
        return profile.protocolRawConfig
    }

    private fun parseQueryParams(query: String): Map<String, String> {
        val result = mutableMapOf<String, String>()
        if (query.isBlank()) return result
        val pairs = query.split('&')
        for (pair in pairs) {
            val idx = pair.indexOf('=')
            if (idx > 0) {
                val key = pair.substring(0, idx)
                val value = pair.substring(idx + 1)
                result[key] = runCatching { URLDecoder.decode(value, "UTF-8") }.getOrDefault(value)
            }
        }
        return result
    }
}

