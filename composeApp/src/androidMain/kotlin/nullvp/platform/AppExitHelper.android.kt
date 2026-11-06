package nullvp.platform

import nullvp.MainActivity

actual object AppExitHelper {
    actual fun exitApp() {
        MainActivity.instance?.finish()
    }
}

