package nullvp.platform

import android.graphics.BitmapFactory
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.GlobalHistogramBinarizer
import com.google.zxing.qrcode.QRCodeReader
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions

actual class QrScannerLauncher(
    private val onLaunchCamera: () -> Unit,
    private val onLaunchGallery: () -> Unit
) {
    actual fun launchCamera() = onLaunchCamera()
    actual fun launchGallery() = onLaunchGallery()
}

@Composable
actual fun rememberQrScanner(
    onResult: (String) -> Unit,
    onError: (String) -> Unit
): QrScannerLauncher {
    val context = LocalContext.current

    val cameraLauncher = rememberLauncherForActivityResult(ScanContract()) { result ->
        val contents = result.contents
        if (!contents.isNullOrBlank()) {
            onResult(contents.trim())
        }
    }

    val galleryLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            try {
                context.contentResolver.openInputStream(uri)?.use { inputStream ->
                    val bitmap = BitmapFactory.decodeStream(inputStream)
                    if (bitmap == null) {
                        onError("Не удалось загрузить изображение")
                        return@use
                    }
                    val width = bitmap.width
                    val height = bitmap.height
                    val pixels = IntArray(width * height)
                    bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
                    val source = RGBLuminanceSource(width, height, pixels)
                    val binaryBitmap = BinaryBitmap(GlobalHistogramBinarizer(source))
                    val reader = QRCodeReader()
                    val hints = mapOf(DecodeHintType.CHARACTER_SET to "UTF-8")
                    val result = reader.decode(binaryBitmap, hints)
                    val text = result.text
                    if (!text.isNullOrBlank()) {
                        onResult(text.trim())
                    } else {
                        onError("В изображении не найден QR-код")
                    }
                }
            } catch (e: Exception) {
                onError("Не удалось распознать QR-код: ${e.message ?: "ошибка"}")
            }
        }
    }

    return remember {
        QrScannerLauncher(
            onLaunchCamera = {
                val options = ScanOptions().apply {
                    setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                    setPrompt("Наведите камеру на QR-код профиля или подписки")
                    setBeepEnabled(false)
                    setOrientationLocked(false)
                }
                cameraLauncher.launch(options)
            },
            onLaunchGallery = {
                galleryLauncher.launch("image/*")
            }
        )
    }
}

