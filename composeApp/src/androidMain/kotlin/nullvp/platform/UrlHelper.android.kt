package nullvp.platform

import android.content.Intent
import android.net.Uri
import nullvp.MainActivity

actual object UrlHelper {
    actual fun openUrl(url: String) {
        runCatching {
            val ctx = MainActivity.instance ?: return
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            ctx.startActivity(intent)
        }
    }
}

