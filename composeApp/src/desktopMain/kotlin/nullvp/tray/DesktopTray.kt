package nullvp.tray

import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.toComposeImageBitmap
import java.awt.BasicStroke
import java.awt.Color as AwtColor
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import javax.imageio.ImageIO

object DesktopTrayIcon {
    fun createIcon(isConnected: Boolean): BitmapPainter {
        val stream = object {}::class.java.classLoader?.getResourceAsStream("icon.png")
        if (stream != null) {
            val loaded = runCatching {
                val img = ImageIO.read(stream)
                if (img != null) {
                    val result = BufferedImage(img.width, img.height, BufferedImage.TYPE_INT_ARGB)
                    val g = result.createGraphics()
                    g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                    g.drawImage(img, 0, 0, null)
                    if (isConnected) {
                        val r = img.width / 4
                        val x = img.width - r - 4
                        val y = img.height - r - 4
                        g.color = AwtColor(0, 230, 120)
                        g.fillOval(x, y, r, r)
                        g.color = AwtColor(0, 0, 0, 180)
                        g.stroke = BasicStroke(2f)
                        g.drawOval(x, y, r, r)
                    }
                    g.dispose()
                    BitmapPainter(result.toComposeImageBitmap())
                } else null
            }.getOrNull()

            if (loaded != null) return loaded
        }

        // Fallback procedural icon
        val size = 32
        val image = BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB)
        val g: Graphics2D = image.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g.color = if (isConnected) AwtColor(0, 229, 255) else AwtColor(45, 45, 55)
        g.fillOval(2, 2, size - 4, size - 4)
        g.dispose()
        return BitmapPainter(image.toComposeImageBitmap())
    }
}
