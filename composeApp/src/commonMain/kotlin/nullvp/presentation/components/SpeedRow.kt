package nullvp.presentation.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import nullvp.theme.CyberGreen
import nullvp.theme.DarkSurfaceVariant
import nullvp.theme.NeonCyan
import nullvp.theme.OnDarkSurfaceVariant

@Composable
fun SpeedRow(
    rxSpeedBps: Long,
    txSpeedBps: Long,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(DarkSurfaceVariant)
            .padding(16.dp),
        horizontalArrangement = Arrangement.SpaceAround,
        verticalAlignment = Alignment.CenterVertically
    ) {
        SpeedItem(label = "DOWNLOAD", speedBps = rxSpeedBps, color = CyberGreen)
        Box(
            modifier = Modifier
                .width(1.dp)
                .height(32.dp)
                .background(Color.White.copy(alpha = 0.1f))
        )
        SpeedItem(label = "UPLOAD", speedBps = txSpeedBps, color = NeonCyan)
    }
}

@Composable
private fun SpeedItem(
    label: String,
    speedBps: Long,
    color: Color
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = OnDarkSurfaceVariant
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = formatSpeed(speedBps),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = color
        )
    }
}

private fun formatSpeed(bytesPerSec: Long): String {
    return when {
        bytesPerSec >= 1024 * 1024 -> String.format("%.1f MB/s", bytesPerSec / (1024.0 * 1024.0))
        bytesPerSec >= 1024 -> String.format("%.1f KB/s", bytesPerSec / 1024.0)
        else -> "$bytesPerSec B/s"
    }
}

