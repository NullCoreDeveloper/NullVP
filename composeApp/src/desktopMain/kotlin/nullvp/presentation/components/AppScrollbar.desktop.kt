package nullvp.presentation.components

import androidx.compose.foundation.ScrollbarStyle
import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import nullvp.theme.NeonCyan

@Composable
actual fun AppVerticalScrollbar(
    modifier: Modifier,
    listState: LazyListState
) {
    VerticalScrollbar(
        adapter = rememberScrollbarAdapter(listState),
        modifier = modifier,
        style = ScrollbarStyle(
            minimalHeight = 16.dp,
            thickness = 10.dp,
            shape = RoundedCornerShape(5.dp),
            hoverDurationMillis = 200,
            unhoverColor = NeonCyan.copy(alpha = 0.35f),
            hoverColor = NeonCyan.copy(alpha = 0.9f)
        )
    )
}

