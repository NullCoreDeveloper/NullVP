package nullvp.platform

data class AppInfoItem(
    val packageName: String,
    val appName: String,
    val isSystemApp: Boolean = false
)

expect object AppListHelper {
    fun isAndroid(): Boolean
    suspend fun getInstalledApps(): List<AppInfoItem>
}

