package nullvp.platform

import android.content.Context
import java.io.File

actual object StorageHelper {
    private var appContext: Context? = null

    fun setContext(ctx: Context) {
        this.appContext = ctx.applicationContext
    }

    private val file: File?
        get() = appContext?.let { File(it.filesDir, "nullvp_state.json") }

    actual fun readStateJson(): String? {
        return runCatching {
            val f = file
            if (f != null && f.exists()) f.readText() else null
        }.getOrNull()
    }

    actual fun writeStateJson(json: String) {
        runCatching {
            file?.writeText(json)
        }
    }
}

