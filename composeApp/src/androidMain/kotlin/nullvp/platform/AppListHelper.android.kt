package nullvp.platform

import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import nullvp.MainActivity

actual object AppListHelper {
    actual fun isAndroid(): Boolean = true

    actual suspend fun getInstalledApps(): List<AppInfoItem> = withContext(Dispatchers.IO) {
        val context = MainActivity.instance?.applicationContext ?: return@withContext emptyList()
        val pm = context.packageManager
        val selfPkg = context.packageName

        val packages = runCatching {
            pm.getInstalledPackages(0)
        }.getOrDefault(emptyList())

        val result = mutableListOf<AppInfoItem>()
        for (pkg in packages) {
            val appInfo = pkg.applicationInfo ?: continue
            val pkgName = pkg.packageName
            if (pkgName == selfPkg) continue

            val isSys = (appInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0
            val label = runCatching { pm.getApplicationLabel(appInfo).toString() }.getOrDefault(pkgName)

            result.add(
                AppInfoItem(
                    packageName = pkgName,
                    appName = label.ifBlank { pkgName },
                    isSystemApp = isSys
                )
            )
        }
        result.sortedBy { it.appName.lowercase() }
    }
}

