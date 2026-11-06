package nullvp.platform

import androidx.compose.ui.input.key.KeyEvent

expect fun isClipboardPasteEvent(event: KeyEvent): Boolean
expect fun isClipboardCopyEvent(event: KeyEvent): Boolean
expect fun isClipboardCutEvent(event: KeyEvent): Boolean
expect fun isSelectAllEvent(event: KeyEvent): Boolean

