package nullvp.presentation.components

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

@Composable
actual fun AppVerticalScrollbar(
    modifier: Modifier,
    listState: LazyListState
) {
    // Touch scrolling natively handled on Android
}

