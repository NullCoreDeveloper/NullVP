package nullvp.platform

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.TimeUnit
import kotlin.math.roundToLong

actual object PingHelper {
    actual suspend fun measurePing(host: String, port: Int, timeoutMs: Int): Long = withContext(Dispatchers.IO) {
        val cleanHost = host.trim()
            .removePrefix("http://")
            .removePrefix("https://")
            .substringBefore("/")
            .substringBefore(":")
            .trim()

        if (cleanHost.isBlank()) return@withContext -1L

        // 1. Try real ICMP ping (works for both UDP/QUIC like Hysteria2 and TCP nodes)
        val icmpLatency = measureIcmpPing(cleanHost, timeoutMs)
        if (icmpLatency > 0) {
            return@withContext icmpLatency
        }

        // 2. Fallback: TCP socket handshake for TCP-based protocols
        if (port > 0) {
            val tcpLatency = measureTcpPing(cleanHost, port, timeoutMs)
            if (tcpLatency > 0) {
                return@withContext tcpLatency
            }
        }

        // 3. Fallback: InetAddress isReachable
        runCatching {
            val start = System.currentTimeMillis()
            val addr = InetAddress.getByName(cleanHost)
            if (addr.isReachable(timeoutMs)) {
                System.currentTimeMillis() - start
            } else -1L
        }.getOrDefault(-1L)
    }

    private fun measureIcmpPing(host: String, timeoutMs: Int): Long {
        return runCatching {
            val timeoutSec = maxOf(1, timeoutMs / 1000)
            val pb = ProcessBuilder("ping", "-c", "1", "-W", timeoutSec.toString(), host)
            pb.redirectErrorStream(true)
            val proc = pb.start()

            val output = proc.inputStream.bufferedReader().use { it.readText() }
            val finished = proc.waitFor((timeoutMs + 400).toLong(), TimeUnit.MILLISECONDS)
            if (!finished) {
                proc.destroyForcibly()
                return -1L
            }

            if (proc.exitValue() == 0) {
                // Look for "time=XX.X ms"
                val timeMatch = Regex("""time=([0-9.]+)\s*ms""").find(output)
                if (timeMatch != null) {
                    val ms = timeMatch.groupValues[1].toDoubleOrNull()
                    if (ms != null) return ms.roundToLong()
                }

                // Or "rtt min/avg/max/mdev = min/avg/max/mdev"
                val rttMatch = Regex("""rtt min/avg/max/mdev = [0-9.]+/([0-9.]+)/""").find(output)
                if (rttMatch != null) {
                    val ms = rttMatch.groupValues[1].toDoubleOrNull()
                    if (ms != null) return ms.roundToLong()
                }
            }
            -1L
        }.getOrDefault(-1L)
    }

    private fun measureTcpPing(host: String, port: Int, timeoutMs: Int): Long {
        return runCatching {
            val start = System.currentTimeMillis()
            Socket().use { socket ->
                socket.connect(InetSocketAddress(host, port), timeoutMs)
            }
            System.currentTimeMillis() - start
        }.getOrDefault(-1L)
    }
}
