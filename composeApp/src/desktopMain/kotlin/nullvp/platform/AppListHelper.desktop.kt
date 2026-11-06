package nullvp.platform

actual object AppListHelper {
    actual fun isAndroid(): Boolean = false
    actual suspend fun getInstalledApps(): List<AppInfoItem> = emptyList()
}

