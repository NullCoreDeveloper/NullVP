package nullvp.platform

import com.journeyapps.barcodescanner.CaptureActivity

/**
 * CaptureActivity with fixed portrait orientation to prevent screen rotation when scanning QR codes.
 */
class PortraitCaptureActivity : CaptureActivity()

