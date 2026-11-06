package nullvp.presentation.dialogs

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import nullvp.platform.ClipboardHelper
import nullvp.platform.UrlHelper
import nullvp.theme.*

@Composable
fun VkCaptchaDialog(
    captchaUrl: String,
    onSubmit: (token: String) -> Unit,
    onDismiss: () -> Unit
) {
    var token by remember { mutableStateOf("") }
    var copied by remember { mutableStateOf(false) }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(18.dp),
            color = DarkSurface,
            border = androidx.compose.foundation.BorderStroke(1.dp, NeonCyan.copy(alpha = 0.5f)),
            modifier = Modifier.fillMaxWidth().padding(16.dp)
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Icon(
                    imageVector = Icons.Default.Lock,
                    contentDescription = null,
                    tint = CyberAmber,
                    modifier = Modifier.size(40.dp)
                )

                Spacer(Modifier.height(12.dp))

                Text(
                    text = "Требуется решение VK Captcha",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = OnDarkSurface
                )

                Spacer(Modifier.height(8.dp))

                Text(
                    text = "VK TURN заблокировал сессию проверкой на робота. Откройте капчу в браузере, пройдите её и скопируйте итоговый токен или подтвердите прохождение:",
                    style = MaterialTheme.typography.bodySmall,
                    color = OnDarkSurfaceVariant
                )

                Spacer(Modifier.height(16.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = { UrlHelper.openUrl(captchaUrl) },
                        colors = ButtonDefaults.buttonColors(containerColor = NeonCyan, contentColor = Color.Black),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Открыть сайт", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }

                    OutlinedButton(
                        onClick = {
                            ClipboardHelper.setText(captchaUrl)
                            copied = true
                        },
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = NeonCyan),
                        border = androidx.compose.foundation.BorderStroke(1.dp, NeonCyan.copy(alpha = 0.6f)),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(if (copied) "Скопировано!" else "Ссылка", fontSize = 12.sp)
                    }
                }

                Spacer(Modifier.height(16.dp))

                OutlinedTextField(
                    value = token,
                    onValueChange = { token = it },
                    label = { Text("Токен капчи (sid / sign / key)") },
                    placeholder = { Text("Вставьте токен капчи...") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = NeonCyan,
                        unfocusedBorderColor = DarkSurfaceVariant,
                        focusedTextColor = OnDarkSurface,
                        unfocusedTextColor = OnDarkSurface
                    )
                )

                Spacer(Modifier.height(20.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(onClick = onDismiss) {
                        Text("Отмена", color = OnDarkSurfaceVariant)
                    }
                    Spacer(Modifier.width(8.dp))
                    Button(
                        onClick = { onSubmit(token.trim()) },
                        colors = ButtonDefaults.buttonColors(containerColor = CyberGreen, contentColor = Color.Black),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text("Подтвердить", fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}
