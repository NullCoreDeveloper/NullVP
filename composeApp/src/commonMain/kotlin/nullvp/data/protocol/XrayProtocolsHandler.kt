package nullvp.data.protocol

import io.ktor.util.decodeBase64String
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import nullvp.domain.model.Profile
import nullvp.domain.model.ProtocolType
import nullvp.domain.protocol.ProtocolCapabilities
import nullvp.domain.protocol.ProtocolHandler
import nullvp.domain.protocol.ValidationResult
import java.net.URLDecoder

/**
 * Handler for VMess links (vmess://[base64-json])
 */
class VmessProtocolHandler : ProtocolHandler {
    override val protocolType: ProtocolType = ProtocolType.VMESS
    override val scheme: String = "vmess"
    override val displayName: String = "VMess"
    override val capabilities: ProtocolCapabilities = ProtocolCapabilities(
        supportsUdp = true,
        requiresTun = false,
        supportsProxyMode = true,
        supportsCaptcha = false
    )

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    override fun parseUri(uri: String): Profile? {
        val trimmed = uri.trim()
        if (!trimmed.startsWith("vmess://", ignoreCase = true)) return null

        return runCatching {
            val b64 = trimmed.substring(8)
            val jsonStr = b64.decodeBase64String()
            val obj = json.parseToJsonElement(jsonStr).jsonObject

            val name = obj["ps"]?.jsonPrimitive?.content ?: "VMess Node"
            val host = obj["add"]?.jsonPrimitive?.content ?: return null
            val port = obj["port"]?.jsonPrimitive?.intOrNull ?: return null
            val id = "vmess_${host.replace('.', '_')}_$port"

            Profile(
                id = id,
                name = name,
                serverAddress = host,
                serverPort = port,
                protocolType = ProtocolType.VMESS,
                protocolRawConfig = jsonStr
            )
        }.getOrNull()
    }

    override fun formatUri(profile: Profile): String? = null

    override fun validate(profile: Profile): ValidationResult {
        if (profile.serverAddress.isBlank() || profile.serverPort <= 0) {
            return ValidationResult.Invalid("Invalid server host or port")
        }
        return ValidationResult.Valid
    }

    override fun toCoreConfigJson(profile: Profile): String = profile.protocolRawConfig
}

/**
 * Handler for Trojan links (trojan://password@host:port?params#remark)
 */
class TrojanProtocolHandler : ProtocolHandler {
    override val protocolType: ProtocolType = ProtocolType.TROJAN
    override val scheme: String = "trojan"
    override val displayName: String = "Trojan"
    override val capabilities: ProtocolCapabilities = ProtocolCapabilities(
        supportsUdp = true,
        requiresTun = false,
        supportsProxyMode = true,
        supportsCaptcha = false
    )

    override fun parseUri(uri: String): Profile? {
        val trimmed = uri.trim()
        if (!trimmed.startsWith("trojan://", ignoreCase = true)) return null

        return runCatching {
            val rest = trimmed.substring(9)
            val hashIdx = rest.indexOf('#')
            val urlPart = if (hashIdx != -1) rest.substring(0, hashIdx) else rest
            val rawName = if (hashIdx != -1) rest.substring(hashIdx + 1) else "Trojan Node"
            val name = runCatching { URLDecoder.decode(rawName, "UTF-8") }.getOrDefault(rawName)

            val atIdx = urlPart.indexOf('@')
            val password = if (atIdx != -1) urlPart.substring(0, atIdx) else ""
            val hostPortQuery = if (atIdx != -1) urlPart.substring(atIdx + 1) else urlPart

            val qIdx = hostPortQuery.indexOf('?')
            val hostPort = if (qIdx != -1) hostPortQuery.substring(0, qIdx) else hostPortQuery
            val queryStr = if (qIdx != -1) hostPortQuery.substring(qIdx + 1) else ""

            val colonIdx = hostPort.lastIndexOf(':')
            val host = if (colonIdx != -1) hostPort.substring(0, colonIdx) else hostPort
            val port = if (colonIdx != -1) hostPort.substring(colonIdx + 1).toIntOrNull() ?: 443 else 443

            val configJson = buildJsonObject {
                put("password", password)
                put("host", host)
                put("port", port)
                put("query", queryStr)
                put("name", name)
            }.toString()

            val id = "trojan_${host.replace('.', '_')}_$port"

            Profile(
                id = id,
                name = name,
                serverAddress = host,
                serverPort = port,
                protocolType = ProtocolType.TROJAN,
                protocolRawConfig = configJson
            )
        }.getOrNull()
    }

    override fun formatUri(profile: Profile): String? = null

    override fun validate(profile: Profile): ValidationResult {
        if (profile.serverAddress.isBlank() || profile.serverPort <= 0) {
            return ValidationResult.Invalid("Invalid server host or port")
        }
        return ValidationResult.Valid
    }

    override fun toCoreConfigJson(profile: Profile): String = profile.protocolRawConfig
}

/**
 * Handler for Shadowsocks links (ss://...)
 */
class ShadowsocksProtocolHandler : ProtocolHandler {
    override val protocolType: ProtocolType = ProtocolType.SHADOWSOCKS
    override val scheme: String = "ss"
    override val displayName: String = "Shadowsocks"
    override val capabilities: ProtocolCapabilities = ProtocolCapabilities(
        supportsUdp = true,
        requiresTun = false,
        supportsProxyMode = true,
        supportsCaptcha = false
    )

    override fun parseUri(uri: String): Profile? {
        val trimmed = uri.trim()
        if (!trimmed.startsWith("ss://", ignoreCase = true)) return null

        return runCatching {
            val rest = trimmed.substring(5)
            val hashIdx = rest.indexOf('#')
            val rawPayload = if (hashIdx != -1) rest.substring(0, hashIdx) else rest
            val rawName = if (hashIdx != -1) rest.substring(hashIdx + 1) else "SS Node"
            val name = runCatching { URLDecoder.decode(rawName, "UTF-8") }.getOrDefault(rawName)

            // Shadowsocks SIP002: ss://base64(method:password@host:port) or ss://base64(method:password)@host:port
            val decodedStr = if (!rawPayload.contains("@")) {
                runCatching { rawPayload.decodeBase64String() }.getOrDefault(rawPayload)
            } else if (rawPayload.indexOf('@') > 0 && !rawPayload.substring(0, rawPayload.indexOf('@')).contains(':')) {
                val atIdx = rawPayload.indexOf('@')
                val userPass = runCatching { rawPayload.substring(0, atIdx).decodeBase64String() }.getOrDefault("")
                userPass + "@" + rawPayload.substring(atIdx + 1)
            } else {
                rawPayload
            }

            val atIdx = decodedStr.indexOf('@')
            val methodPass = if (atIdx != -1) decodedStr.substring(0, atIdx) else ""
            val hostPort = if (atIdx != -1) decodedStr.substring(atIdx + 1) else decodedStr

            val colonIdx = hostPort.lastIndexOf(':')
            val host = if (colonIdx != -1) hostPort.substring(0, colonIdx) else hostPort
            val port = if (colonIdx != -1) hostPort.substring(colonIdx + 1).toIntOrNull() ?: 8388 else 8388

            val configJson = buildJsonObject {
                put("methodPass", methodPass)
                put("host", host)
                put("port", port)
                put("name", name)
            }.toString()

            val id = "ss_${host.replace('.', '_')}_$port"

            Profile(
                id = id,
                name = name,
                serverAddress = host,
                serverPort = port,
                protocolType = ProtocolType.SHADOWSOCKS,
                protocolRawConfig = configJson
            )
        }.getOrNull()
    }

    override fun formatUri(profile: Profile): String? = null

    override fun validate(profile: Profile): ValidationResult {
        if (profile.serverAddress.isBlank() || profile.serverPort <= 0) {
            return ValidationResult.Invalid("Invalid server host or port")
        }
        return ValidationResult.Valid
    }

    override fun toCoreConfigJson(profile: Profile): String = profile.protocolRawConfig
}

