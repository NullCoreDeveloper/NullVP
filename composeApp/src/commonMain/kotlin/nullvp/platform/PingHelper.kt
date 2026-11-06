package nullvp.platform

expect object PingHelper {
    suspend fun measurePing(host: String, port: Int, timeoutMs: Int = 2500): Long
}

