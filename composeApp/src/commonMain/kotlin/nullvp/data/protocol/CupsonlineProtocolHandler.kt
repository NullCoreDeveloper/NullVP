package nullvp.data.protocol

import io.ktor.util.decodeBase64String
import io.ktor.util.encodeBase64
import kotlinx.serialization.json.*
import nullvp.domain.model.Profile
import nullvp.domain.model.ProtocolType
import nullvp.domain.protocol.ProtocolCapabilities
import nullvp.domain.protocol.ProtocolHandler
import nullvp.domain.protocol.ValidationResult
import java.io.ByteArrayInputStream
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.Base64
import java.util.zip.Inflater
import java.util.zip.InflaterInputStream

class CupsonlineProtocolHandler : ProtocolHandler {
    override val protocolType: ProtocolType = ProtocolType.CUPSONLINE
    override val scheme: String = "cupsonline"
    override val alternativeSchemes: List<String> = listOf("openflux", "openflux-cups", "cups")
    override val displayName: String = "VK Cups.online (OpenFlux)"
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
        val rest = trimmed.substring(colonIdx + 3)
        val hashIdx = rest.indexOf('#')
        val payload = if (hashIdx != -1) rest.substring(0, hashIdx) else rest
        val rawName = if (hashIdx != -1) rest.substring(hashIdx + 1) else ""
        var profileName = runCatching { URLDecoder.decode(rawName, "UTF-8") }.getOrDefault(rawName).trim()

        var rooms = ""

        if (schemePart == "openflux" && payload.startsWith("v1/")) {
            val base64UrlPart = payload.substring(3).trim()
            val decompressed = decompressOpenfluxPayload(base64UrlPart)
            if (decompressed != null) {
                runCatching {
                    val root = json.parseToJsonElement(decompressed).jsonObject
                    if (profileName.isBlank()) {
                        profileName = root["name"]?.jsonPrimitive?.contentOrNull ?: ""
                    }
                    val transports = root["transports"]?.jsonArray
                    if (transports != null) {
                        for (t in transports) {
                            val tObj = t.jsonObject
                            val type = tObj["type"]?.jsonPrimitive?.contentOrNull?.lowercase()
                            if (type == "cupsonline" || type == "cups") {
                                rooms = tObj["rooms"]?.jsonPrimitive?.contentOrNull ?: ""
                                break
                            }
                        }
                    }
                }
            }
        } else if (schemePart == "cupsonline" || schemePart == "openflux-cups" || schemePart == "cups") {
            val decodedJsonStr = runCatching {
                payload.decodeBase64String()
            }.getOrNull()

            if (decodedJsonStr != null) {
                val jsonElement = runCatching {
                    json.parseToJsonElement(decodedJsonStr).jsonObject
                }.getOrNull()

                if (jsonElement != null) {
                    rooms = jsonElement["rooms"]?.jsonPrimitive?.contentOrNull ?: ""
                }
            }
        }

        if (rooms.isBlank()) return null
        if (profileName.isBlank()) profileName = "VK Cups"

        val rawConfigObj = buildJsonObject {
            put("rooms", rooms)
            put("server", "interview.cups.online")
            put("port", 443)
            put("localPort", 10808)
            put("format", if (schemePart == "cupsonline") "cupsonline" else "openflux")
        }

        return Profile(
            id = "cups_${rooms.hashCode()}_${System.currentTimeMillis()}",
            name = profileName,
            serverAddress = "interview.cups.online",
            serverPort = 443,
            protocolType = ProtocolType.CUPSONLINE,
            protocolRawConfig = rawConfigObj.toString()
        )
    }

    override fun formatUri(profile: Profile): String? {
        val root = runCatching {
            json.parseToJsonElement(profile.protocolRawConfig).jsonObject
        }.getOrNull() ?: return null

        val b64 = root.toString().encodeBase64()
        val remark = runCatching { URLEncoder.encode(profile.name, "UTF-8") }.getOrDefault(profile.name)
        return "cupsonline://$b64#$remark"
    }

    override fun validate(profile: Profile): ValidationResult {
        if (profile.protocolType != ProtocolType.CUPSONLINE) {
            return ValidationResult.Invalid("Not a Cupsonline profile")
        }
        val root = runCatching {
            json.parseToJsonElement(profile.protocolRawConfig).jsonObject
        }.getOrNull() ?: return ValidationResult.Invalid("Invalid JSON config")

        val rooms = root["rooms"]?.jsonPrimitive?.contentOrNull
        if (rooms.isNullOrBlank()) {
            return ValidationResult.Invalid("Rooms parameter is required")
        }
        return ValidationResult.Valid
    }

    override fun toCoreConfigJson(profile: Profile): String {
        return profile.protocolRawConfig
    }

    private fun decompressOpenfluxPayload(b64url: String): String? {
        return runCatching {
            var padded = b64url.replace('-', '+').replace('_', '/')
            while (padded.length % 4 != 0) padded += "="
            val compressed = Base64.getDecoder().decode(padded)
            val inflater = Inflater(true)
            val input = InflaterInputStream(ByteArrayInputStream(compressed), inflater)
            input.readBytes().decodeToString()
        }.getOrNull()
    }
}

