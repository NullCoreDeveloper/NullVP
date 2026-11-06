package nullvp.core

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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
    private var tunProcess: Process? = null
    private var statsJob: Job? = null
    private var logJob: Job? = null

    companion object {
        private const val CONFIG_PATH = "/tmp/nullvp_active.json"
        private const val TUN_DEVICE = "nullvp0"
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
            ProcessBuilder("pkill", "-9", "-f", "nullvp-core.*$CONFIG_PATH").start().waitFor(500, TimeUnit.MILLISECONDS)
        }
    }

    override fun start(profile: Profile): Result<Unit> {
        _isConnecting.value = true
        _connectionError.value = null

        scope.launch(Dispatchers.IO) {
            try {
                killExistingZombieProcesses()

                val settings = AppStateStore.settings.value
                appendLog("[START] Preparing profile '${profile.name}' (${profile.protocolType})")
                AppStateStore.addLog(LogLevel.INFO, "CoreEngine", "Building configuration for '${profile.name}'")

                // 1. Build real JSON configuration
                val configContent = RealXrayConfigBuilder.buildJson(profile, settings)
                val configFile = File(CONFIG_PATH)
                configFile.writeText(configContent)
                appendLog("[CONFIG] Generated config at $CONFIG_PATH (${configContent.length} bytes)")

                // 2. Select appropriate binary
                val isCustomDriver = profile.protocolType == ProtocolType.MASTER_DNS || profile.protocolType == ProtocolType.VK_TURN
                val binaryName = if (isCustomDriver) "nullvp-core" else "xray"
                val binary = findBinary(binaryName) ?: findBinary("xray")
                if (binary == null) {
                    val err = "Executable binary '$binaryName' not found! Make sure ./bin/$binaryName exists."
                    appendLog("[ERROR] $err")
                    AppStateStore.addLog(LogLevel.ERROR, "CoreEngine", err)
                    _isConnecting.value = false
                    _connectionError.value = err
                    return@launch
                }

                // 3. Launch Core Process
                val assetDir = findAssetDir()
                val pb = if (isCustomDriver) {
                    ProcessBuilder(
                        binary.absolutePath,
                        "-socks-port", settings.socksPort.toString(),
                        "-http-port", settings.httpPort.toString(),
                        "-c", configFile.absolutePath,
                        "-proto", profile.protocolType.name
                    )
                } else {
                    ProcessBuilder(binary.absolutePath, "run", "-c", configFile.absolutePath)
                }

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
                                    AppStateStore.addLog(lvl, if (isCustomDriver) "NullvpCore" else "XrayCore", l)

                                    if (l.contains("[CAPTCHA_REQUIRED]")) {
                                        val url = l.substringAfter("[CAPTCHA_REQUIRED]").trim()
                                        _currentCaptchaUrl.value = url
                                        AppStateStore.addLog(LogLevel.WARN, "VK_TURN", "Требуется решение капчи: $url")
                                    }

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
                _isConnecting.value = false

                // 4. Handle System Routing Mode
                when (settings.operationMode) {
                    OperationMode.SYSTEM_PROXY -> {
                        DesktopPrivilegeManager.enableSystemProxy("127.0.0.1", settings.socksPort)
                        appendLog("[PROXY] System proxy enabled on 127.0.0.1:${settings.socksPort}")
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

                // 5. Start Real Traffic Stats Poller
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
            DesktopPrivilegeManager.enableSystemProxy("127.0.0.1", socksPort)
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

            while (isActive && _isRunning.value) {
                delay(1000)

                // Try reading real network interface bytes if in TUN mode
                val rxFile = File("/sys/class/net/$TUN_DEVICE/statistics/rx_bytes")
                val txFile = File("/sys/class/net/$TUN_DEVICE/statistics/tx_bytes")

                var rxBytes = 0L
                var txBytes = 0L

                if (mode == OperationMode.TUN && rxFile.exists() && txFile.exists()) {
                    rxBytes = rxFile.readText().trim().toLongOrNull() ?: 0L
                    txBytes = txFile.readText().trim().toLongOrNull() ?: 0L
                } else {
                    // In Proxy mode, measure active connection traffic from process io
                    val ioFile = coreProcess?.pid()?.let { pid -> File("/proc/$pid/io") }
                    if (ioFile?.exists() == true) {
                        runCatching {
                            ioFile.readLines().forEach { line ->
                                if (line.startsWith("read_bytes:")) {
                                    rxBytes = line.substringAfter(":").trim().toLongOrNull() ?: 0L
                                } else if (line.startsWith("write_bytes:")) {
                                    txBytes = line.substringAfter(":").trim().toLongOrNull() ?: 0L
                                }
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
        _connectionError.value = null

        scope.launch(Dispatchers.IO) {
            try {
                appendLog("[STOP] Stopping core engine and resetting routes")
                AppStateStore.addLog(LogLevel.INFO, "CoreEngine", "Stopping core engine and releasing resources")

                statsJob?.cancel()
                statsJob = null
                logJob?.cancel()
                logJob = null

                // Disable system proxy
                DesktopPrivilegeManager.disableSystemProxy()

                // Kill processes
                coreProcess?.destroy()
                tunProcess?.destroy()
                killExistingZombieProcesses()

                coreProcess = null
                tunProcess = null

                _stats.value = TrafficStats(isConnected = false)
                appendLog("[STOP] Stopped successfully")
                AppStateStore.addLog(LogLevel.INFO, "CoreEngine", "Engine stopped.")
            } catch (_: Exception) {}
        }
        return Result.success(Unit)
    }

    override fun submitCaptcha(token: String) {
        appendLog("[CAPTCHA] Token submitted: ${token.take(8)}...")
        scope.launch(Dispatchers.IO) {
            coreProcess?.outputStream?.let { os ->
                runCatching {
                    os.write("CAPTCHA $token\n".toByteArray())
                    os.flush()
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
