package nullvp.platform

actual object UrlHelper {
    actual fun openUrl(url: String) {
        // Handled on platform or no-op when backgrounded
    }
}

