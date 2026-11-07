package nullvp.platform

import androidx.compose.runtime.Composable

expect class QrScannerLauncher {
    fun launchCamera()
    fun launchGallery()
}

@Composable
expect fun rememberQrScanner(
    onResult: (String) -> Unit,
    onError: (String) -> Unit
): QrScannerLauncher

