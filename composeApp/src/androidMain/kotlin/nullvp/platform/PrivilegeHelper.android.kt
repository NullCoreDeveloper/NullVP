package nullvp.platform

actual object PrivilegeHelper {
    actual val isLinux: Boolean = false
    actual val needsSudoForTun: Boolean = false
    actual suspend fun verifyAndCacheSudoPassword(password: String): Pair<Boolean, String?> = true to null
}

