package nullvp.data.protocol

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

class MdnsProtocolHandler : ProtocolHandler {
    override val protocolType: ProtocolType = ProtocolType.MASTER_DNS
    override val scheme: String = "mdns"
    override val displayName: String = "MasterDNS (DNS Tunnel)"
    override val capabilities: ProtocolCapabilities = ProtocolCapabilities(
        supportsUdp = true,
        requiresTun = false,
        supportsProxyMode = true,
        supportsCaptcha = false
    )

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        prettyPrint = false
    }

    override fun parseUri(uri: String): Profile? {
        val trimmed = uri.trim()
        if (!trimmed.startsWith("mdns://", ignoreCase = true)) return null

        val rest = trimmed.substring(7)
        val hashIdx = rest.indexOf('#')
        val base64Payload = if (hashIdx != -1) rest.substring(0, hashIdx) else rest
        val rawName = if (hashIdx != -1) rest.substring(hashIdx + 1) else "MasterDNS"
        val name = runCatching { URLDecoder.decode(rawName, "UTF-8") }.getOrDefault(rawName)

        val decodedJsonStr = runCatching {
            base64Payload.decodeBase64String()
        }.getOrNull() ?: return null

        val jsonElement = runCatching {
            json.parseToJsonElement(decodedJsonStr).jsonObject
        }.getOrNull() ?: return null

        // Extract primary domain and listen port
        val domains = jsonElement["DOMAINS"]?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull }
            ?: jsonElement["domains"]?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull }
            ?: emptyList()
        val serverAddr = domains.firstOrNull() ?: "dns.tunnel"

        val listenPort = jsonElement["LISTEN_PORT"]?.jsonPrimitive?.intOrNull
            ?: jsonElement["listen_port"]?.jsonPrimitive?.intOrNull
            ?: 18000

        val id = "mdns_${serverAddr.replace('.', '_')}_$listenPort"

        return Profile(
            id = id,
            name = name.ifBlank { "MasterDNS $serverAddr" },
            serverAddress = serverAddr,
            serverPort = listenPort,
            protocolType = ProtocolType.MASTER_DNS,
            protocolRawConfig = decodedJsonStr
        )
    }

    override fun formatUri(profile: Profile): String? {
        if (profile.protocolRawConfig.isBlank()) return null
        val b64 = profile.protocolRawConfig.encodeBase64()
        val encodedName = runCatching { URLEncoder.encode(profile.name, "UTF-8") }.getOrDefault(profile.name)
        return "mdns://$b64#$encodedName"
    }

    override fun validate(profile: Profile): ValidationResult {
        if (profile.protocolRawConfig.isBlank()) {
            return ValidationResult.Invalid("Config JSON is empty")
        }
        val element = runCatching { json.parseToJsonElement(profile.protocolRawConfig).jsonObject }.getOrNull()
            ?: return ValidationResult.Invalid("Invalid JSON syntax in config")

        val hasDomains = element.containsKey("DOMAINS") || element.containsKey("domains")
        if (!hasDomains) {
            return ValidationResult.Invalid("Missing DOMAINS in configuration")
        }
        return ValidationResult.Valid
    }

    override fun toCoreConfigJson(profile: Profile): String {
        return profile.protocolRawConfig
    }
}

