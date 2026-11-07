package nullvp.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.provider.Settings
import android.util.Base64
import androidx.core.app.NotificationCompat
import com.v2ray.ang.service.TProxyService
import go.Seq
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import libv2ray.CoreCallbackHandler
import libv2ray.CoreController
import libv2ray.Libv2ray
import nullvp.MainActivity
import nullvp.core.AndroidCoreBridge
import nullvp.core.RealXrayConfigBuilder
import nullvp.domain.model.Profile
import nullvp.domain.model.ProtocolType
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.atomic.AtomicBoolean

class NullVpnService : VpnService() {

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var vpnInterface: ParcelFileDescriptor? = null
    private var tproxyService: TProxyService? = null
    private var coreController: CoreController? = null
    private var statsJob: Job? = null
    private var startJob: Job? = null

    private val isStopping = AtomicBoolean(false)
    private var sleptMillis = 0L

    private var totalRx = 0L
    private var totalTx = 0L

    // Screen-on broadcast receiver to detect sleep gaps > 60s
    private val screenOnReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val slept = SystemClock.elapsedRealtime() - SystemClock.uptimeMillis()
            val gap = slept - sleptMillis
            sleptMillis = slept
            if (gap >= DEEP_SLEEP_KICK_MS) {
                AndroidCoreBridge.addLog("[VPN] Wake kick from deep sleep (${gap}ms)")
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        registerReceiver(screenOnReceiver, IntentFilter(Intent.ACTION_SCREEN_ON))
        initCoreEnv()
    }

    private fun initCoreEnv() {
        runCatching {
            Seq.setContext(applicationContext)

            val assetDir = File(
                getExternalFilesDir("assets")?.absolutePath
                    ?: getDir("assets", Context.MODE_PRIVATE).absolutePath
            )
            if (!assetDir.exists()) assetDir.mkdirs()

            // Copy Geo assets from APK assets if missing
            val geoFiles = listOf("geosite.dat", "geoip.dat", "geoip-only-cn-private.dat")
            geoFiles.forEach { fileName ->
                val target = File(assetDir, fileName)
                if (!target.exists() || target.length() == 0L) {
                    runCatching {
                        assets.open(fileName).use { input ->
                            FileOutputStream(target).use { output ->
                                input.copyTo(output)
                            }
                        }
                        AndroidCoreBridge.addLog("[ASSET] Extracted $fileName to storage")
                    }
                }
            }

            val deviceId = try {
                val aid = Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID) ?: "nullvp_device"
                Base64.encodeToString(aid.toByteArray(Charsets.UTF_8).copyOf(32), Base64.NO_PADDING or Base64.URL_SAFE)
            } catch (_: Exception) {
                "nullvp_device_key_0000000000000000"
            }

            Libv2ray.initCoreEnv(assetDir.absolutePath, deviceId)

            Libv2ray.setCaptchaHandler(object : libv2ray.CaptchaHandler {
                override fun openCaptcha(url: String?) {
                    if (!url.isNullOrBlank()) {
                        AndroidCoreBridge.setCaptchaUrl(url)
                    }
                }
            })
            AndroidCoreBridge.addLog("[CORE] Native environment initialized")
        }.onFailure {
            AndroidCoreBridge.addLog("[CORE] Failed to init native env: ${it.message}")
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action ?: return START_NOT_STICKY

        when (action) {
            ACTION_START -> {
                val profile = AndroidCoreBridge.activeProfile
                if (profile == null) {
                    AndroidCoreBridge.setError("No active server profile selected")
                    stopSelf()
                    return START_NOT_STICKY
                }
                startVpn(profile)
            }
            ACTION_STOP -> {
                stopVpn()
            }
        }
        return START_NOT_STICKY
    }

    private fun startVpn(profile: Profile) {
        startJob?.cancel()
        startJob = scope.launch {
            try {
                // If core is already running, gracefully tear it down first to reload new rules/profile
                stopCoreAndTProxy()

                isStopping.set(false)
                val settings = AndroidCoreBridge.appSettings
                val isTunMode = settings.operationMode != nullvp.domain.model.OperationMode.PROXY_ONLY

                val notifTitle = if (!isTunMode) {
                    "NullVP • Прокси активен (:${settings.socksPort})"
                } else {
                    "NullVP • VPN активен"
                }

                val notification = createNotification(notifTitle, "Подключен: ${profile.name}")
                if (Build.VERSION.SDK_INT >= 34) {
                    startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
                } else {
                    startForeground(NOTIFICATION_ID, notification)
                }

                if (isTunMode) {
                    // 1. Configure and establish Android VPN Tun Interface
                    val builder = Builder()
                        .setSession("NullVP")
                        .setMtu(settings.tunMtu)
                        .addAddress("10.10.10.2", 30)
                        .addRoute("0.0.0.0", 0)

                    // Add DNS
                    val dns = if (settings.directDns.isNotBlank()) settings.directDns else "1.1.1.1"
                    if (dns.matches(Regex("\\d+\\.\\d+\\.\\d+\\.\\d+"))) {
                        builder.addDnsServer(dns)
                    } else {
                        builder.addDnsServer("1.1.1.1")
                    }
                    builder.addDnsServer("8.8.8.8")

                    // Configure IPv6 if enabled
                    if (settings.enableIpv6) {
                        runCatching {
                            builder.addAddress("fc00::2", 126)
                            builder.addRoute("::", 0)
                        }
                    }

                    // 2. Split Tunneling & Dynamic App Scanner (v2whitelist logic)
                    applySplitTunneling(builder)

                    // Close previous interface if open
                    vpnInterface?.close()
                    val pfd = builder.establish() ?: error("Failed to establish VPN interface")
                    vpnInterface = pfd
                } else {
                    vpnInterface?.close()
                    vpnInterface = null
                    AndroidCoreBridge.addLog("[MODE] Режим только прокси: системный TUN интерфейс НЕ создается")
                }

                // 3. Handle Special Drivers (VK_TURN / MASTER_DNS)
                var localRelayPort = 10815
                if (profile.protocolType == ProtocolType.VK_TURN) {
                    localRelayPort = (20000..50000).random()
                    val raw = profile.protocolRawConfig
                    val updatedJson = runCatching {
                        val parsed = Json.parseToJsonElement(raw).jsonObject.toMutableMap()
                        parsed["localPort"] = JsonPrimitive(localRelayPort)
                        JsonObject(parsed).toString()
                    }.getOrDefault(raw)

                    val b64 = Base64.encodeToString(updatedJson.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
                    val filesDir = applicationContext.filesDir.absolutePath
                    AndroidCoreBridge.addLog("[VKTURN] Starting internal VK TURN engine on :$localRelayPort")
                    Libv2ray.startVkTurnClient(b64, "", filesDir)
                    if (!Libv2ray.isVkTurnClientRunning()) {
                        error("VK TURN client failed to start")
                    }
                } else if (profile.protocolType == ProtocolType.MASTER_DNS) {
                    localRelayPort = (20000..50000).random()
                    val raw = profile.protocolRawConfig
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
                    val updatedJson = runCatching {
                        val parsed = Json.parseToJsonElement(raw).jsonObject.toMutableMap()
                        parsed["LISTEN_PORT"] = JsonPrimitive(localRelayPort)
                        // Disable broken Soft ARQ FEC 10+3 fragmentation so 1 packet = 1 DNS query with instant ACKs
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
                        parsed["PING_AGGRESSIVE_INTERVAL_SECONDS"] = JsonPrimitive(0.150)
                        parsed["PING_LAZY_INTERVAL_SECONDS"] = JsonPrimitive(0.750)
                        parsed["ARQ_INITIAL_RTO_SECONDS"] = JsonPrimitive(0.3)
                        parsed["MTU_TEST_RETRIES"] = JsonPrimitive(1)
                        parsed["MTU_TEST_TIMEOUT"] = JsonPrimitive(1.2)
                        parsed["MTU_TEST_PARALLELISM"] = JsonPrimitive(20)
                        parsed["LOG_LEVEL"] = JsonPrimitive("WARN")

                        val parsedResolvers = runCatching {
                            parsed["RESOLVERS"]?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull }
                        }.getOrNull() ?: emptyList()
                        val cleanResolvers = (parsedResolvers.filter { !it.contains("77.88.8.") } + defaultResolvers).distinct()
                        parsed["RESOLVERS"] = JsonArray(cleanResolvers.map { JsonPrimitive(it) })

                        JsonObject(parsed).toString()
                    }.getOrDefault(raw)

                    val b64 = Base64.encodeToString(updatedJson.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
                    val filesDir = applicationContext.filesDir.absolutePath
                    val resolversFile = File(applicationContext.filesDir, "client_resolvers.txt")
                    runCatching {
                        val existing = if (resolversFile.exists()) resolversFile.readLines().map { it.trim() }.filter { it.isNotEmpty() && !it.contains("77.88.8.") } else emptyList()
                        val merged = (existing + defaultResolvers).distinct()
                        resolversFile.writeText(merged.joinToString("\n") + "\n")
                    }
                    AndroidCoreBridge.addLog("[MDNS] Starting internal MDNS engine on :$localRelayPort")
                    Libv2ray.startMdnsClient(b64, "", filesDir)
                    if (!Libv2ray.isMdnsClientRunning()) {
                        error("MasterDnsVPN client failed to start")
                    }
                    // Wait until MasterDnsVPN finishes MTU probing and session initialization
                    AndroidCoreBridge.addLog("[MDNS] Ожидание MTU-теста и инициализации сессии...")
                    var mdnsReady = false
                    for (attempt in 0 until 180) {
                        if (!isActive || isStopping.get() || !Libv2ray.isMdnsClientRunning()) break
                        val sessionReady = runCatching { Libv2ray.isMdnsSessionReady() }.getOrDefault(false)
                        if (sessionReady) {
                            // Wait briefly for StartAsyncRuntime to bind the TCP listener after sessionReady=true
                            delay(80)
                            mdnsReady = true
                            break
                        }
                        delay(200)
                    }
                    if (!mdnsReady && !isStopping.get()) {
                        error("DNS-туннель не смог пройти тест MTU (сервер не ответил)")
                    }
                    AndroidCoreBridge.addLog("[MDNS] Сессия установлена, локальный порт :$localRelayPort готов!")
                }

                if (!isActive || isStopping.get()) return@launch

                // 4. Build Real Xray configuration
                val configContent = RealXrayConfigBuilder.buildJson(profile, settings, localRelayPort)
                AndroidCoreBridge.addLog("[XRAY] Config generated (${configContent.length} chars)")

                // 5. Start hev-socks5-tunnel JNI (only in TUN mode)
                if (isTunMode) {
                    val pfd = vpnInterface ?: error("Failed to acquire VPN interface for TUN mode")
                    tproxyService = TProxyService(applicationContext, pfd)
                    tproxyService?.startTun2Socks(
                        socksPort = settings.socksPort,
                        socksUsername = if (settings.proxyAuthEnabled) settings.proxyUsername else null,
                        socksPassword = if (settings.proxyAuthEnabled) settings.proxyPassword else null,
                        mtu = settings.tunMtu
                    )
                    AndroidCoreBridge.addLog("[TPROXY] hev-socks5-tunnel started on port ${settings.socksPort}")
                } else {
                    AndroidCoreBridge.addLog("[PROXY] Локальный прокси слушает 127.0.0.1:${settings.socksPort} (SOCKS5) и :${settings.httpPort} (HTTP)")
                }

                // 6. Start Core Controller
                val callback = object : CoreCallbackHandler {
                    override fun startup(): Long {
                        AndroidCoreBridge.addLog("[XRAY] Core loop active")
                        return 0
                    }

                    override fun shutdown(): Long {
                        AndroidCoreBridge.addLog("[XRAY] Core loop stopped")
                        return 0
                    }

                    override fun onEmitStatus(status: Long, msg: String?): Long {
                        if (!msg.isNullOrBlank()) {
                            AndroidCoreBridge.addLog("[CORE] $msg")
                        }
                        return 0
                    }
                }

                val controller = Libv2ray.newCoreController(callback)
                coreController = controller
                controller.startLoop(configContent, 0)

                AndroidCoreBridge.setRunning(true)
                AndroidCoreBridge.addLog("[STATE] Tunnel successfully established!")

                // 7. Start real-time traffic statistics poller
                startStatsPoller(controller)

            } catch (e: CancellationException) {
                // Cancelled by stopVpn
            } catch (e: Exception) {
                AndroidCoreBridge.setError("Failed to start VPN: ${e.message}")
                stopVpn()
            }
        }
    }

    private fun applySplitTunneling(builder: Builder) {
        val settings = AndroidCoreBridge.appSettings
        val selfPackage = packageName

        val pm = packageManager
        val installed = runCatching { pm.getInstalledPackages(0) }.getOrDefault(emptyList())

        // 1. User Per-App Proxy Configuration
        val isPerApp = settings.perAppProxyEnabled
        val isBypassMode = settings.perAppBypassMode
        val selectedPackages = settings.perAppSelectedPackages.toSet()

        if (isPerApp && !isBypassMode) {
            // PROXY (WHITELIST) MODE: only selected apps go through VPN.
            // Note: Do NOT call addDisallowedApplication in this branch because Android VpnService.Builder
            // forbids mixing addAllowedApplication and addDisallowedApplication!
            // Since selfPackage is not added to allowed applications, NullVP is automatically bypassed.
            selectedPackages.forEach { pkg ->
                if (pkg != selfPackage) {
                    runCatching { builder.addAllowedApplication(pkg) }
                }
            }
            // CRITICAL REQUIREMENT: Force 4PDA through VPN even in proxy mode if present!
            installed.forEach { pkg ->
                val pName = pkg.packageName.lowercase()
                if (pName.contains("4pda") || pName.contains("fourpda")) {
                    runCatching { builder.addAllowedApplication(pkg.packageName) }
                }
            }
            return
        }

        // Always disallow VPN's own package in normal/bypass mode to prevent routing loops
        runCatching { builder.addDisallowedApplication(selfPackage) }

        // BYPASS MODE:
        if (isPerApp && isBypassMode) {
            selectedPackages.forEach { pkg ->
                if (pkg != selfPackage) {
                    runCatching { builder.addDisallowedApplication(pkg) }
                }
            }
        }

        // 2. Dynamic Russian Apps Scanner (from v2whitelist)
        // Manual configuration has higher priority over dynamic:
        // If user enabled Per-App Proxy, manual selection defines which apps are bypassed!
        // If Per-App Proxy is disabled, dynamic scanner acts automatically on RU services.
        if (settings.dynamicRuAppScannerEnabled && !isPerApp) {
            installed.forEach { pkg ->
                val pName = pkg.packageName.lowercase()

                // CRITICAL REQUIREMENT:
                // 4PDA starts with ru.fourpda.client, which previously got bypassed by the broad "ru." rule.
                // We MUST EXCLUDE 4PDA from the bypass scanner so it ALWAYS routes through VPN!
                if (pName.contains("4pda") || pName.contains("fourpda")) {
                    return@forEach // SKIP: Do not disallow 4PDA!
                }

                val isBroadRu = pName.startsWith("ru.")
                if (isBroadRu ||
                    pName.contains("yandex") ||
                    pName.contains("tinkoff") ||
                    pName.contains("tbank") ||
                    pName.contains("sberbank") ||
                    pName.contains("sber") ||
                    pName.contains("vkontakte") ||
                    pName.contains("vk.store") ||
                    pName.contains("rustore") ||
                    pName.contains("ozon") ||
                    pName.contains("wildberries") ||
                    pName.contains("avito") ||
                    pName.contains("alfabank") ||
                    pName.contains("mail.ru") ||
                    pName.contains("kinopoisk") ||
                    pName.contains("rostel") ||
                    pName.contains("vtb") ||
                    pName.contains("gosuslugi") ||
                    pName.contains("mirpay") ||
                    pName.contains("nspk") ||
                    pName.contains("gazprombank") ||
                    pName.contains("raiffeisen") ||
                    pName.contains("sovcombank") ||
                    pName.contains("openbank") ||
                    pName.contains("pochtabank") ||
                    pName.contains("megafon") ||
                    pName.contains("mts.ru") ||
                    pName.contains("beeline") ||
                    pName.contains("tele2")
                ) {
                    runCatching { builder.addDisallowedApplication(pkg.packageName) }
                }
            }
        }
    }

    private fun startStatsPoller(controller: CoreController) {
        statsJob?.cancel()
        statsJob = scope.launch {
            while (isActive) {
                delay(1000)
                try {
                    val down = controller.queryStats("proxy", "downlink")
                    val up = controller.queryStats("proxy", "uplink")
                    totalRx += down
                    totalTx += up
                    AndroidCoreBridge.updateStats(
                        rxBytes = totalRx,
                        txBytes = totalTx,
                        rxSpeed = down,
                        txSpeed = up
                    )
                } catch (_: Exception) {}
            }
        }
    }

    private fun stopCoreAndTProxy() {
        statsJob?.cancel()
        statsJob = null

        val tp = tproxyService
        tproxyService = null
        try {
            tp?.stopTun2Socks()
        } catch (_: Exception) {}

        val cc = coreController
        coreController = null
        try {
            cc?.stopLoop()
        } catch (_: Exception) {}

        runCatching {
            if (Libv2ray.isVkTurnClientRunning()) {
                Libv2ray.stopVkTurnClient()
            }
        }
        runCatching {
            if (Libv2ray.isMdnsClientRunning()) {
                Libv2ray.stopMdnsClient()
            }
        }
    }

    private fun stopVpn() {
        if (isStopping.compareAndSet(false, true)) {
            startJob?.cancel()
            startJob = null
            statsJob?.cancel()
            statsJob = null
            AndroidCoreBridge.setRunning(false)
            runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }

            val pfd = vpnInterface
            vpnInterface = null

            Thread {
                runCatching { stopCoreAndTProxy() }
                runCatching { pfd?.close() }
                runCatching { stopSelf() }
            }.start()
        }
    }

    override fun onRevoke() {
        super.onRevoke()
        stopVpn()
    }

    override fun onDestroy() {
        super.onDestroy()
        runCatching { unregisterReceiver(screenOnReceiver) }
        startJob?.cancel()
        statsJob?.cancel()
        scope.cancel()
        AndroidCoreBridge.setRunning(false)
        val pfd = vpnInterface
        vpnInterface = null
        val tp = tproxyService
        tproxyService = null
        val cc = coreController
        coreController = null
        Thread {
            runCatching { tp?.stopTun2Socks() }
            runCatching { cc?.stopLoop() }
            runCatching {
                if (Libv2ray.isVkTurnClientRunning()) Libv2ray.stopVkTurnClient()
            }
            runCatching {
                if (Libv2ray.isMdnsClientRunning()) Libv2ray.stopMdnsClient()
            }
            runCatching { pfd?.close() }
        }.start()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "NullVP Active Tunnel",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows notification when VPN is active"
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }
    }

    private fun createNotification(title: String, text: String): Notification {
        val openIntent = Intent(this, MainActivity::class.java)
        val openPendingIntent = PendingIntent.getActivity(
            this, 0, openIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        // Кнопка "Стоп" в уведомлении
        val stopIntent = Intent(this, NullVpnService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPendingIntent = PendingIntent.getService(
            this, 1, stopIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .setContentIntent(openPendingIntent)
            .setOngoing(true)
            .setSilent(true)
            .addAction(android.R.drawable.ic_delete, "⏹ Стоп", stopPendingIntent)
            .build()
    }

    companion object {
        const val ACTION_START = "nullvp.service.START"
        const val ACTION_STOP = "nullvp.service.STOP"
        private const val CHANNEL_ID = "nullvp_channel_v2"
        private const val NOTIFICATION_ID = 1001
        private const val DEEP_SLEEP_KICK_MS = 60_000L
    }
}
