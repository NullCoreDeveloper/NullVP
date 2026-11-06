package nullvp.platform

import androidx.compose.runtime.Composable

/**
 * Платформо-зависимый обработчик нажатия кнопки "Назад".
 * Android — использует BackHandler из androidx.activity.compose.
 * Desktop — no-op (нет физической кнопки назад).
 */
@Composable
expect fun AppBackHandler(enabled: Boolean, onBack: () -> Unit)

