package nullvp.data.subscription

import io.ktor.client.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.util.decodeBase64String
import nullvp.domain.model.Profile
import nullvp.domain.model.Subscription
import nullvp.domain.protocol.ProtocolRegistry

class SubscriptionManager(
    private val httpClient: HttpClient
) {
    suspend fun fetchSubscription(subscriptionUrl: String, customName: String? = null): Result<Pair<Subscription, List<Profile>>> {
        return runCatching {
            val response = httpClient.get(subscriptionUrl)
            val bodyText = response.bodyAsText().trim()

            // Parse Userinfo headers: "upload=100; download=200; total=1000; expire=1700000000"
            val userInfoHeader = response.headers["subscription-userinfo"]
                ?: response.headers["Subscription-Userinfo"]

            var upload = 0L
            var download = 0L
            var total = 0L
            var expire = 0L

            if (!userInfoHeader.isNullOrBlank()) {
                val parts = userInfoHeader.split(";")
                for (p in parts) {
                    val kv = p.trim().split("=")
                    if (kv.size == 2) {
                        val key = kv[0].trim().lowercase()
                        val value = kv[1].trim().toLongOrNull() ?: 0L
                        when (key) {
                            "upload" -> upload = value
                            "download" -> download = value
                            "total" -> total = value
                            "expire" -> expire = value
                        }
                    }
                }
            }

            // Check if content is Base64 encoded or raw text
            val decodedContent = if (isLikelyBase64(bodyText)) {
                runCatching { bodyText.decodeBase64String() }.getOrDefault(bodyText)
            } else {
                bodyText
            }

            val lines = decodedContent.split("\n", "\r\n")
            val subId = "sub_${subscriptionUrl.hashCode()}"
            val subName = customName?.ifBlank { null }
                ?: response.headers["profile-title"]
                ?: "Subscription"

            val profiles = mutableListOf<Profile>()
            var nodeIdx = 0
            for (line in lines) {
                val trimmed = line.trim()
                if (trimmed.isBlank() || trimmed.startsWith("#")) continue
                val profile = ProtocolRegistry.parse(trimmed)
                if (profile != null) {
                    val uniqueId = "${profile.id}_${nodeIdx++}"
                    profiles.add(profile.copy(id = uniqueId, subscriptionId = subId))
                }
            }

            val subscription = Subscription(
                id = subId,
                name = subName,
                url = subscriptionUrl,
                lastUpdated = System.currentTimeMillis(),
                uploadBytes = upload,
                downloadBytes = download,
                totalBytes = total,
                expireTimestamp = expire
            )

            val cleanedProfiles = nullvp.data.store.AppStateStore.deduplicateVkProfiles(profiles)
            Pair(subscription, cleanedProfiles)
        }
    }

    private fun isLikelyBase64(text: String): Boolean {
        if (text.startsWith("vless://") || text.startsWith("mdns://") ||
            text.startsWith("vkturn://") || text.startsWith("freeturn://")) {
            return false
        }
        val clean = text.filter { !it.isWhitespace() }
        if (clean.length % 4 != 0) return false
        val base64Pattern = Regex("^[A-Za-z0-9+/=]+$")
        return base64Pattern.matches(clean)
    }
}

