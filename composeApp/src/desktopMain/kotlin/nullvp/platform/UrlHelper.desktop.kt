package nullvp.platform

import java.awt.Desktop
import java.net.URI

actual object UrlHelper {
    actual fun openUrl(url: String) {
        try {
            if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                Desktop.getDesktop().browse(URI(url))
            } else {
                ProcessBuilder("xdg-open", url).start()
            }
        } catch (_: Exception) {
            try {
                ProcessBuilder("xdg-open", url).start()
            } catch (_: Exception) {}
        }
    }
}

