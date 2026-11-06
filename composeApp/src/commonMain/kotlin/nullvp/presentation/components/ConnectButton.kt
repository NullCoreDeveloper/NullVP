package nullvp.presentation.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import nullvp.theme.CyberGreen
import nullvp.theme.CyberRed
import nullvp.theme.NeonCyan

enum class ConnectState {
    DISCONNECTED,
    CONNECTING,
    CONNECTED
}

@Composable
fun ConnectButton(
    state: ConnectState,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = if (state == ConnectState.CONNECTING) 1.15f else 1.05f,
        animationSpec = infiniteRepeatable(
            animation = tween(1000, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulseScale"
    )

    val buttonColor by animateColorAsState(
        targetValue = when (state) {
            ConnectState.CONNECTED -> CyberGreen
            ConnectState.CONNECTING -> NeonCyan
            ConnectState.DISCONNECTED -> Color(0xFF2A2A35)
        },
        label = "btnColor"
    )

    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier.size(160.dp)
    ) {
        // Outer pulsing ring when active
        if (state != ConnectState.DISCONNECTED) {
            Box(
                modifier = Modifier
                    .size(150.dp)
                    .scale(pulseScale)
                    .clip(CircleShape)
                    .background(buttonColor.copy(alpha = 0.2f))
            )
        }

        // Main button
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(120.dp)
                .clip(CircleShape)
                .background(buttonColor)
                .border(3.dp, buttonColor.copy(alpha = 0.6f), CircleShape)
                .clickable(onClick = onClick)
        ) {
            Text(
                text = when (state) {
                    ConnectState.CONNECTED -> "ON"
                    ConnectState.CONNECTING -> "..."
                    ConnectState.DISCONNECTED -> "START"
                },
                style = MaterialTheme.typography.titleMedium,
                color = if (state == ConnectState.DISCONNECTED) Color.White else Color.Black
            )
        }
    }
}

