package com.opentether.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.opentether.NodeStatus
import com.opentether.ThroughputSample
import com.opentether.runtime.TunnelPhase
import com.opentether.ui.theme.OtBlue
import com.opentether.ui.theme.OtGreen
import com.opentether.ui.theme.OtRed
import com.opentether.ui.theme.OtSurfaceAlt
import com.opentether.ui.theme.OtTextMuted
import com.opentether.ui.theme.OtYellow

// ── SectionCard ───────────────────────────────────────────────────────────────

@Composable
fun SectionCard(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(
        modifier = modifier
            .border(
                width = 1.dp,
                color = MaterialTheme.colorScheme.outline.copy(alpha = 0.55f),
                shape = MaterialTheme.shapes.medium,
            ),
        color = MaterialTheme.colorScheme.surface,
        shape = MaterialTheme.shapes.medium,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                if (subtitle != null) {
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            content()
        }
    }
}

// ── MetricTile ────────────────────────────────────────────────────────────────

@Composable
fun MetricTile(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    accent: Color,
) {
    Surface(
        modifier = modifier,
        color = OtSurfaceAlt,
        shape = RoundedCornerShape(14.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = label.uppercase(),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = value,
                style = MaterialTheme.typography.titleLarge,
                color = accent,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

// ── StatusPill ────────────────────────────────────────────────────────────────
// Pulses the background alpha when in a transitional state.

@Composable
fun StatusPill(
    text: String,
    phase: TunnelPhase,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
) {
    val targetColor = when (phase) {
        TunnelPhase.Connected -> OtGreen
        TunnelPhase.Error -> OtRed
        TunnelPhase.Starting, TunnelPhase.Connecting, TunnelPhase.AwaitingTransport, TunnelPhase.Stopping -> OtYellow
        TunnelPhase.Idle -> OtTextMuted
    }
    val isPulsing = phase in setOf(
        TunnelPhase.Starting,
        TunnelPhase.AwaitingTransport,
        TunnelPhase.Connecting,
    )

    val dotColor by animateColorAsState(
        targetValue = targetColor,
        animationSpec = tween(durationMillis = 300, easing = FastOutSlowInEasing),
        label = "statusDot",
    )

    val infiniteTransition = rememberInfiniteTransition(label = "statusPulse")
    val pulseAlpha by infiniteTransition.animateFloat(
        initialValue = 0.14f,
        targetValue = 0.30f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 700, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "pulseAlpha",
    )
    val staticAlpha by animateColorAsState(
        targetValue = targetColor.copy(alpha = 0.18f),
        animationSpec = tween(durationMillis = 300),
        label = "staticContainer",
    )

    val containerColor = if (isPulsing) dotColor.copy(alpha = pulseAlpha) else staticAlpha

    Row(
        modifier = modifier
            .background(containerColor, RoundedCornerShape(999.dp))
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .background(dotColor, RoundedCornerShape(999.dp)),
        )
        if (!compact) {
            Text(
                text = text,
                style = MaterialTheme.typography.labelMedium,
                color = dotColor,
            )
        }
    }
}

// ── NodeStatusPill ────────────────────────────────────────────────────────────

@Composable
fun NodeStatusPill(
    status: NodeStatus,
    modifier: Modifier = Modifier,
) {
    val (label, color) = when (status) {
        NodeStatus.Active -> "ACTIVE" to OtGreen
        NodeStatus.Warning -> "WARN" to OtYellow
        NodeStatus.Error -> "ERROR" to OtRed
        NodeStatus.Idle -> "IDLE" to OtTextMuted
    }
    Row(
        modifier = modifier
            .background(color.copy(alpha = 0.18f), RoundedCornerShape(999.dp))
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(
            modifier = Modifier
                .size(6.dp)
                .background(color, RoundedCornerShape(999.dp)),
        )
        Text(text = label, style = MaterialTheme.typography.labelMedium, color = color)
    }
}

// ── TrafficChart ──────────────────────────────────────────────────────────────

@Composable
fun TrafficChart(
    points: List<ThroughputSample>,
    modifier: Modifier = Modifier,
) {
    val paddedPoints = points.takeLast(30)
    val safePoints = List((30 - paddedPoints.size).coerceAtLeast(0)) {
        ThroughputSample(0, 0, 0)
    } + paddedPoints
    val maxBytes = safePoints
        .maxOfOrNull { maxOf(it.downloadBytesPerSec, it.uploadBytesPerSec) }
        ?.coerceAtLeast(1L) ?: 1L
    val strokePx = 4.dp
    val gridColor = MaterialTheme.colorScheme.outline

    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 160.dp)
            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(14.dp))
            .padding(12.dp),
    ) {
        val w = size.width
        val h = size.height
        val step = if (safePoints.size > 1) w / (safePoints.size - 1) else w

        // Grid background
        drawRect(color = gridColor.copy(alpha = 0.12f), size = Size(w, h))
        repeat(3) { i ->
            val y = h / 4f * (i + 1)
            drawLine(
                color = gridColor.copy(alpha = 0.25f),
                start = Offset(0f, y),
                end = Offset(w, y),
                strokeWidth = 1.dp.toPx(),
            )
        }

        // Shared path builder — avoids duplicating the index/ratio math
        fun buildPath(selector: (ThroughputSample) -> Long, filled: Boolean): Path {
            val path = Path()
            safePoints.forEachIndexed { idx, sample ->
                val x = step * idx
                val y = h - (selector(sample).toFloat() / maxBytes * h * 0.88f) - (h * 0.06f)
                when {
                    idx == 0 && filled -> { path.moveTo(x, h); path.lineTo(x, y) }
                    idx == 0 -> path.moveTo(x, y)
                    else -> path.lineTo(x, y)
                }
            }
            if (filled) { path.lineTo(w, h); path.close() }
            return path
        }

        // Download fill + line
        drawPath(
            path = buildPath({ it.downloadBytesPerSec }, filled = true),
            color = OtGreen.copy(alpha = 0.16f),
            style = Fill,
        )
        drawPath(
            path = buildPath({ it.downloadBytesPerSec }, filled = false),
            color = OtGreen,
            style = Stroke(width = strokePx.toPx(), cap = StrokeCap.Round),
        )
        // Upload line only
        drawPath(
            path = buildPath({ it.uploadBytesPerSec }, filled = false),
            color = OtBlue,
            style = Stroke(width = strokePx.toPx(), cap = StrokeCap.Round),
        )
    }
}

// ── LegendRow ─────────────────────────────────────────────────────────────────

@Composable
fun LegendRow(
    label: String,
    color: Color,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(
            modifier = Modifier
                .size(width = 16.dp, height = 3.dp)
                .background(color, RoundedCornerShape(999.dp)),
        )
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

// ── TerminalText ──────────────────────────────────────────────────────────────

@Composable
fun TerminalText(
    text: String,
    color: Color,
    modifier: Modifier = Modifier,
    bold: Boolean = false,
) {
    Text(
        text = text,
        modifier = modifier,
        style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
        color = color,
        fontWeight = if (bold) FontWeight.SemiBold else FontWeight.Normal,
    )
}

// ── EmptyState ────────────────────────────────────────────────────────────────

@Composable
fun EmptyState(
    title: String,
    message: String,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(14.dp))
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
