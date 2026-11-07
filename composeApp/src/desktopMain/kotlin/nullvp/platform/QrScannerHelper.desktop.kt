package nullvp.platform

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.client.j2se.BufferedImageLuminanceSource
import com.google.zxing.common.GlobalHistogramBinarizer
import com.google.zxing.qrcode.QRCodeReader
import java.io.File
import javax.imageio.ImageIO
import javax.swing.JFileChooser
import javax.swing.filechooser.FileNameExtensionFilter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

actual class QrScannerLauncher(
    private val onPickFile: () -> Unit
) {
    actual fun launchCamera() = onPickFile()
    actual fun launchGallery() = onPickFile()
}

@Composable
actual fun rememberQrScanner(
    onResult: (String) -> Unit,
    onError: (String) -> Unit
): QrScannerLauncher {
    val coroutineScope = remember { CoroutineScope(Dispatchers.IO) }

    return remember {
        QrScannerLauncher(
            onPickFile = {
                coroutineScope.launch {
                    try {
                        val chooser = JFileChooser().apply {
                            dialogTitle = "Выберите изображение с QR-кодом"
                            fileFilter = FileNameExtensionFilter("Изображения (PNG, JPG, BMP)", "png", "jpg", "jpeg", "bmp", "webp")
                            isAcceptAllFileFilterUsed = false
                        }
                        val result = chooser.showOpenDialog(null)
                        if (result == JFileChooser.APPROVE_OPTION) {
                            val file: File = chooser.selectedFile
                            val bufferedImage = ImageIO.read(file)
                            if (bufferedImage == null) {
                                onError("Не удалось загрузить изображение: ${file.name}")
                                return@launch
                            }
                            val source = BufferedImageLuminanceSource(bufferedImage)
                            val bitmap = BinaryBitmap(GlobalHistogramBinarizer(source))
                            val reader = QRCodeReader()
                            val hints = mapOf(DecodeHintType.CHARACTER_SET to "UTF-8")
                            val text = reader.decode(bitmap, hints).text
                            if (!text.isNullOrBlank()) {
                                onResult(text.trim())
                            } else {
                                onError("В изображении не найден QR-код")
                            }
                        }
                    } catch (e: Exception) {
                        onError("Ошибка распознавания QR-кода: ${e.message ?: "неизвестная ошибка"}")
                    }
                }
            }
        )
    }
}

