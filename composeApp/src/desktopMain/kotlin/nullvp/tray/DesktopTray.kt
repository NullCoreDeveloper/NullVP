package nullvp.tray

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.toComposeImageBitmap
import java.awt.BasicStroke
import java.awt.Color as AwtColor
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.image.BufferedImage

object DesktopTrayIcon {
    fun createIcon(isConnected: Boolean): BitmapPainter {
        val size = 32
        val image = BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB)
        val g: Graphics2D = image.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)

        // Background circle
        g.color = if (isConnected) AwtColor(0, 229, 255) else AwtColor(45, 45, 55)
        g.fillOval(2, 2, size - 4, size - 4)

        // Inner shield/dot
        g.color = if (isConnected) AwtColor(0, 0, 0) else AwtColor(0, 229, 255)
        g.stroke = BasicStroke(2.5f)
        g.drawOval(8, 8, size - 16, size - 16)
        g.dispose()

        return BitmapPainter(image.toComposeImageBitmap())
    }
}

