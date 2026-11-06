package nullvp.platform

expect object PrivilegeHelper {
    val isLinux: Boolean
    val needsSudoForTun: Boolean
    suspend fun verifyAndCacheSudoPassword(password: String): Pair<Boolean, String?>
}

