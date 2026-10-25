package com.example.offlinetranscription.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.offlinetranscription.theme.StatusSuccess
import com.example.offlinetranscription.theme.StatusWarning

@Composable
fun DiagnosticBadge(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    highlightColor: Color? = null
) {
    val borderColor = highlightColor ?: MaterialTheme.colorScheme.outline.copy(alpha = 0.4f)

    Row(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
            .border(1.dp, borderColor, RoundedCornerShape(8.dp))
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        if (highlightColor != null) {
            Box(
                modifier = Modifier
                    .size(6.dp)
                    .clip(CircleShape)
                    .background(highlightColor)
            )
        }
        Text(
            text = label.uppercase(),
            style = MaterialTheme.typography.labelSmall.copy(
                fontSize = 9.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        )
        Text(
            text = value,
            style = MaterialTheme.typography.labelSmall.copy(
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.SemiBold,
                color = highlightColor ?: MaterialTheme.colorScheme.onSurface
            )
        )
    }
}

@Composable
fun PerformanceRow(
    rtf: Float,
    latencyMs: Long,
    threads: Int,
    modifier: Modifier = Modifier,
    firstPartialMs: Long = 0,
    finalLatencyMs: Long = 0,
    queueAgeMs: Long = 0
) {
    FlowRow(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        val rtfColor = if (rtf in 0.01f..0.8f) StatusSuccess else if (rtf > 0.8f) StatusWarning else null
        DiagnosticBadge(
            label = "RTF",
            value = if (rtf > 0.001f) String.format(java.util.Locale.US, "%.2fx", rtf) else "--",
            highlightColor = rtfColor
        )
        DiagnosticBadge(
            label = "Inference",
            value = if (latencyMs > 0) "${latencyMs}ms" else "--"
        )
        DiagnosticBadge(
            label = "Threads",
            value = if (threads > 0) "$threads CPU" else "Auto"
        )
        if (firstPartialMs > 0) DiagnosticBadge("First text", "${firstPartialMs}ms")
        if (finalLatencyMs > 0) DiagnosticBadge("Speech end", "${finalLatencyMs}ms")
        if (queueAgeMs > 0) DiagnosticBadge("Queue", "${queueAgeMs}ms")
    }
}
