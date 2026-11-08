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
    override val alternativeSchemes: List<String> = listOf("openflux", "openflux-mailru")
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
        if (schemePart != "mailru" && schemePart != "openflux" && schemePart != "openflux-mailru") return null

        val rest = trimmed.substring(colonIdx + 3)
        val hashIdx = rest.indexOf('#')
        val payload = if (hashIdx != -1) rest.substring(0, hashIdx) else rest
        val rawName = if (hashIdx != -1) rest.substring(hashIdx + 1) else ""
        var profileName = runCatching { URLDecoder.decode(rawName, "UTF-8") }.getOrDefault(rawName).trim()

        var docUrl = ""
        var cookies = ""
        var secretKey = ""
        var isFormatMailru = (schemePart == "mailru")

        // 1. Check if OpenFlux v1 link (raw DEFLATE base64url)
        if (payload.startsWith("v1/")) {
            isFormatMailru = false
            val base64UrlPart = payload.substring(3).trim()
            val decompressed = decompressOpenfluxPayload(base64UrlPart)
            if (decompressed != null) {
                runCatching {
                    val root = json.parseToJsonElement(decompressed).jsonObject
                    if (profileName.isBlank()) {
                        profileName = root["name"]?.jsonPrimitive?.contentOrNull ?: ""
                    }
                    secretKey = root["secret"]?.jsonPrimitive?.contentOrNull ?: ""
                    val transports = root["transports"]?.jsonArray
                    if (transports != null) {
                        for (t in transports) {
                            val tObj = t.jsonObject
                            val type = tObj["type"]?.jsonPrimitive?.contentOrNull?.lowercase()
                            if (type == "mailru") {
                                docUrl = tObj["url"]?.jsonPrimitive?.contentOrNull ?: ""
                                break
                            }
                        }
                    }
                }
            }
        } else {
            // 2. Direct Base64 JSON (mailru:// or classic openflux)
            val decodedJsonStr = runCatching {
                payload.decodeBase64String()
            }.getOrNull()

            if (decodedJsonStr != null) {
                val jsonElement = runCatching {
                    json.parseToJsonElement(decodedJsonStr).jsonObject
                }.getOrNull()

                if (jsonElement != null) {
                    docUrl = jsonElement["url"]?.jsonPrimitive?.contentOrNull
                        ?: jsonElement["mailruUrl"]?.jsonPrimitive?.contentOrNull
                        ?: jsonElement["weblink"]?.jsonPrimitive?.contentOrNull
                        ?: ""
                    cookies = jsonElement["cookies"]?.jsonPrimitive?.contentOrNull ?: ""
                    secretKey = jsonElement["secretKey"]?.jsonPrimitive?.contentOrNull
                        ?: jsonElement["secret"]?.jsonPrimitive?.contentOrNull
                        ?: ""
                    if (schemePart == "mailru" || jsonElement["format"]?.jsonPrimitive?.contentOrNull == "mailru") {
                        isFormatMailru = true
                    }
                }
            }
        }

        if (docUrl.isBlank()) return null

        val finalConfigObj = buildJsonObject {
            put("server", "cloud.mail.ru")
            put("port", 443)
            put("targetProtocol", "tcp")
            put("url", docUrl)
            if (cookies.isNotBlank()) put("cookies", cookies)
            if (secretKey.isNotBlank()) put("secretKey", secretKey)
            put("localPort", 10808)
            put("format", if (isFormatMailru) "mailru" else "openflux")
        }
        val finalConfigStr = json.encodeToString(JsonObject.serializer(), finalConfigObj)

        val id = "mailru_${docUrl.hashCode()}_${(1000..9999).random()}"
        return Profile(
            id = id,
            name = profileName.ifBlank { "Mail.ru Docs" },
            serverAddress = "cloud.mail.ru",
            serverPort = 443,
            protocolType = ProtocolType.MAILRU,
            protocolRawConfig = finalConfigStr
        )
    }

    private fun decompressOpenfluxPayload(b64url: String): String? {
        return runCatching {
            var b64 = b64url.replace('-', '+').replace('_', '/')
            val rem = b64.length % 4
            if (rem == 2) b64 += "=="
            else if (rem == 3) b64 += "="

            val compressedBytes = java.util.Base64.getDecoder().decode(b64)
            val inflater = java.util.zip.Inflater(true)
            inflater.setInput(compressedBytes)

            val buffer = ByteArray(1024)
            val out = java.io.ByteArrayOutputStream()
            while (!inflater.finished()) {
                val count = inflater.inflate(buffer)
                if (count == 0 && inflater.needsInput()) break
                out.write(buffer, 0, count)
            }
            inflater.end()
            out.toString("UTF-8")
        }.getOrNull()
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
