package nullvp.core

import android.content.Context
import android.content.Intent
import android.net.VpnService
import kotlinx.coroutines.flow.StateFlow
import nullvp.MainActivity
import nullvp.data.store.AppStateStore
import nullvp.domain.model.Profile
import nullvp.domain.model.TrafficStats
import nullvp.service.NullVpnService

class AndroidCoreEngine(private var appContext: Context? = null) : CoreEngine {

    override val isRunning: StateFlow<Boolean> = AndroidCoreBridge.isRunning
    override val isConnecting: StateFlow<Boolean> = AndroidCoreBridge.isConnecting
    override val connectionError: StateFlow<String?> = AndroidCoreBridge.connectionError
    override val stats: StateFlow<TrafficStats> = AndroidCoreBridge.stats
    override val logs: StateFlow<List<String>> = AndroidCoreBridge.logs
    override val currentCaptchaUrl: StateFlow<String?> = AndroidCoreBridge.currentCaptchaUrl

    fun setContext(context: Context) {
        this.appContext = context.applicationContext
    }

    override fun start(profile: Profile): Result<Unit> {
        val ctx = appContext ?: return Result.failure(IllegalStateException("Context not initialized"))

        val settings = AppStateStore.settings.value
        val isTunMode = settings.operationMode != nullvp.domain.model.OperationMode.PROXY_ONLY

        // Only request Android VPN permission if we are actually starting a TUN tunnel
        if (isTunMode) {
            val prepareIntent = VpnService.prepare(ctx)
            if (prepareIntent != null) {
                MainActivity.instance?.let { act ->
                    MainActivity.pendingProfile = profile
                    MainActivity.vpnPermissionLauncher?.launch(prepareIntent)
                    return Result.failure(SecurityException("VPN permission consent requested"))
                } ?: return Result.failure(SecurityException("VPN consent needed"))
            }
        }

        AndroidCoreBridge.activeProfile = profile
        AndroidCoreBridge.appSettings = settings
        AndroidCoreBridge.setConnecting(true)

        val serviceIntent = Intent(ctx, NullVpnService::class.java).apply {
            action = NullVpnService.ACTION_START
        }
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            ctx.startForegroundService(serviceIntent)
        } else {
            ctx.startService(serviceIntent)
        }
        return Result.success(Unit)
    }

    override fun stop(): Result<Unit> {
        val ctx = appContext ?: return Result.failure(IllegalStateException("Context not initialized"))
        val serviceIntent = Intent(ctx, NullVpnService::class.java).apply {
            action = NullVpnService.ACTION_STOP
        }
        ctx.startService(serviceIntent)
        return Result.success(Unit)
    }

    override fun submitCaptcha(token: String) {
        AndroidCoreBridge.submitCaptchaToken(token)
    }
}

val androidEngineInstance = AndroidCoreEngine()

actual fun getPlatformCoreEngine(): CoreEngine = androidEngineInstance

fun initializeAndroidCoreEngine(context: Context) {
    androidEngineInstance.setContext(context)
}
