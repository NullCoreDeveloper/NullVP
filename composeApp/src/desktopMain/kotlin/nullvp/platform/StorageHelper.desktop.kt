package nullvp.platform

import java.io.File

actual object StorageHelper {
    private val file by lazy {
        val home = System.getProperty("user.home") ?: "."
        val dir = File(home, ".config/nullvp")
        if (!dir.exists()) dir.mkdirs()
        File(dir, "state.json")
    }

    actual fun readStateJson(): String? {
        return runCatching {
            if (file.exists()) file.readText() else null
        }.getOrNull()
    }

    actual fun writeStateJson(json: String) {
        runCatching {
            file.writeText(json)
        }
    }
}

