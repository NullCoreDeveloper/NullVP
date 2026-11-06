package nullvp.platform

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.URL

actual object ConnectionInfoHelper {

    private val jsonParser = Json { ignoreUnknownKeys = true; isLenient = true }
    private val cache = mutableMapOf<String, ConnectionInfo>()

    actual suspend fun fetchServerInfo(host: String, latencyMs: Long): ConnectionInfo? = withContext(Dispatchers.IO) {
        val cleanHost = host.trim()
            .removePrefix("http://")
            .removePrefix("https://")
            .substringBefore("/")
            .substringBefore(":")
            .trim()

        if (cleanHost.isBlank()) return@withContext null

        val cached = cache[cleanHost]
        if (cached != null) {
            return@withContext cached.copy(latencyMs = latencyMs)
        }

        try {
            // 1. Resolve host to IP
            val ip = runCatching {
                InetAddress.getByName(cleanHost).hostAddress
            }.getOrNull() ?: cleanHost

            val masked = maskIp(ip)

            // 2. Query geo info via https://ipwho.is
            val url = URL("https://ipwho.is/$ip")
            val conn = (url.openConnection() as HttpURLConnection).apply {
                connectTimeout = 4000
                readTimeout = 4000
                requestMethod = "GET"
                setRequestProperty("User-Agent", "NullVP-Client/1.0")
            }

            if (conn.responseCode in 200..299) {
                val body = conn.inputStream.bufferedReader().use { it.readText() }
                val root = jsonParser.parseToJsonElement(body).jsonObject
                val success = root["success"]?.jsonPrimitive?.content?.toBooleanStrictOrNull() ?: true
                if (success) {
                    val country = root["country"]?.jsonPrimitive?.content ?: ""
                    val countryCode = root["country_code"]?.jsonPrimitive?.content ?: ""
                    val city = root["city"]?.jsonPrimitive?.content ?: ""
                    val flagEmoji = root["flag"]?.jsonObject?.get("emoji")?.jsonPrimitive?.content
                        ?: countryCodeToFlag(countryCode)

                    val info = ConnectionInfo(
                        ip = ip,
                        maskedIp = masked,
                        country = country,
                        countryCode = countryCode,
                        city = city,
                        latencyMs = latencyMs,
                        flag = flagEmoji
                    )
                    cache[cleanHost] = info
                    return@withContext info
                }
            }

            val fallback = ConnectionInfo(
                ip = ip,
                maskedIp = masked,
                country = "Сервер",
                countryCode = "",
                city = cleanHost,
                latencyMs = latencyMs,
                flag = "🌍"
            )
            cache[cleanHost] = fallback
            fallback
        } catch (e: Exception) {
            val ip = cleanHost
            ConnectionInfo(
                ip = ip,
                maskedIp = maskIp(ip),
                country = "Сервер",
                countryCode = "",
                city = cleanHost,
                latencyMs = latencyMs,
                flag = "⚡"
            )
        }
    }

    actual fun maskIp(ip: String): String {
        val parts = ip.split(".")
        return if (parts.size == 4) {
            "${parts[0]}.${parts[1]}.*.*"
        } else {
            val groups = ip.split(":")
            if (groups.size >= 4) {
                groups.take(groups.size / 2).joinToString(":") + ":*:*:*:*"
            } else ip
        }
    }

    actual fun countryCodeToFlag(cc: String): String {
        if (cc.length != 2) return "🌍"
        return cc.uppercase().map { char ->
            String(Character.toChars(char.code - 'A'.code + 0x1F1E6))
        }.joinToString("")
    }
}
