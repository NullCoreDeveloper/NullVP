package nullvp.presentation.dialogs

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import kotlinx.coroutines.launch
import nullvp.theme.*

@Composable
fun SudoPasswordDialog(
    isOpen: Boolean,
    onDismiss: () -> Unit,
    onConfirmed: () -> Unit,
    onVerify: suspend (String) -> Pair<Boolean, String?>
) {
    if (!isOpen) return

    val scope = rememberCoroutineScope()
    var password by remember { mutableStateOf("") }
    var passwordVisible by remember { mutableStateOf(false) }
    var isVerifying by remember { mutableStateOf(false) }
    var errorMsg by remember { mutableStateOf<String?>(null) }

    fun submit() {
        if (password.isBlank()) {
            errorMsg = "Пароль не может быть пустым"
            return
        }
        isVerifying = true
        errorMsg = null
        scope.launch {
            val (ok, err) = onVerify(password)
            isVerifying = false
            if (ok) {
                onConfirmed()
            } else {
                errorMsg = err ?: "Неверный пароль sudo, попробуйте еще раз"
            }
        }
    }

    Dialog(onDismissRequest = { if (!isVerifying) onDismiss() }) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = DarkSurface,
            border = androidx.compose.foundation.BorderStroke(1.dp, NeonCyan.copy(alpha = 0.5f)),
            modifier = Modifier.fillMaxWidth(0.92f)
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Icon(
                        Icons.Default.Lock,
                        contentDescription = null,
                        tint = NeonCyan,
                        modifier = Modifier.size(28.dp)
                    )
                    Text(
                        text = "Активация режима TUN",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = OnDarkSurface
                    )
                }

                Text(
                    text = "Для работы режима TUN (полная переадресация сетевого трафика) на Linux требуются привилегии суперпользователя (sudo).\n\nПароль вводится один раз и хранится только в оперативной памяти текущей сессии.",
                    style = MaterialTheme.typography.bodySmall,
                    color = OnDarkSurfaceVariant,
                    lineHeight = 18.sp
                )

                OutlinedTextField(
                    value = password,
                    onValueChange = {
                        password = it
                        errorMsg = null
                    },
                    label = { Text("Пароль sudo") },
                    placeholder = { Text("Введите системный пароль...") },
                    singleLine = true,
                    enabled = !isVerifying,
                    visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { submit() }),
                    trailingIcon = {
                        TextButton(onClick = { passwordVisible = !passwordVisible }) {
                            Text(
                                text = if (passwordVisible) "Скрыть" else "Показать",
                                color = NeonCyan,
                                fontSize = 11.sp
                            )
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(10.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = NeonCyan,
                        unfocusedBorderColor = DarkSurfaceVariant,
                        focusedTextColor = OnDarkSurface,
                        unfocusedTextColor = OnDarkSurface,
                        focusedLabelColor = NeonCyan
                    )
                )

                if (errorMsg != null) {
                    Text(
                        text = errorMsg!!,
                        color = CyberRed,
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.SemiBold
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(
                        onClick = onDismiss,
                        enabled = !isVerifying
                    ) {
                        Text("Отмена", color = OnDarkSurfaceVariant)
                    }

                    Spacer(Modifier.width(8.dp))

                    Button(
                        onClick = { submit() },
                        enabled = !isVerifying && password.isNotBlank(),
                        colors = ButtonDefaults.buttonColors(containerColor = NeonCyan, contentColor = Color.Black),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        if (isVerifying) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp,
                                color = Color.Black
                            )
                            Spacer(Modifier.width(8.dp))
                        }
                        Text("Подтвердить", fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}
