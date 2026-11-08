package nullvp.platform

import nullvp.MainActivity
import nullvp.ui.CaptchaActivity

actual object CaptchaHelper {
    actual fun openCaptcha(url: String) {
        val ctx = MainActivity.instance ?: return
        CaptchaActivity.start(ctx, url)
    }
}

