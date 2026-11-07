package nullvp.platform

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import libv2ray.Libv2ray
import nullvp.core.RealXrayConfigBuilder
import nullvp.data.store.AppStateStore
import nullvp.data.store.LogLevel
import nullvp.domain.model.Profile
import nullvp.domain.model.ProtocolType
import java.io.ByteArrayOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL
import java.util.concurrent.TimeUnit
import kotlin.math.roundToLong
import kotlin.random.Random

actual object PingHelper {
    private val defaultDnsResolvers = listOf(
        "77.88.8.8", "77.88.8.1", "1.1.1.1", "1.0.0.1", "8.8.8.8", "8.8.4.4", "9.9.9.9", "208.67.222.222"
    )

    actual suspend fun measurePing(host: String, port: Int, timeoutMs: Int): Long = withContext(Dispatchers.IO) {
        val cleanHost = host.trim()
            .removePrefix("http://")
            .removePrefix("https://")
            .substringBefore("/")
            .substringBefore(":")
            .trim()

        if (cleanHost.isBlank()) return@withContext -1L

        val icmpLatency = measureIcmpPing(cleanHost, timeoutMs)
        if (icmpLatency > 0) return@withContext icmpLatency

        if (port > 0) {
            val tcpLatency = measureTcpPing(cleanHost, port, timeoutMs)
            if (tcpLatency > 0) return@withContext tcpLatency
        }

        runCatching {
            val start = System.currentTimeMillis()
            val addr = InetAddress.getByName(cleanHost)
            if (addr.isReachable(timeoutMs)) {
                System.currentTimeMillis() - start
            } else -1L
        }.getOrDefault(-1L)
    }

    actual suspend fun measureProfilePing(profile: Profile, timeoutMs: Int): SmartPingResult = withContext(Dispatchers.IO) {
        when (profile.protocolType) {
            ProtocolType.MASTER_DNS -> measureMdnsResolvers(profile, timeoutMs)
            ProtocolType.VK_TURN -> measureVkTurnReachability(profile, timeoutMs)
            else -> measureProxy204Delay(profile, timeoutMs)
        }
    }

    private suspend fun measureMdnsResolvers(profile: Profile, timeoutMs: Int): SmartPingResult = coroutineScope {
        val rawObj = runCatching { Json.parseToJsonElement(profile.protocolRawConfig).jsonObject }.getOrNull()
        val resolvers = mutableListOf<String>()

        rawObj?.get("RESOLVERS")?.let { elem ->
            if (elem is JsonArray) {
                elem.forEach { item ->
                    item.jsonPrimitive.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }?.let { resolvers.add(it) }
                }
            } else if (elem is JsonPrimitive) {
                elem.contentOrNull?.split(",", "\n", ";")?.map { it.trim() }?.filter { it.isNotEmpty() }?.let { resolvers.addAll(it) }
            }
        }
        if (resolvers.isEmpty()) {
            resolvers.addAll(defaultDnsResolvers)
        }

        val probeDomain = rawObj?.get("DOMAINS")?.let { elem ->
            when (elem) {
                is JsonArray -> elem.firstOrNull()?.jsonPrimitive?.contentOrNull
                is JsonPrimitive -> elem.contentOrNull
                else -> null
            }
        }?.takeIf { it.isNotBlank() && !it.contains("{{") } ?: profile.serverAddress.ifBlank { "ya.ru" }

        val perResolverTimeout = minOf(timeoutMs, 2000)
        val distinctResolvers = resolvers.distinct()

        val results = distinctResolvers.map { resolver ->
            async(Dispatchers.IO) {
                val rtt = probeDnsServerUdp(resolver, probeDomain, perResolverTimeout)
                resolver to rtt
            }
        }.awaitAll()

        val answered = results.filter { it.second >= 0 }.sortedBy { it.second }
        val total = distinctResolvers.size
        val okCount = answered.size

        if (answered.isEmpty()) {
            val msg = "DNS: 0/$total ответили (нет ответа)"
            AppStateStore.addLog(LogLevel.WARN, "MDNS-Ping", "[${profile.name}] $msg")
            return@coroutineScope SmartPingResult(-1L, msg)
        }

        val bestLatency = answered.first().second
        val avgLatency = answered.map { it.second }.average().roundToLong()
        val topList = answered.take(5).joinToString(", ") { "${it.first} (${it.second}ms)" }
        val extra = if (answered.size > 5) " +${answered.size - 5}" else ""
        val details = "DNS ($okCount/$total ок, ср. ${avgLatency}ms): $topList$extra"

        AppStateStore.addLog(LogLevel.INFO, "MDNS-Ping", "[${profile.name}] $details")
        SmartPingResult(bestLatency, details)
    }

    private fun probeDnsServerUdp(resolver: String, domain: String, timeoutMs: Int): Long {
        return runCatching {
            val host = resolver.substringBefore(":").trim()
            val port = resolver.substringAfter(":", "53").toIntOrNull() ?: 53
            val txId = Random.nextInt(1, 65535)
            val query = buildDnsQueryPacket(txId, domain)

            DatagramSocket().use { socket ->
                socket.soTimeout = timeoutMs
                val addr = InetAddress.getByName(host)
                val reqPacket = DatagramPacket(query, query.size, addr, port)
                val respBuf = ByteArray(512)
                val respPacket = DatagramPacket(respBuf, respBuf.size)

                // Do 2 probes to check stability and get accurate RTT
                val start1 = System.currentTimeMillis()
                socket.send(reqPacket)
                socket.receive(respPacket)
                val rtt1 = System.currentTimeMillis() - start1

                if (respPacket.length < 12) return -1L
                val respId = ((respBuf[0].toInt() and 0xFF) shl 8) or (respBuf[1].toInt() and 0xFF)
                if (respId != txId) return -1L

                // Second quick confirmation probe
                val txId2 = Random.nextInt(1, 65535)
                val query2 = buildDnsQueryPacket(txId2, domain)
                val start2 = System.currentTimeMillis()
                socket.send(DatagramPacket(query2, query2.size, addr, port))
                val rtt2 = runCatching {
                    socket.receive(respPacket)
                    System.currentTimeMillis() - start2
                }.getOrDefault(rtt1)

                minOf(rtt1, rtt2)
            }
        }.getOrDefault(-1L)
    }

    private fun buildDnsQueryPacket(txId: Int, domain: String): ByteArray {
        val out = ByteArrayOutputStream()
        out.write((txId shr 8) and 0xFF)
        out.write(txId and 0xFF)
        out.write(0x01) // Flags: standard query, RD=1
        out.write(0x00)
        out.write(0x00)
        out.write(0x01) // QDCOUNT = 1
        out.write(0x00)
        out.write(0x00) // ANCOUNT = 0
        out.write(0x00)
        out.write(0x00) // NSCOUNT = 0
        out.write(0x00)
        out.write(0x00) // ARCOUNT = 0

        val cleanDomain = domain.trim().trim('.').ifEmpty { "ya.ru" }
        for (label in cleanDomain.split(".")) {
            val bytes = label.toByteArray(Charsets.UTF_8)
            if (bytes.isNotEmpty() && bytes.size <= 63) {
                out.write(bytes.size)
                out.write(bytes)
            }
        }
        out.write(0x00) // End of QNAME
        out.write(0x00)
        out.write(0x01) // QTYPE = A (1)
        out.write(0x00)
        out.write(0x01) // QCLASS = IN (1)
        return out.toByteArray()
    }

    private suspend fun measureVkTurnReachability(profile: Profile, timeoutMs: Int): SmartPingResult = coroutineScope {
        val rawObj = runCatching { Json.parseToJsonElement(profile.protocolRawConfig).jsonObject }.getOrNull()
        val vkLink = rawObj?.get("vkLink")?.jsonPrimitive?.contentOrNull
            ?: rawObj?.get("vk")?.jsonPrimitive?.contentOrNull
            ?: "https://vk.com/call/join/"

        val vkEndpoints = listOf(
            "api.vk.com" to 443,
            "vk.com" to 443,
            "calls.vk.com" to 443
        )

        val endpointChecks = vkEndpoints.map { (host, port) ->
            async(Dispatchers.IO) {
                val rtt = measureTcpPing(host, port, minOf(timeoutMs, 2500))
                host to rtt
            }
        }

        val linkCheck = async(Dispatchers.IO) {
            runCatching {
                val urlStr = if (vkLink.startsWith("http")) vkLink else "https://$vkLink"
                val start = System.currentTimeMillis()
                val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 2500
                    readTimeout = 2500
                    requestMethod = "HEAD"
                    instanceFollowRedirects = true
                }
                val code = conn.responseCode
                conn.disconnect()
                if (code in 200..499) System.currentTimeMillis() - start else -1L
            }.getOrDefault(-1L)
        }

        val relayCheck = async(Dispatchers.IO) {
            val host = profile.serverAddress.trim()
            val port = if (profile.serverPort > 0) profile.serverPort else 443
            if (host.isNotBlank() && host != "127.0.0.1") {
                measurePing(host, port, 2000)
            } else -1L
        }

        val vkResults = endpointChecks.awaitAll()
        val linkRtt = linkCheck.await()
        val relayRtt = relayCheck.await()

        val aliveVk = vkResults.filter { it.second >= 0 }
        if (aliveVk.isEmpty() && linkRtt < 0) {
            val msg = "VK TURN: серверы ВК недоступны"
            AppStateStore.addLog(LogLevel.WARN, "VK-Ping", "[${profile.name}] $msg")
            return@coroutineScope SmartPingResult(-1L, msg)
        }

        val bestVk = aliveVk.minByOrNull { it.second }?.second ?: linkRtt
        val vkStatus = aliveVk.joinToString(", ") { "${it.first}: ${it.second}ms" }
        val relayStr = if (relayRtt >= 0) " • Узел: ${relayRtt}ms" else ""
        val details = "VK (${aliveVk.size}/${vkEndpoints.size} ок): $vkStatus$relayStr"

        AppStateStore.addLog(LogLevel.INFO, "VK-Ping", "[${profile.name}] $details")
        SmartPingResult(bestVk, details)
    }

    private fun measureProxy204Delay(profile: Profile, timeoutMs: Int): SmartPingResult {
        return runCatching {
            val settings = AppStateStore.settings.value
            val configJson = RealXrayConfigBuilder.buildJson(profile, settings, 10815)
            val testUrls = listOf(
                "http://cp.cloudflare.com/generate_204",
                "http://www.gstatic.com/generate_204",
                "http://connectivitycheck.gstatic.com/generate_204"
            )
            var delayMs = -1L
            for (testUrl in testUrls) {
                val d = runCatching {
                    Libv2ray.measureOutboundDelay(configJson, testUrl)
                }.getOrDefault(-1L)
                if (d > 0) {
                    delayMs = d
                    break
                }
            }
            if (delayMs > 0) {
                val msg = "204 OK • ${delayMs}ms (${profile.protocolType.name})"
                AppStateStore.addLog(LogLevel.INFO, "Ping-204", "[${profile.name}] $msg")
                SmartPingResult(delayMs, msg)
            } else {
                val msg = "204 Timeout (прокси не отвечает)"
                AppStateStore.addLog(LogLevel.WARN, "Ping-204", "[${profile.name}] $msg")
                SmartPingResult(-1L, msg)
            }
        }.getOrElse { e ->
            SmartPingResult(-1L, "Ошибка 204 теста: ${e.message?.take(40)}")
        }
    }

    private fun measureIcmpPing(host: String, timeoutMs: Int): Long {
        return runCatching {
            val timeoutSec = maxOf(1, timeoutMs / 1000)
            val pingCmd = if (java.io.File("/system/bin/ping").exists()) "/system/bin/ping" else "ping"
            val pb = ProcessBuilder(pingCmd, "-c", "1", "-W", timeoutSec.toString(), host)
            pb.redirectErrorStream(true)
            val proc = pb.start()

            val output = proc.inputStream.bufferedReader().use { it.readText() }
            val finished = proc.waitFor((timeoutMs + 400).toLong(), TimeUnit.MILLISECONDS)
            if (!finished) {
                proc.destroyForcibly()
                return -1L
            }

            if (proc.exitValue() == 0) {
                val timeMatch = Regex("""time=([0-9.]+)\s*ms""").find(output)
                if (timeMatch != null) {
                    val ms = timeMatch.groupValues[1].toDoubleOrNull()
                    if (ms != null) return ms.roundToLong()
                }

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
