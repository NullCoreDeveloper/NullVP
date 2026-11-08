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

class MailruProtocolHandler : ProtocolHandler {
    override val protocolType: ProtocolType = ProtocolType.MAILRU
    override val scheme: String = "mailru"
    override val alternativeSchemes: List<String> = listOf("openflux")
    override val displayName: String = "Mail.ru Docs (OpenFlux)"
    override val capabilities: ProtocolCapabilities = ProtocolCapabilities(
        supportsUdp = false,
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
        val colonIdx = trimmed.indexOf("://")
        if (colonIdx == -1) return null

        val schemePart = trimmed.substring(0, colonIdx).lowercase()
        if (schemePart != "mailru" && schemePart != "openflux") return null

        val rest = trimmed.substring(colonIdx + 3)
        val hashIdx = rest.indexOf('#')
        val base64Payload = if (hashIdx != -1) rest.substring(0, hashIdx) else rest
        val rawName = if (hashIdx != -1) rest.substring(hashIdx + 1) else "Mail.ru Docs"
        val name = runCatching { URLDecoder.decode(rawName, "UTF-8") }.getOrDefault(rawName)

        val decodedJsonStr = runCatching {
            base64Payload.decodeBase64String()
        }.getOrNull() ?: return null

        val jsonElement = runCatching {
            json.parseToJsonElement(decodedJsonStr).jsonObject
        }.getOrNull() ?: return null

        val url = jsonElement["url"]?.jsonPrimitive?.contentOrNull
            ?: jsonElement["mailruUrl"]?.jsonPrimitive?.contentOrNull
            ?: jsonElement["weblink"]?.jsonPrimitive?.contentOrNull
            ?: "https://cloud.mail.ru/"

        val port = jsonElement["port"]?.jsonPrimitive?.intOrNull ?: 443
        val id = "mailru_${url.hashCode()}_${(1000..9999).random()}"

        return Profile(
            id = id,
            name = name.ifBlank { "Mail.ru Docs" },
            serverAddress = "cloud.mail.ru",
            serverPort = port,
            protocolType = ProtocolType.MAILRU,
            protocolRawConfig = decodedJsonStr
        )
    }

    override fun formatUri(profile: Profile): String? {
        if (profile.protocolRawConfig.isBlank()) return null
        val b64 = profile.protocolRawConfig.encodeBase64()
        val encodedName = runCatching { URLEncoder.encode(profile.name, "UTF-8") }.getOrDefault(profile.name)
        return "mailru://$b64#$encodedName"
    }

    override fun validate(profile: Profile): ValidationResult {
        if (profile.protocolRawConfig.isBlank()) {
            return ValidationResult.Invalid("Config JSON is empty")
        }
        val element = runCatching { json.parseToJsonElement(profile.protocolRawConfig).jsonObject }.getOrNull()
            ?: return ValidationResult.Invalid("Invalid JSON syntax in config")

        val hasUrl = element.containsKey("url") || element.containsKey("mailruUrl") || element.containsKey("weblink")
        if (!hasUrl) {
            return ValidationResult.Invalid("Missing document URL in configuration")
        }
        return ValidationResult.Valid
    }

    override fun toCoreConfigJson(profile: Profile): String {
        return profile.protocolRawConfig
    }
}
