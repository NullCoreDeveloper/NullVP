package nullvp.platform

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context

actual object ClipboardHelper {
    private var appContext: Context? = null

    fun setContext(ctx: Context) {
        appContext = ctx.applicationContext
    }

    actual fun getText(): String? {
        val ctx = appContext ?: return null
        val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        val item = cm?.primaryClip?.getItemAt(0)
        return item?.text?.toString()
    }

    actual fun setText(text: String) {
        val ctx = appContext ?: return
        val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        val clip = ClipData.newPlainText("NullVP", text)
        cm?.setPrimaryClip(clip)
    }
}

