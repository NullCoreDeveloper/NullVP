package nullvp.core

import android.content.Context
import android.content.Intent
import android.net.VpnService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import nullvp.domain.model.Profile
import nullvp.domain.model.TrafficStats
import nullvp.service.NullVpnService

class AndroidCoreEngine(private var appContext: Context? = null) : CoreEngine {

    private val _isRunning = MutableStateFlow(false)
    override val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()

    private val _stats = MutableStateFlow(TrafficStats())
    override val stats: StateFlow<TrafficStats> = _stats.asStateFlow()

    private val _logs = MutableStateFlow<List<String>>(listOf("[INIT] Android VPN Engine Initialized"))
    override val logs: StateFlow<List<String>> = _logs.asStateFlow()

    private val _currentCaptchaUrl = MutableStateFlow<String?>(null)
    override val currentCaptchaUrl: StateFlow<String?> = _currentCaptchaUrl.asStateFlow()

    fun setContext(context: Context) {
        this.appContext = context.applicationContext
    }

    override fun start(profile: Profile): Result<Unit> {
        val ctx = appContext ?: return Result.failure(IllegalStateException("Context not initialized"))

        val prepareIntent = VpnService.prepare(ctx)
        if (prepareIntent != null) {
            // Need user consent via activity
            return Result.failure(SecurityException("VPN consent needed"))
        }

        val serviceIntent = Intent(ctx, NullVpnService::class.java).apply {
            action = NullVpnService.ACTION_START
            putExtra(NullVpnService.EXTRA_PROFILE_NAME, profile.name)
        }
        ctx.startService(serviceIntent)
        _isRunning.value = true
        return Result.success(Unit)
    }

    override fun stop(): Result<Unit> {
        val ctx = appContext ?: return Result.failure(IllegalStateException("Context not initialized"))
        val serviceIntent = Intent(ctx, NullVpnService::class.java).apply {
            action = NullVpnService.ACTION_STOP
        }
        ctx.startService(serviceIntent)
        _isRunning.value = false
        _stats.value = TrafficStats(isConnected = false)
        return Result.success(Unit)
    }

    override fun submitCaptcha(token: String) {
        _currentCaptchaUrl.value = null
    }
}

private val androidEngineInstance = AndroidCoreEngine()

actual fun getPlatformCoreEngine(): CoreEngine = androidEngineInstance

fun initializeAndroidCoreEngine(context: Context) {
    androidEngineInstance.setContext(context)
}

