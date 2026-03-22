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
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Inbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.opentether.NodeStatus
import com.opentether.ThroughputSample
import com.opentether.runtime.TunnelPhase
import com.opentether.ui.theme.OtBlue
import com.opentether.ui.theme.OtGreen
import com.opentether.ui.theme.OtGreenBright
import com.opentether.ui.theme.OtRed
import com.opentether.ui.theme.OtTextMuted
import com.opentether.ui.theme.OtYellow

// ── SectionCard ───────────────────────────────────────────────────────────────
// Uses OtSurfaceAlt as the card background color so cards read clearly above the
// OtBackground page surface. Outline alpha raised to 0.75 for stronger definition.
// A subtle HorizontalDivider separates the header block from the content area.

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
                color = MaterialTheme.colorScheme.outline.copy(alpha = 0.75f),
                shape = MaterialTheme.shapes.medium,
            ),
        color = MaterialTheme.colorScheme.surfaceVariant,
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
            HorizontalDivider(
                color = MaterialTheme.colorScheme.outline.copy(alpha = 0.35f),
                thickness = 1.dp,
            )
            content()
        }
    }
}

// ── MetricTile ────────────────────────────────────────────────────────────────
// Value uses headlineMedium (28 sp bold) for strong visual weight vs the label.
// A thin left accent bar reinforces the per-metric color at a glance.
// IntrinsicSize.Min ensures the bar fills the card height regardless of content.

@Composable
fun MetricTile(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    accent: Color,
) {
    Surface(
        modifier = modifier,
        color = MaterialTheme.colorScheme.background,
        shape = MaterialTheme.shapes.medium,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(IntrinsicSize.Min),
        ) {
            // Left accent bar — fills the intrinsic height of the row
            Box(
                modifier = Modifier
                    .width(3.dp)
                    .fillMaxHeight()
                    .background(
                        color = accent.copy(alpha = 0.55f),
                        shape = RoundedCornerShape(topStart = 16.dp, bottomStart = 16.dp),
                    ),
            )
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 14.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = label.uppercase(),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    letterSpacing = 0.8.sp,
                )
                Text(
                    text = value,
                    style = MaterialTheme.typography.headlineMedium,
                    color = accent,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
    }
}

// ── StatusPill ────────────────────────────────────────────────────────────────
// Pulses the background alpha when in a transitional state.
// Connected state gets a subtle glowing border using OtGreenBright.
// OtGreenBright replaces OtGreen for dot/text in Connected to meet WCAG AA.

@Composable
fun StatusPill(
    text: String,
    phase: TunnelPhase,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
) {
    val targetColor = when (phase) {
        TunnelPhase.Connected -> OtGreenBright
        TunnelPhase.Error -> OtRed
        TunnelPhase.Starting, TunnelPhase.Connecting, TunnelPhase.AwaitingTransport, TunnelPhase.Stopping -> OtYellow
        TunnelPhase.Idle -> OtTextMuted
    }
    val isPulsing = phase in setOf(
        TunnelPhase.Starting,
        TunnelPhase.AwaitingTransport,
        TunnelPhase.Connecting,
    )
    val isConnected = phase == TunnelPhase.Connected

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
    val borderColor by animateColorAsState(
        targetValue = if (isConnected) OtGreenBright.copy(alpha = 0.40f) else Color.Transparent,
        animationSpec = tween(durationMillis = 400),
        label = "pillBorder",
    )

    Row(
        modifier = modifier
            .border(width = 1.dp, color = borderColor, shape = RoundedCornerShape(999.dp))
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
// Active nodes use OtGreenBright for WCAG AA text contrast.

@Composable
fun NodeStatusPill(
    status: NodeStatus,
    modifier: Modifier = Modifier,
) {
    val (label, color) = when (status) {
        NodeStatus.Active -> "ACTIVE" to OtGreenBright
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
// Both download and upload now have filled areas under their curves.
// Upload fill uses a slightly lower alpha (0.10 vs 0.16) to preserve hierarchy.
// Download line uses OtGreenBright for better contrast against the dark chart bg.

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
            .background(MaterialTheme.colorScheme.background, RoundedCornerShape(14.dp))
            .padding(12.dp),
    ) {
        val w = size.width
        val h = size.height
        val step = if (safePoints.size > 1) w / (safePoints.size - 1) else w

        // Grid background
        drawRect(color = gridColor.copy(alpha = 0.08f), size = Size(w, h))
        repeat(3) { i ->
            val y = h / 4f * (i + 1)
            drawLine(
                color = gridColor.copy(alpha = 0.20f),
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
            color = OtGreenBright,
            style = Stroke(width = strokePx.toPx(), cap = StrokeCap.Round),
        )

        // Upload fill + line
        drawPath(
            path = buildPath({ it.uploadBytesPerSec }, filled = true),
            color = OtBlue.copy(alpha = 0.10f),
            style = Fill,
        )
        drawPath(
            path = buildPath({ it.uploadBytesPerSec }, filled = false),
            color = OtBlue,
            style = Stroke(width = strokePx.toPx(), cap = StrokeCap.Round),
        )
    }
}

// ── LegendRow ─────────────────────────────────────────────────────────────────
// Color swatch upgraded from a thin 16×3 hairline to a 12×12 rounded square
// that's clearly scannable at a glance.

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
                .size(12.dp)
                .background(color, RoundedCornerShape(4.dp)),
        )
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

// ── TerminalText ──────────────────────────────────────────────────────────────
// Uses labelSmall (11 sp with FontFamily.Monospace — defined in the theme) rather
// than bodyMedium copied with Monospace, to read clearly as secondary/technical text.
// maxLines + ellipsis guard against long technical strings overflowing the card.

@Composable
fun TerminalText(
    text: String,
    color: Color,
    modifier: Modifier = Modifier,
    bold: Boolean = false,
    maxLines: Int = Int.MAX_VALUE,
) {
    Text(
        text = text,
        modifier = modifier,
        style = MaterialTheme.typography.labelSmall,
        color = color,
        fontWeight = if (bold) FontWeight.SemiBold else FontWeight.Normal,
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis,
    )
}

// ── EmptyState ────────────────────────────────────────────────────────────────
// Re-designed with a centered icon (48 dp, muted tint) above the headline text
// so every empty screen shows intentional content instead of a blank void.
// Accepts a custom icon so callers can pass a context-specific glyph.

@Composable
fun EmptyState(
    title: String,
    message: String,
    modifier: Modifier = Modifier,
    icon: androidx.compose.ui.graphics.vector.ImageVector = Icons.Outlined.Inbox,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.background, RoundedCornerShape(14.dp))
            .padding(horizontal = 24.dp, vertical = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(48.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f),
        )
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}
