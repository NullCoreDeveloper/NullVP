package nullvp.platform

actual object CaptchaHelper {
    actual fun openCaptcha(url: String) {
        UrlHelper.openUrl(url)
    }
}

