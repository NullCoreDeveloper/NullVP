package nullvp.core

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import nullvp.domain.model.Profile
import nullvp.domain.model.TrafficStats
import nullvp.platform.DesktopPrivilegeManager
import nullvp.platform.RoutingMode

class DesktopCoreEngine : CoreEngine {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val _isRunning = MutableStateFlow(false)
    override val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()

    private val _stats = MutableStateFlow(TrafficStats())
    override val stats: StateFlow<TrafficStats> = _stats.asStateFlow()

    private val _logs = MutableStateFlow<List<String>>(listOf("[INIT] Desktop Core Engine Ready"))
    override val logs: StateFlow<List<String>> = _logs.asStateFlow()

    private val _currentCaptchaUrl = MutableStateFlow<String?>(null)
    override val currentCaptchaUrl: StateFlow<String?> = _currentCaptchaUrl.asStateFlow()

    private var pollJob: Job? = null

    override fun start(profile: Profile): Result<Unit> {
        return runCatching {
            appendLog("[START] Starting profile '${profile.name}' (${profile.protocolType})")
            _isRunning.value = true

            if (DesktopPrivilegeManager.currentRoutingMode == RoutingMode.SYSTEM_PROXY) {
                DesktopPrivilegeManager.enableSystemProxy()
                appendLog("[NETWORK] System proxy enabled on 127.0.0.1:10808 (Zero Root Mode)")
            }

            // Start simulated stats polling
            pollJob?.cancel()
            pollJob = scope.launch {
                var rx = 0L
                var tx = 0L
                while (isActive && _isRunning.value) {
                    delay(1000)
                    val rxInc = (500_000L..2_500_000L).random()
                    val txInc = (100_000L..500_000L).random()
                    rx += rxInc
                    tx += txInc
                    _stats.value = TrafficStats(
                        rxBytes = rx,
                        txBytes = tx,
                        rxSpeedBps = rxInc,
                        txSpeedBps = txInc,
                        activeStreams = (2..8).random(),
                        isConnected = true
                    )
                }
            }
        }
    }

    override fun stop(): Result<Unit> {
        return runCatching {
            appendLog("[STOP] Stopping core engine")
            _isRunning.value = false
            pollJob?.cancel()
            pollJob = null

            if (DesktopPrivilegeManager.currentRoutingMode == RoutingMode.SYSTEM_PROXY) {
                DesktopPrivilegeManager.disableSystemProxy()
                appendLog("[NETWORK] System proxy disabled")
            }

            _stats.value = TrafficStats(isConnected = false)
        }
    }

    override fun submitCaptcha(token: String) {
        appendLog("[CAPTCHA] Token submitted: ${token.take(8)}...")
        _currentCaptchaUrl.value = null
    }

    private fun appendLog(msg: String) {
        val current = _logs.value.toMutableList()
        if (current.size > 200) current.removeAt(0)
        current.add(msg)
        _logs.value = current
    }
}

private val desktopEngineInstance by lazy { DesktopCoreEngine() }

actual fun getPlatformCoreEngine(): CoreEngine = desktopEngineInstance

