package nullvp.platform

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import nullvp.core.RealXrayConfigBuilder
import nullvp.data.store.AppStateStore
import nullvp.data.store.LogLevel
import nullvp.domain.model.Profile
import nullvp.domain.model.ProtocolType
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ServerSocket
import java.net.Socket
import java.net.URL
import java.util.UUID
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

                val start1 = System.currentTimeMillis()
                socket.send(reqPacket)
                socket.receive(respPacket)
                val rtt1 = System.currentTimeMillis() - start1

                if (respPacket.length < 12) return -1L
                val respId = ((respBuf[0].toInt() and 0xFF) shl 8) or (respBuf[1].toInt() and 0xFF)
                if (respId != txId) return -1L

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
        out.write(0x01)
        out.write(0x00)
        out.write(0x00)
        out.write(0x01)
        out.write(0x00)
        out.write(0x00)
        out.write(0x00)
        out.write(0x00)
        out.write(0x00)
        out.write(0x00)

        val cleanDomain = domain.trim().trim('.').ifEmpty { "ya.ru" }
        for (label in cleanDomain.split(".")) {
            val bytes = label.toByteArray(Charsets.UTF_8)
            if (bytes.isNotEmpty() && bytes.size <= 63) {
                out.write(bytes.size)
                out.write(bytes)
            }
        }
        out.write(0x00)
        out.write(0x00)
        out.write(0x01)
        out.write(0x00)
        out.write(0x01)
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

    private suspend fun measureProxy204Delay(profile: Profile, timeoutMs: Int): SmartPingResult {
        val xrayBin = findXrayBinary() ?: run {
            val tcp = measurePing(profile.serverAddress, profile.serverPort, timeoutMs)
            return SmartPingResult(tcp, if (tcp >= 0) "TCP: ${tcp}ms" else "Нет ответа")
        }

        val tempSocksPort = runCatching { ServerSocket(0).use { it.localPort } }.getOrDefault(0)
        val tempHttpPort = runCatching { ServerSocket(0).use { it.localPort } }.getOrDefault(0)
        if (tempSocksPort <= 0 || tempHttpPort <= 0) {
            return SmartPingResult(-1L, "Ошибка выделения порта для 204-теста")
        }

        val tempSettings = AppStateStore.settings.value.copy(
            socksPort = tempSocksPort,
            httpPort = tempHttpPort,
            proxyAuthEnabled = false,
            allowLan = false
        )

        // Build config without fixed 10085 API port so multiple 204 tests can run in parallel
        val rawConfig = RealXrayConfigBuilder.buildJson(profile, tempSettings, 10815)
        val tempApiPort = runCatching { ServerSocket(0).use { it.localPort } }.getOrDefault(18085)
        val patchedConfig = rawConfig.replace("\"port\": 10085", "\"port\": $tempApiPort")
        val tempConfigFile = File("/tmp/nullvp_ping_${UUID.randomUUID().toString().take(8)}.json")

        var proc: Process? = null
        return try {
            tempConfigFile.writeText(patchedConfig)
            val pb = ProcessBuilder(xrayBin.absolutePath, "run", "-c", tempConfigFile.absolutePath)
            findAssetDir()?.let { pb.environment()["XRAY_LOCATION_ASSET"] = it.absolutePath }
            pb.redirectErrorStream(true)
            proc = pb.start()

            // Wait up to 800ms for tempHttpPort to open
            var portReady = false
            for (i in 0 until 16) {
                if (!proc.isAlive) break
                val open = runCatching {
                    Socket().use { s ->
                        s.connect(InetSocketAddress("127.0.0.1", tempHttpPort), 80)
                        true
                    }
                }.getOrDefault(false)
                if (open) {
                    portReady = true
                    break
                }
                delay(50)
            }

            if (!portReady) {
                return SmartPingResult(-1L, "204 Error: ядро не запустилось")
            }

            val proxy = Proxy(Proxy.Type.HTTP, InetSocketAddress("127.0.0.1", tempHttpPort))
            val testUrls = listOf(
                "http://cp.cloudflare.com/generate_204",
                "http://www.gstatic.com/generate_204",
                "http://connectivitycheck.gstatic.com/generate_204"
            )

            var bestDelay = -1L
            for (urlStr in testUrls) {
                val d = runCatching {
                    val start = System.currentTimeMillis()
                    val conn = (URL(urlStr).openConnection(proxy) as HttpURLConnection).apply {
                        connectTimeout = minOf(timeoutMs, 4500)
                        readTimeout = minOf(timeoutMs, 4500)
                        requestMethod = "GET"
                        instanceFollowRedirects = false
                        useCaches = false
                    }
                    val code = conn.responseCode
                    conn.disconnect()
                    if (code == 204 || code == 200) {
                        System.currentTimeMillis() - start
                    } else -1L
                }.getOrDefault(-1L)

                if (d > 0) {
                    bestDelay = d
                    break
                }
            }

            if (bestDelay > 0) {
                val msg = "204 OK • ${bestDelay}ms (${profile.protocolType.name})"
                AppStateStore.addLog(LogLevel.INFO, "Ping-204", "[${profile.name}] $msg")
                SmartPingResult(bestDelay, msg)
            } else {
                val msg = "204 Timeout (нет ответа через туннель)"
                AppStateStore.addLog(LogLevel.WARN, "Ping-204", "[${profile.name}] $msg")
                SmartPingResult(-1L, msg)
            }
        } catch (e: Exception) {
            SmartPingResult(-1L, "Ошибка 204 теста: ${e.message?.take(40)}")
        } finally {
            runCatching { proc?.destroyForcibly() }
            runCatching { tempConfigFile.delete() }
        }
    }

    private fun findXrayBinary(): File? {
        val appDir = System.getProperty("app.dir") ?: System.getProperty("user.dir") ?: "."
        val candidates = listOf(
            File("$appDir/bin/xray"),
            File("$appDir/lib/app/resources/bin/xray"),
            File("./bin/xray"),
            File("composeApp/build/compose/binaries/main/app/NullVP/bin/xray"),
            File("/usr/bin/xray"),
            File("/usr/local/bin/xray"),
            File("./bin/xray")
        )
        return candidates.firstOrNull { it.exists() && it.canExecute() }
    }

    private fun findAssetDir(): File? {
        val appDir = System.getProperty("app.dir") ?: System.getProperty("user.dir") ?: "."
        val candidates = listOf(
            File("$appDir/lib/app/resources/assets"),
            File("$appDir/assets"),
            File("./assets"),
            File("composeApp/src/desktopMain/resources/assets"),
            File("./assets")
        )
        return candidates.firstOrNull { it.exists() && File(it, "geoip.dat").exists() }
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
