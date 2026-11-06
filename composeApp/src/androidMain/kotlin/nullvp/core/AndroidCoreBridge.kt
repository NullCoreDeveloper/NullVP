package nullvp.core

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import nullvp.domain.model.AppSettings
import nullvp.domain.model.Profile
import nullvp.domain.model.TrafficStats

object AndroidCoreBridge {
    private val _isRunning = MutableStateFlow(false)
    val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()

    private val _isConnecting = MutableStateFlow(false)
    val isConnecting: StateFlow<Boolean> = _isConnecting.asStateFlow()

    private val _connectionError = MutableStateFlow<String?>(null)
    val connectionError: StateFlow<String?> = _connectionError.asStateFlow()

    private val _stats = MutableStateFlow(TrafficStats())
    val stats: StateFlow<TrafficStats> = _stats.asStateFlow()

    private val _logs = MutableStateFlow<List<String>>(listOf("[INIT] NullVP Android Core Ready"))
    val logs: StateFlow<List<String>> = _logs.asStateFlow()

    private val _currentCaptchaUrl = MutableStateFlow<String?>(null)
    val currentCaptchaUrl: StateFlow<String?> = _currentCaptchaUrl.asStateFlow()

    var activeProfile: Profile? = null
    var appSettings: AppSettings = AppSettings()

    fun addLog(msg: String) {
        val current = _logs.value.toMutableList()
        current.add(msg)
        if (current.size > 2000) {
            current.removeAt(0)
        }
        _logs.value = current
    }

    fun setRunning(running: Boolean) {
        _isRunning.value = running
        if (running) {
            _isConnecting.value = false
            _connectionError.value = null
        }
    }

    fun setConnecting(connecting: Boolean) {
        _isConnecting.value = connecting
    }

    fun setError(error: String?) {
        _connectionError.value = error
        if (error != null) {
            _isRunning.value = false
            _isConnecting.value = false
            addLog("[ERROR] $error")
        }
    }

    fun updateStats(rxBytes: Long, txBytes: Long, rxSpeed: Long, txSpeed: Long) {
        _stats.value = TrafficStats(
            rxBytes = rxBytes,
            txBytes = txBytes,
            rxSpeedBps = rxSpeed,
            txSpeedBps = txSpeed,
            isConnected = _isRunning.value
        )
    }

    fun setCaptchaUrl(url: String?) {
        _currentCaptchaUrl.value = url
        if (url != null) {
            addLog("[CAPTCHA] Action required: $url")
        }
    }

    fun submitCaptchaToken(token: String) {
        _currentCaptchaUrl.value = null
        try {
            libv2ray.Libv2ray.submitCaptchaToken(token)
            addLog("[CAPTCHA] Token submitted")
        } catch (e: Exception) {
            addLog("[CAPTCHA] Error submitting token: ${e.message}")
        }
    }
}

