package nullvp.data.protocol

import io.ktor.http.URLBuilder
import io.ktor.http.Url
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import nullvp.domain.model.Profile
import nullvp.domain.model.ProtocolType
import nullvp.domain.protocol.ProtocolCapabilities
import nullvp.domain.protocol.ProtocolHandler
import nullvp.domain.protocol.ValidationResult
import java.net.URLDecoder
import java.net.URLEncoder

class VlessProtocolHandler : ProtocolHandler {
    override val protocolType: ProtocolType = ProtocolType.VLESS
    override val scheme: String = "vless"
    override val displayName: String = "VLESS (Reality / TLS)"
    override val capabilities: ProtocolCapabilities = ProtocolCapabilities(
        supportsUdp = true,
        requiresTun = false,
        supportsProxyMode = true,
        supportsCaptcha = false
    )

    override fun parseUri(uri: String): Profile? {
        val trimmed = uri.trim()
        if (!trimmed.startsWith("vless://", ignoreCase = true)) return null

        return runCatching {
            val hashIdx = trimmed.indexOf('#')
            val urlPart = if (hashIdx != -1) trimmed.substring(0, hashIdx) else trimmed
            val rawName = if (hashIdx != -1) trimmed.substring(hashIdx + 1) else "VLESS Node"
            val name = URLDecoder.decode(rawName, "UTF-8")

            // Parse vless://uuid@host:port?params
            val afterScheme = urlPart.substring(8)
            val atIdx = afterScheme.indexOf('@')
            if (atIdx == -1) return null

            val uuid = afterScheme.substring(0, atIdx)
            val hostPortAndQuery = afterScheme.substring(atIdx + 1)

            val qIdx = hostPortAndQuery.indexOf('?')
            val hostPort = if (qIdx != -1) hostPortAndQuery.substring(0, qIdx) else hostPortAndQuery
            val queryStr = if (qIdx != -1) hostPortAndQuery.substring(qIdx + 1) else ""

            val colonIdx = hostPort.lastIndexOf(':')
            if (colonIdx == -1) return null

            val host = hostPort.substring(0, colonIdx)
            val port = hostPort.substring(colonIdx + 1).toIntOrNull() ?: return null

            // Build minimal JSON config representing this VLESS outbound
            val configJson = buildJsonObject {
                put("uuid", uuid)
                put("host", host)
                put("port", port)
                put("query", queryStr)
                put("name", name)
            }.toString()

            val id = "vless_${host.replace('.', '_')}_$port"

            Profile(
                id = id,
                name = name,
                serverAddress = host,
                serverPort = port,
                protocolType = ProtocolType.VLESS,
                protocolRawConfig = configJson
            )
        }.getOrNull()
    }

    override fun formatUri(profile: Profile): String? {
        // Can format back to standard vless link
        return null // Provided when editing
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
}

