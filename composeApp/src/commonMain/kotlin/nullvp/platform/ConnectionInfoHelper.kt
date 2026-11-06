package nullvp.platform

data class ConnectionInfo(
    val ip: String,
    val maskedIp: String,   // 185.28.*.* (последние два октета скрыты)
    val country: String,
    val countryCode: String,
    val city: String,
    val latencyMs: Long,
    val flag: String        // эмодзи флаг страны
)

expect object ConnectionInfoHelper {
    suspend fun fetchServerInfo(host: String, latencyMs: Long): ConnectionInfo?
    fun maskIp(ip: String): String
    fun countryCodeToFlag(cc: String): String
}
