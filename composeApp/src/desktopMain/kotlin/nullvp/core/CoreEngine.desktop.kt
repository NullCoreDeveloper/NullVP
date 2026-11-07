package nullvp.core

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.*
import nullvp.data.store.AppStateStore
import nullvp.data.store.LogLevel
import nullvp.domain.model.OperationMode
import nullvp.domain.model.Profile
import nullvp.domain.model.ProtocolType
import nullvp.domain.model.TrafficStats
import nullvp.platform.DesktopPrivilegeManager
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit

class DesktopCoreEngine : CoreEngine {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val _isRunning = MutableStateFlow(false)
    override val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()

    private val _isConnecting = MutableStateFlow(false)
    override val isConnecting: StateFlow<Boolean> = _isConnecting.asStateFlow()

    private val _connectionError = MutableStateFlow<String?>(null)
    override val connectionError: StateFlow<String?> = _connectionError.asStateFlow()

    private val _stats = MutableStateFlow(TrafficStats())
    override val stats: StateFlow<TrafficStats> = _stats.asStateFlow()

    private val _logs = MutableStateFlow<List<String>>(listOf("[INIT] Desktop Core Engine Ready"))
    override val logs: StateFlow<List<String>> = _logs.asStateFlow()

    private val _currentCaptchaUrl = MutableStateFlow<String?>(null)
    override val currentCaptchaUrl: StateFlow<String?> = _currentCaptchaUrl.asStateFlow()

    private var coreProcess: Process? = null
    private var companionProcess: Process? = null
    private var tunProcess: Process? = null
    private var startJob: Job? = null
    private var statsJob: Job? = null
    private var logJob: Job? = null
    private var companionLogJob: Job? = null

    companion object {
        private const val CONFIG_PATH = "/tmp/nullvp_active.json"
        private const val DRIVER_CONFIG_PATH = "/tmp/nullvp_driver.json"
        private const val TUN_DEVICE = "nullvp0"
        var activeProfile: Profile? = null
        var isRunningState: Boolean = false
    }

    private fun findBinary(name: String): File? {
        val appDir = System.getProperty("app.dir") ?: System.getProperty("user.dir") ?: "."
        val candidates = listOf(
            File("$appDir/bin/$name"),
            File("$appDir/lib/app/resources/bin/$name"),
            File("./bin/$name"),
            File("composeApp/build/compose/binaries/main/app/NullVP/bin/$name"),
            File("/usr/bin/$name"),
            File("/usr/local/bin/$name"),
            File("./bin/$name"),
            File("./bin/$name")
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
            File("./assets"),
            File("./assets")
        )
        return candidates.firstOrNull { it.exists() && File(it, "geoip.dat").exists() }
    }

    private fun killExistingZombieProcesses() {
        runCatching {
            ProcessBuilder("pkill", "-9", "-f", "xray.*$CONFIG_PATH").start().waitFor(500, TimeUnit.MILLISECONDS)
            ProcessBuilder("pkill", "-9", "-f", "tun2socks.*$TUN_DEVICE").start().waitFor(500, TimeUnit.MILLISECONDS)
            ProcessBuilder("pkill", "-9", "-f", "nullvp-core.*").start().waitFor(500, TimeUnit.MILLISECONDS)
        }
    }

    override fun start(profile: Profile): Result<Unit> {
        _isConnecting.value = true
        _connectionError.value = null

        startJob?.cancel()
        startJob = scope.launch(Dispatchers.IO) {
            try {
                killExistingZombieProcesses()

                val settings = AppStateStore.settings.value
                appendLog("[START] Preparing profile '${profile.name}' (${profile.protocolType})")
                AppStateStore.addLog(LogLevel.INFO, "CoreEngine", "Building configuration for '${profile.name}'")

                val isCustomDriver = profile.protocolType == ProtocolType.MASTER_DNS || profile.protocolType == ProtocolType.VK_TURN
                val localRelayPort = if (isCustomDriver) {
                    runCatching { java.net.ServerSocket(0).use { it.localPort } }.getOrDefault(10815)
                } else 10815

                // 1. Build real Xray configuration
                val configContent = RealXrayConfigBuilder.buildJson(profile, settings, localRelayPort)
                val configFile = File(CONFIG_PATH)
                configFile.writeText(configContent)
                appendLog("[CONFIG] Generated config at $CONFIG_PATH (${configContent.length} bytes)")

                val xrayBinary = findBinary("xray")
                if (xrayBinary == null) {
                    val err = "Executable binary 'xray' not found! Make sure ./bin/xray exists."
                    appendLog("[ERROR] $err")
                    AppStateStore.addLog(LogLevel.ERROR, "CoreEngine", err)
                    _isConnecting.value = false
                    _connectionError.value = err
                    return@launch
                }

                // If custom tunnel driver (VK_TURN or MASTER_DNS), start nullvp-core first
                if (isCustomDriver) {
                    val nullvpBinary = findBinary("nullvp-core")
                    if (nullvpBinary == null) {
                        val err = "Binary 'nullvp-core' not found for protocol ${profile.protocolType}!"
                        appendLog("[ERROR] $err")
                        AppStateStore.addLog(LogLevel.ERROR, "CoreEngine", err)
                        _isConnecting.value = false
                        _connectionError.value = err
                        return@launch
                    }

                    val driverConfigFile = File(DRIVER_CONFIG_PATH)
                    val defaultResolvers = listOf(
                        "1.1.1.1", "1.0.0.1",
                        "8.8.8.8", "8.8.4.4",
                        "9.9.9.9", "149.112.112.112",
                        "208.67.222.222", "208.67.220.220",
                        "208.67.222.123", "208.67.220.123",
                        "4.2.2.1", "4.2.2.2", "4.2.2.3", "4.2.2.4",
                        "84.200.69.80", "84.200.70.40",
                        "64.6.64.6", "64.6.65.6"
                    )
                    val rawConfig = if (profile.protocolType == ProtocolType.MASTER_DNS) {
                        runCatching {
                            val parsed = Json.parseToJsonElement(profile.protocolRawConfig).jsonObject.toMutableMap()
                            parsed["LISTEN_PORT"] = JsonPrimitive(localRelayPort)
                            parsed["FECDataShards"] = JsonPrimitive(0)
                            parsed["FECParityShards"] = JsonPrimitive(0)
                            parsed["FEC_DATA_SHARDS"] = JsonPrimitive(0)
                            parsed["FEC_PARITY_SHARDS"] = JsonPrimitive(0)
                            parsed["BASE_ENCODE_DATA"] = JsonPrimitive(false)
                            parsed["DATA_ENCRYPTION_METHOD"] = JsonPrimitive(1)
                            parsed["PACKET_DUPLICATION_COUNT"] = JsonPrimitive(2)
                            parsed["SETUP_PACKET_DUPLICATION_COUNT"] = JsonPrimitive(3)
                            parsed["AUTO_DISABLE_TIMEOUT_SERVERS"] = JsonPrimitive(true)
                            parsed["AUTO_REMOVE_LOW_MTU_SERVERS"] = JsonPrimitive(true)
                            parsed["RECHECK_INACTIVE_SERVERS_ENABLED"] = JsonPrimitive(true)
                            parsed["RESOLVER_BALANCING_STRATEGY"] = JsonPrimitive(3)
                            parsed["STREAM_RESOLVER_FAILOVER_RESEND_THRESHOLD"] = JsonPrimitive(2)
                            parsed["STREAM_RESOLVER_FAILOVER_COOLDOWN"] = JsonPrimitive(2.0)
                            parsed["PING_AGGRESSIVE_INTERVAL_SECONDS"] = JsonPrimitive(0.050)
                            parsed["PING_LAZY_INTERVAL_SECONDS"] = JsonPrimitive(0.750)
                            parsed["ARQ_INITIAL_RTO_SECONDS"] = JsonPrimitive(0.4)
                            parsed["MTU_TEST_RETRIES"] = JsonPrimitive(2)
                            parsed["MTU_TEST_TIMEOUT"] = JsonPrimitive(2.0)
                            parsed["MTU_TEST_PARALLELISM"] = JsonPrimitive(16)
                            parsed["LOG_LEVEL"] = JsonPrimitive("WARN")

                            val parsedResolvers = runCatching {
                                parsed["RESOLVERS"]?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull }
                            }.getOrNull() ?: emptyList()
                            val cleanResolvers = (parsedResolvers.filter { !it.contains("77.88.8.") } + defaultResolvers).distinct()
                            parsed["RESOLVERS"] = JsonArray(cleanResolvers.map { JsonPrimitive(it) })

                            JsonObject(parsed).toString()
                        }.getOrDefault(profile.protocolRawConfig)
                    } else {
                        profile.protocolRawConfig
                    }
                    driverConfigFile.writeText(rawConfig)

                    val driverPb = ProcessBuilder(
                        nullvpBinary.absolutePath,
                        "-socks-port", settings.socksPort.toString(),
                        "-http-port", settings.httpPort.toString(),
                        "-local-port", localRelayPort.toString(),
                        "-c", driverConfigFile.absolutePath,
                        "-proto", profile.protocolType.name
                    )
                    driverPb.redirectErrorStream(true)
                    val cProc = driverPb.start()
                    companionProcess = cProc

                    companionLogJob?.cancel()
                    companionLogJob = scope.launch(Dispatchers.IO) {
                        try {
                            BufferedReader(InputStreamReader(cProc.inputStream)).use { reader ->
                                var line: String? = null
                                while (reader.readLine().also { line = it } != null) {
                                    line?.let { l ->
                                        appendLog("[CORE] $l")
                                        val lvl = if (l.contains("error", true) || l.contains("failed", true)) LogLevel.ERROR
                                        else if (l.contains("warning", true)) LogLevel.WARN
                                        else LogLevel.INFO
                                        AppStateStore.addLog(lvl, "NullvpCore", l)

                                        if (l.contains("[CAPTCHA_REQUIRED]")) {
                                            val url = l.substringAfter("[CAPTCHA_REQUIRED]").trim()
                                            _currentCaptchaUrl.value = url
                                            AppStateStore.addLog(LogLevel.WARN, "VK_TURN", "Требуется решение капчи: $url")
                                        }
                                    }
                                }
                            }
                        } catch (_: Exception) {}
                    }

                    if (profile.protocolType == ProtocolType.MASTER_DNS) {
                        appendLog("[MDNS] Ожидание MTU-теста и запуска локального порта :$localRelayPort...")
                        var mdnsReady = false
                        for (attempt in 0 until 180) {
                            if (!isActive || !cProc.isAlive) break
                            val canConnect = runCatching {
                                java.net.Socket().use { s ->
                                    s.connect(java.net.InetSocketAddress("127.0.0.1", localRelayPort), 150)
                                    true
                                }
                            }.getOrDefault(false)
                            if (canConnect) {
                                mdnsReady = true
                                break
                            }
                            delay(200)
                        }
                        if (!mdnsReady && isActive) {
                            val err = "DNS-туннель не смог пройти тест MTU (сервер не ответил)"
                            _isRunning.value = false
                            _isConnecting.value = false
                            _connectionError.value = err
                            AppStateStore.addLog(LogLevel.ERROR, "MDNS", err)
                            cProc.destroyForcibly()
                            return@launch
                        }
                    } else {
                        delay(400)
                    }
                }

                if (!isActive) return@launch

                // 2. Launch Xray Process
                val assetDir = findAssetDir()
                val pb = ProcessBuilder(xrayBinary.absolutePath, "run", "-c", configFile.absolutePath)
                if (assetDir != null) {
                    pb.environment()["XRAY_LOCATION_ASSET"] = assetDir.absolutePath
                }
                pb.redirectErrorStream(true)

                val proc = pb.start()
                coreProcess = proc

                // Read live output from process in background
                logJob?.cancel()
                logJob = scope.launch(Dispatchers.IO) {
                    try {
                        BufferedReader(InputStreamReader(proc.inputStream)).use { reader ->
                            var line: String? = null
                            while (reader.readLine().also { line = it } != null) {
                                line?.let { l ->
                                    appendLog("[CORE] $l")
                                    val lvl = if (l.contains("error", true) || l.contains("failed", true)) LogLevel.ERROR
                                    else if (l.contains("warning", true)) LogLevel.WARN
                                    else LogLevel.INFO
                                    AppStateStore.addLog(lvl, "XrayCore", l)

                                    if (l.contains("Failed to start:") || l.contains("fatal error")) {
                                        _connectionError.value = l.substringAfter("Failed to start:").trim()
                                    }
                                }
                            }
                        }
                    } catch (_: Exception) {}
                }

                // Check if process stayed alive after 300ms
                delay(300)
                if (!proc.isAlive) {
                    val exit = proc.exitValue()
                    val err = _connectionError.value ?: "Процесс ядра завершился сразу после старта (код $exit)"
                    _isRunning.value = false
                    _isConnecting.value = false
                    _connectionError.value = err
                    AppStateStore.addLog(LogLevel.ERROR, "CoreEngine", err)
                    return@launch
                }

                _isRunning.value = true
                isRunningState = true
                activeProfile = profile
                _isConnecting.value = false

                // 3. Handle System Routing Mode
                when (settings.operationMode) {
                    OperationMode.SYSTEM_PROXY -> {
                        DesktopPrivilegeManager.enableSystemProxy(settings.socksPort, settings.httpPort)
                        appendLog("[PROXY] System proxy enabled on 127.0.0.1:${settings.socksPort} (SOCKS) / ${settings.httpPort} (HTTP)")
                        AppStateStore.addLog(LogLevel.INFO, "Routing", "OS System Proxy active on 127.0.0.1:${settings.socksPort}")
                    }
                    OperationMode.TUN -> {
                        startTunDevice(settings.socksPort)
                    }
                    OperationMode.PROXY_ONLY -> {
                        appendLog("[PROXY] Local proxy ports open: SOCKS5=${settings.socksPort}, HTTP=${settings.httpPort}")
                        AppStateStore.addLog(LogLevel.INFO, "Routing", "Manual Proxy ports open: SOCKS5=${settings.socksPort}, HTTP=${settings.httpPort}")
                    }
                }

                // 4. Start Real Traffic Stats Poller
                startStatsPolling(settings.operationMode)
            } catch (e: Exception) {
                _isRunning.value = false
                _isConnecting.value = false
                _connectionError.value = e.message ?: "Неизвестная ошибка запуска"
                AppStateStore.addLog(LogLevel.ERROR, "CoreEngine", "Error starting engine: ${e.message}")
            }
        }
        return Result.success(Unit)
    }

    private fun startTunDevice(socksPort: Int) {
        val tunBinary = findBinary("tun2socks")
        if (tunBinary == null) {
            appendLog("[WARN] tun2socks binary not found, falling back to System Proxy mode")
            DesktopPrivilegeManager.enableSystemProxy(socksPort = socksPort)
            return
        }

        scope.launch(Dispatchers.IO) {
            try {
                appendLog("[TUN] Starting tun2socks on device $TUN_DEVICE...")
                AppStateStore.addLog(LogLevel.INFO, "TUN", "Initializing virtual interface $TUN_DEVICE via tun2socks")

                val tunPb = ProcessBuilder(
                    tunBinary.absolutePath,
                    "-device", TUN_DEVICE,
                    "-proxy", "socks5://127.0.0.1:$socksPort",
                    "-loglevel", "info"
                )
                tunPb.redirectErrorStream(true)
                val p = tunPb.start()
                tunProcess = p

                delay(800)

                // Configure interface via ip command (supports cached session sudo or direct cap_net_admin)
                runCatching {
                    val pass = DesktopPrivilegeManager.cachedSudoPassword
                    if (!pass.isNullOrBlank()) {
                        val pb1 = ProcessBuilder("sudo", "-S", "ip", "addr", "add", "198.18.0.1/15", "dev", TUN_DEVICE)
                        val p1 = pb1.start()
                        p1.outputStream.bufferedWriter().use { it.write(pass); it.newLine(); it.flush() }
                        p1.waitFor()

                        val pb2 = ProcessBuilder("sudo", "-S", "ip", "link", "set", "dev", TUN_DEVICE, "up")
                        val p2 = pb2.start()
                        p2.outputStream.bufferedWriter().use { it.write(pass); it.newLine(); it.flush() }
                        p2.waitFor()
                    } else {
                        ProcessBuilder("ip", "addr", "add", "198.18.0.1/15", "dev", TUN_DEVICE).start().waitFor()
                        ProcessBuilder("ip", "link", "set", "dev", TUN_DEVICE, "up").start().waitFor()
                    }
                }

                BufferedReader(InputStreamReader(p.inputStream)).use { reader ->
                    var line: String? = null
                    while (p.isAlive && reader.readLine().also { line = it } != null) {
                        line?.let { l ->
                            appendLog("[TUN2SOCKS] $l")
                            AppStateStore.addLog(LogLevel.DEBUG, "tun2socks", l)
                        }
                    }
                }
            } catch (e: Exception) {
                appendLog("[ERROR] Failed to start TUN: ${e.message}")
                AppStateStore.addLog(LogLevel.ERROR, "TUN", "Error: ${e.message}")
            }
        }
    }

    private fun startStatsPolling(mode: OperationMode) {
        statsJob?.cancel()
        statsJob = scope.launch(Dispatchers.IO) {
            var prevRx = 0L
            var prevTx = 0L
            val xrayBin = findBinary("xray")

            while (isActive && _isRunning.value) {
                delay(1000)

                var rxBytes = 0L
                var txBytes = 0L

                // 1. In TUN mode, check virtual adapter stats
                if (mode == OperationMode.TUN) {
                    val rxFile = File("/sys/class/net/$TUN_DEVICE/statistics/rx_bytes")
                    val txFile = File("/sys/class/net/$TUN_DEVICE/statistics/tx_bytes")
                    if (rxFile.exists() && txFile.exists()) {
                        rxBytes = rxFile.readText().trim().toLongOrNull() ?: 0L
                        txBytes = txFile.readText().trim().toLongOrNull() ?: 0L
                    }
                }

                // 2. Query Xray Stats API (works accurately for Proxy, System Proxy, and TUN modes)
                if (xrayBin != null && coreProcess?.isAlive == true) {
                    runCatching {
                        val pb = ProcessBuilder(xrayBin.absolutePath, "api", "statsquery", "--server=127.0.0.1:10085")
                        val proc = pb.start()
                        val output = proc.inputStream.bufferedReader().use { it.readText() }
                        proc.waitFor(400, java.util.concurrent.TimeUnit.MILLISECONDS)
                        if (output.isNotBlank()) {
                            val parsed = Json.parseToJsonElement(output).jsonObject
                            val statArray = parsed["stat"]?.jsonArray
                            var xrayRx = 0L
                            var xrayTx = 0L
                            statArray?.forEach { elem ->
                                val obj = elem.jsonObject
                                val name = obj["name"]?.jsonPrimitive?.contentOrNull ?: ""
                                val value = obj["value"]?.jsonPrimitive?.longOrNull ?: 0L
                                if (name.contains("traffic>>>downlink")) {
                                    if (name.contains("socks-in") || name.contains("http-in") || name.contains("proxy")) {
                                        xrayRx = maxOf(xrayRx, value)
                                    }
                                } else if (name.contains("traffic>>>uplink")) {
                                    if (name.contains("socks-in") || name.contains("http-in") || name.contains("proxy")) {
                                        xrayTx = maxOf(xrayTx, value)
                                    }
                                }
                            }
                            if (xrayRx > 0L || xrayTx > 0L) {
                                rxBytes = maxOf(rxBytes, xrayRx)
                                txBytes = maxOf(txBytes, xrayTx)
                            }
                        }
                    }
                }

                val rxSpeed = if (prevRx > 0 && rxBytes >= prevRx) rxBytes - prevRx else 0L
                val txSpeed = if (prevTx > 0 && txBytes >= prevTx) txBytes - prevTx else 0L
                prevRx = rxBytes
                prevTx = txBytes

                _stats.value = TrafficStats(
                    rxBytes = rxBytes,
                    txBytes = txBytes,
                    rxSpeedBps = rxSpeed,
                    txSpeedBps = txSpeed,
                    activeStreams = if (rxSpeed > 0 || txSpeed > 0) 4 else 1,
                    isConnected = true
                )
            }
        }
    }

    override fun stop(): Result<Unit> {
        _isConnecting.value = false
        _isRunning.value = false
        isRunningState = false
        activeProfile = null
        _connectionError.value = null
        _stats.value = TrafficStats(isConnected = false)

        startJob?.cancel()
        startJob = null
        statsJob?.cancel()
        statsJob = null
        logJob?.cancel()
        logJob = null
        companionLogJob?.cancel()
        companionLogJob = null

        val cp = coreProcess
        val comp = companionProcess
        val tp = tunProcess
        coreProcess = null
        companionProcess = null
        tunProcess = null

        scope.launch(Dispatchers.IO) {
            try {
                appendLog("[STOP] Stopping core engine and resetting routes")
                AppStateStore.addLog(LogLevel.INFO, "CoreEngine", "Stopping core engine and releasing resources")

                // Kill processes first so ports release immediately
                runCatching { cp?.destroyForcibly() }
                runCatching { comp?.destroyForcibly() }
                runCatching { tp?.destroyForcibly() }
                killExistingZombieProcesses()

                // Disable system proxy
                DesktopPrivilegeManager.disableSystemProxy()

                appendLog("[STOP] Stopped successfully")
                AppStateStore.addLog(LogLevel.INFO, "CoreEngine", "Engine stopped.")
            } catch (_: Exception) {}
        }
        return Result.success(Unit)
    }

    override fun submitCaptcha(token: String) {
        appendLog("[CAPTCHA] Token submitted: ${token.take(8)}...")
        scope.launch(Dispatchers.IO) {
            listOfNotNull(companionProcess, coreProcess).forEach { p ->
                p.outputStream?.let { os ->
                    runCatching {
                        os.write("CAPTCHA $token\n".toByteArray())
                        os.flush()
                    }
                }
            }
        }
        _currentCaptchaUrl.value = null
    }

    private fun appendLog(msg: String) {
        val current = _logs.value.toMutableList()
        if (current.size > 500) current.removeAt(0)
        current.add(msg)
        _logs.value = current
    }
}

private val desktopEngineInstance by lazy { DesktopCoreEngine() }

actual fun getPlatformCoreEngine(): CoreEngine = desktopEngineInstance
