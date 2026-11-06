package nullvp.platform

actual object PrivilegeHelper {
    actual val isLinux: Boolean get() = DesktopPrivilegeManager.isLinux
    actual val needsSudoForTun: Boolean
        get() = isLinux && DesktopPrivilegeManager.cachedSudoPassword == null

    actual suspend fun verifyAndCacheSudoPassword(password: String): Pair<Boolean, String?> {
        val (ok, err) = DesktopPrivilegeManager.verifySudoPassword(password)
        if (ok) {
            DesktopPrivilegeManager.cachedSudoPassword = password
        }
        return ok to err
    }
}

