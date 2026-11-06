package nullvp.platform

import kotlin.system.exitProcess

actual object AppExitHelper {
    actual fun exitApp() {
        exitProcess(0)
    }
}

