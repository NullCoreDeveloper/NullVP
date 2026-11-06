package nullvp.platform

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.InetSocketAddress
import java.net.Socket

actual object PingHelper {
    actual suspend fun measurePing(host: String, port: Int, timeoutMs: Int): Long = withContext(Dispatchers.IO) {
        try {
            val start = System.currentTimeMillis()
            Socket().use { socket ->
                socket.connect(InetSocketAddress(host, port), timeoutMs)
            }
            System.currentTimeMillis() - start
        } catch (_: Exception) {
            -1L
        }
    }
}

