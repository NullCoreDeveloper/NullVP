package nullvp.platform

import nullvp.domain.model.Profile

data class SmartPingResult(
    val latencyMs: Long,
    val details: String? = null
)

expect object PingHelper {
    suspend fun measurePing(host: String, port: Int, timeoutMs: Int = 2500): Long
    suspend fun measureProfilePing(profile: Profile, timeoutMs: Int = 6000): SmartPingResult
}

