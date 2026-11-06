package nullvp.core

import kotlinx.coroutines.flow.StateFlow
import nullvp.domain.model.Profile
import nullvp.domain.model.TrafficStats

interface CoreEngine {
    val isRunning: StateFlow<Boolean>
    val stats: StateFlow<TrafficStats>
    val logs: StateFlow<List<String>>
    val currentCaptchaUrl: StateFlow<String?>

    fun start(profile: Profile): Result<Unit>
    fun stop(): Result<Unit>
    fun submitCaptcha(token: String)
}

expect fun getPlatformCoreEngine(): CoreEngine

