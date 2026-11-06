package nullvp.data.protocol

import io.ktor.util.decodeBase64Bytes
import io.ktor.util.decodeBase64String
import io.ktor.util.encodeBase64
import kotlinx.serialization.json.*
import nullvp.domain.model.Profile
import nullvp.domain.model.ProtocolType
import nullvp.domain.protocol.ProtocolCapabilities
import nullvp.domain.protocol.ProtocolHandler
import nullvp.domain.protocol.ValidationResult
import java.net.URLDecoder
import java.net.URLEncoder

class VkTurnProtocolHandler : ProtocolHandler {
    override val protocolType: ProtocolType = ProtocolType.VK_TURN
    override val scheme: String = "vkturn"
    override val alternativeSchemes: List<String> = listOf("freeturn")
    override val displayName: String = "VK TURN / FreeTURN"
    override val capabilities: ProtocolCapabilities = ProtocolCapabilities(
        supportsUdp = true,
        requiresTun = true,
        supportsProxyMode = true,
        supportsCaptcha = true
    )

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        prettyPrint = false
    }

    override fun parseUri(uri: String): Profile? {
        val trimmed = uri.trim()
        val isFreeturn = trimmed.startsWith("freeturn://", ignoreCase = true)
        val isVkturn = trimmed.startsWith("vkturn://", ignoreCase = true)
        if (!isFreeturn && !isVkturn) return null

        val prefixLen = if (isFreeturn) 11 else 9
        val rest = trimmed.substring(prefixLen)
        val hashIdx = rest.indexOf('#')
        val base64Payload = if (hashIdx != -1) rest.substring(0, hashIdx) else rest
        val rawName = if (hashIdx != -1) rest.substring(hashIdx + 1) else ""
        val linkName = runCatching { URLDecoder.decode(rawName, "UTF-8") }.getOrDefault(rawName)

        val decodedJsonStr = runCatching {
            decodeBase64Payload(base64Payload)
        }.getOrNull() ?: return null

        val jsonElement = runCatching {
            json.parseToJsonElement(decodedJsonStr).jsonObject
        }.getOrNull() ?: return null

        val name = jsonElement["name"]?.jsonPrimitive?.contentOrNull
            ?: linkName.ifBlank { "VK TURN Proxy" }

        val serverAddr: String
        val serverPort: Int

        if (isFreeturn) {
            val peer = jsonElement["peer"]?.jsonPrimitive?.contentOrNull ?: "127.0.0.1:443"
            val parts = peer.split(":")
            serverAddr = parts.getOrNull(0) ?: "127.0.0.1"
            serverPort = parts.getOrNull(1)?.toIntOrNull() ?: 443
        } else {
            serverAddr = jsonElement["server"]?.jsonPrimitive?.contentOrNull ?: "127.0.0.1"
            serverPort = jsonElement["port"]?.jsonPrimitive?.intOrNull ?: 443
        }

        val id = "vkturn_${serverAddr.replace('.', '_')}_${serverPort}_${(1000..9999).random()}"

        return Profile(
            id = id,
            name = name,
            serverAddress = serverAddr,
            serverPort = serverPort,
            protocolType = ProtocolType.VK_TURN,
            protocolRawConfig = decodedJsonStr
        )
    }

    override fun formatUri(profile: Profile): String? {
        if (profile.protocolRawConfig.isBlank()) return null
        val b64 = profile.protocolRawConfig.encodeBase64()
        val encodedName = runCatching { URLEncoder.encode(profile.name, "UTF-8") }.getOrDefault(profile.name)
        return "vkturn://$b64#$encodedName"
    }

    override fun validate(profile: Profile): ValidationResult {
        if (profile.protocolRawConfig.isBlank()) {
            return ValidationResult.Invalid("Empty config")
        }
        val element = runCatching { json.parseToJsonElement(profile.protocolRawConfig).jsonObject }.getOrNull()
            ?: return ValidationResult.Invalid("Invalid JSON syntax")

        val hasVkLink = element.containsKey("vk") || element.containsKey("vkLink")
        if (!hasVkLink) {
            return ValidationResult.Invalid("Missing VK call link")
        }
        return ValidationResult.Valid
    }

    override fun toCoreConfigJson(profile: Profile): String {
        return runCatching {
            val element = json.parseToJsonElement(profile.protocolRawConfig).jsonObject
            buildJsonObject {
                element.forEach { (k, v) -> put(k, v) }
                if (!element.containsKey("server")) put("server", profile.serverAddress)
                if (!element.containsKey("port")) put("port", profile.serverPort)
            }.toString()
        }.getOrDefault(profile.protocolRawConfig)
    }

    private fun decodeBase64Payload(raw: String): String {
        // Handle URL-safe base64 padding
        var s = raw.replace('-', '+').replace('_', '/')
        val mod = s.length % 4
        if (mod > 0) {
            s += "=".repeat(4 - mod)
        }
        return s.decodeBase64String()
    }
}

