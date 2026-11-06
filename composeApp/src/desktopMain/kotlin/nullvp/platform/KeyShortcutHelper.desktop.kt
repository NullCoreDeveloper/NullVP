package nullvp.platform

import androidx.compose.ui.input.key.*
import java.awt.event.KeyEvent as AwtKeyEvent

actual fun isClipboardPasteEvent(event: KeyEvent): Boolean {
    if (event.type != KeyEventType.KeyDown) return false
    if (!event.isCtrlPressed && !event.isMetaPressed) return false

    val awt = event.nativeKeyEvent as? AwtKeyEvent
    if (awt != null) {
        if (awt.extendedKeyCode == AwtKeyEvent.VK_V || awt.keyCode == AwtKeyEvent.VK_V) return true
        if (awt.keyChar == '\u0016' || awt.keyChar == 'м' || awt.keyChar == 'М') return true
    }
    return event.key == Key.V || event.utf16CodePoint == 22 || event.utf16CodePoint == 1084 || event.utf16CodePoint == 1052
}

actual fun isClipboardCopyEvent(event: KeyEvent): Boolean {
    if (event.type != KeyEventType.KeyDown) return false
    if (!event.isCtrlPressed && !event.isMetaPressed) return false

    val awt = event.nativeKeyEvent as? AwtKeyEvent
    if (awt != null) {
        if (awt.extendedKeyCode == AwtKeyEvent.VK_C || awt.keyCode == AwtKeyEvent.VK_C) return true
        if (awt.keyChar == '\u0003' || awt.keyChar == 'с' || awt.keyChar == 'С') return true
    }
    return event.key == Key.C || event.utf16CodePoint == 3 || event.utf16CodePoint == 1089 || event.utf16CodePoint == 1057
}

actual fun isClipboardCutEvent(event: KeyEvent): Boolean {
    if (event.type != KeyEventType.KeyDown) return false
    if (!event.isCtrlPressed && !event.isMetaPressed) return false

    val awt = event.nativeKeyEvent as? AwtKeyEvent
    if (awt != null) {
        if (awt.extendedKeyCode == AwtKeyEvent.VK_X || awt.keyCode == AwtKeyEvent.VK_X) return true
        if (awt.keyChar == '\u0018' || awt.keyChar == 'ч' || awt.keyChar == 'Ч') return true
    }
    return event.key == Key.X || event.utf16CodePoint == 24 || event.utf16CodePoint == 1095 || event.utf16CodePoint == 1063
}

actual fun isSelectAllEvent(event: KeyEvent): Boolean {
    if (event.type != KeyEventType.KeyDown) return false
    if (!event.isCtrlPressed && !event.isMetaPressed) return false

    val awt = event.nativeKeyEvent as? AwtKeyEvent
    if (awt != null) {
        if (awt.extendedKeyCode == AwtKeyEvent.VK_A || awt.keyCode == AwtKeyEvent.VK_A) return true
        if (awt.keyChar == '\u0001' || awt.keyChar == 'ф' || awt.keyChar == 'Ф') return true
    }
    return event.key == Key.A || event.utf16CodePoint == 1 || event.utf16CodePoint == 1092 || event.utf16CodePoint == 1060
}

