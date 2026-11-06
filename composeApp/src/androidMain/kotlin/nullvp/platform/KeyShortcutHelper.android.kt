package nullvp.platform

import androidx.compose.ui.input.key.*

actual fun isClipboardPasteEvent(event: KeyEvent): Boolean {
    if (event.type != KeyEventType.KeyDown) return false
    return (event.isCtrlPressed || event.isMetaPressed) &&
            (event.key == Key.V || event.utf16CodePoint == 22 || event.utf16CodePoint == 1084 || event.utf16CodePoint == 1052)
}

actual fun isClipboardCopyEvent(event: KeyEvent): Boolean {
    if (event.type != KeyEventType.KeyDown) return false
    return (event.isCtrlPressed || event.isMetaPressed) &&
            (event.key == Key.C || event.utf16CodePoint == 3 || event.utf16CodePoint == 1089 || event.utf16CodePoint == 1057)
}

actual fun isClipboardCutEvent(event: KeyEvent): Boolean {
    if (event.type != KeyEventType.KeyDown) return false
    return (event.isCtrlPressed || event.isMetaPressed) &&
            (event.key == Key.X || event.utf16CodePoint == 24 || event.utf16CodePoint == 1095 || event.utf16CodePoint == 1063)
}

actual fun isSelectAllEvent(event: KeyEvent): Boolean {
    if (event.type != KeyEventType.KeyDown) return false
    return (event.isCtrlPressed || event.isMetaPressed) &&
            (event.key == Key.A || event.utf16CodePoint == 1 || event.utf16CodePoint == 1092 || event.utf16CodePoint == 1060)
}

