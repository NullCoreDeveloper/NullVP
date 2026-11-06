package nullvp.presentation.components

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import nullvp.platform.*
import nullvp.theme.*

@Composable
fun NullVpTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    label: @Composable (() -> Unit)? = null,
    placeholder: @Composable (() -> Unit)? = null,
    singleLine: Boolean = true,
    maxLines: Int = if (singleLine) 1 else Int.MAX_VALUE,
    isError: Boolean = false,
    textStyle: TextStyle = LocalTextStyle.current,
    shape: Shape = RoundedCornerShape(10.dp),
    showPasteButton: Boolean = true,
    showClearButton: Boolean = true,
    trailingIcon: @Composable (() -> Unit)? = null,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
    colors: TextFieldColors = OutlinedTextFieldDefaults.colors(
        focusedBorderColor = NeonCyan,
        unfocusedBorderColor = DarkSurfaceVariant,
        focusedTextColor = OnDarkSurface,
        unfocusedTextColor = OnDarkSurface,
        focusedLabelColor = NeonCyan,
        unfocusedLabelColor = OnDarkSurfaceVariant
    )
) {
    var tfv by remember {
        mutableStateOf(TextFieldValue(text = value, selection = TextRange(value.length)))
    }

    // Keep internal text state synchronized with incoming value
    LaunchedEffect(value) {
        if (tfv.text != value) {
            val newCursor = tfv.selection.start.coerceIn(0, value.length)
            tfv = TextFieldValue(text = value, selection = TextRange(newCursor))
        }
    }

    val keyModifier = Modifier.onPreviewKeyEvent { event ->
        if (isClipboardPasteEvent(event)) {
            val clip = ClipboardHelper.getText()
            if (!clip.isNullOrEmpty()) {
                val curText = tfv.text
                val sel = tfv.selection
                val newText = if (sel.collapsed) {
                    val before = curText.take(sel.start)
                    val after = curText.substring(sel.start)
                    before + clip + after
                } else {
                    curText.replaceRange(sel.min, sel.max, clip)
                }
                val newCursor = (if (sel.collapsed) sel.start else sel.min) + clip.length
                tfv = TextFieldValue(text = newText, selection = TextRange(newCursor))
                onValueChange(newText)
                return@onPreviewKeyEvent true
            }
        } else if (isClipboardCopyEvent(event)) {
            val sel = tfv.selection
            val textToCopy = if (sel.collapsed) tfv.text else tfv.text.substring(sel.min, sel.max)
            if (textToCopy.isNotEmpty()) {
                ClipboardHelper.setText(textToCopy)
                return@onPreviewKeyEvent true
            }
        } else if (isClipboardCutEvent(event)) {
            val sel = tfv.selection
            if (!sel.collapsed) {
                val textToCut = tfv.text.substring(sel.min, sel.max)
                ClipboardHelper.setText(textToCut)
                val newText = tfv.text.removeRange(sel.min, sel.max)
                tfv = TextFieldValue(text = newText, selection = TextRange(sel.min))
                onValueChange(newText)
                return@onPreviewKeyEvent true
            }
        } else if (isSelectAllEvent(event)) {
            tfv = tfv.copy(selection = TextRange(0, tfv.text.length))
            return@onPreviewKeyEvent true
        }
        false
    }

    OutlinedTextField(
        value = tfv,
        onValueChange = { newTfv ->
            tfv = newTfv
            if (newTfv.text != value) {
                onValueChange(newTfv.text)
            }
        },
        modifier = modifier.then(keyModifier),
        label = label,
        placeholder = placeholder,
        singleLine = singleLine,
        maxLines = maxLines,
        isError = isError,
        textStyle = textStyle,
        shape = shape,
        keyboardOptions = keyboardOptions,
        keyboardActions = keyboardActions,
        colors = colors,
        trailingIcon = trailingIcon ?: {
            Row {
                if (showClearButton && value.isNotEmpty()) {
                    IconButton(
                        onClick = {
                            tfv = TextFieldValue("", TextRange.Zero)
                            onValueChange("")
                        }
                    ) {
                        Icon(
                            Icons.Default.Clear,
                            contentDescription = "Очистить",
                            tint = OnDarkSurfaceVariant
                        )
                    }
                }
                if (showPasteButton) {
                    IconButton(
                        onClick = {
                            val clip = ClipboardHelper.getText()
                            if (!clip.isNullOrEmpty()) {
                                tfv = TextFieldValue(clip, TextRange(clip.length))
                                onValueChange(clip)
                            }
                        }
                    ) {
                        Icon(
                            PasteIcon,
                            contentDescription = "Вставить из буфера",
                            tint = if (value.isEmpty()) NeonCyan else OnDarkSurfaceVariant
                        )
                    }
                }
            }
        }
    )
}
