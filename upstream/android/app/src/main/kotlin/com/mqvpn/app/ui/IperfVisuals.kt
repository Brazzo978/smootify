// SPDX-License-Identifier: Apache-2.0
package com.mqvpn.app.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.mqvpn.app.iperf.IperfInterval
import com.mqvpn.app.iperf.IperfProtocol
import com.mqvpn.app.iperf.IperfDirection
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import java.util.Locale

/** A tap toggles live and peak; every displayed value comes from iperf intervals. */
@Composable
internal fun IperfGauge(protocol: IperfProtocol?, running: Boolean,
    intervals: List<IperfInterval>, finalMbps: Double?, modifier: Modifier = Modifier) {
    var showPeak by remember { mutableStateOf(false) }
    val measured = intervals.map { it.mbps }.filter { it.isFinite() && it >= 0.0 }
    val live = if (running) measured.lastOrNull() else finalMbps ?: measured.lastOrNull()
    val peak = measured.maxOrNull()
    val shown = if (showPeak) peak else live
    val scale = niceScale(maxOf(10.0, measured.maxOrNull() ?: 0.0, finalMbps ?: 0.0))
    val fraction by animateFloatAsState(
        targetValue = ((shown ?: 0.0) / scale).coerceIn(0.0, 1.0).toFloat(),
        animationSpec = tween(650), label = "Iperf gauge")
    val track = MaterialTheme.colorScheme.surfaceVariant
    val accent = if (protocol == IperfProtocol.UDP) MaterialTheme.colorScheme.tertiary
        else MaterialTheme.colorScheme.primary
    val ink = MaterialTheme.colorScheme.onSurface
    Box(modifier.fillMaxWidth().height(250.dp).clickable { showPeak = !showPeak },
        contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxWidth().height(235.dp)) {
            val radius = min(size.width * 0.39f, size.height * 0.43f)
            val center = Offset(size.width / 2f, size.height * 0.55f)
            val topLeft = Offset(center.x - radius, center.y - radius)
            val arcSize = Size(radius * 2f, radius * 2f)
            val stroke = Stroke(width = 15.dp.toPx(), cap = StrokeCap.Round)
            drawArc(track, 135f, 270f, false, topLeft, arcSize, style = stroke)
            drawArc(accent, 135f, 270f * fraction, false, topLeft, arcSize, style = stroke)
            for (i in 0..9) {
                val angle = Math.toRadians((135.0 + 270.0 * i / 9.0))
                val inner = radius - 28.dp.toPx()
                val outer = radius - 17.dp.toPx()
                drawLine(ink.copy(alpha = .36f),
                    Offset(center.x + cos(angle).toFloat() * inner, center.y + sin(angle).toFloat() * inner),
                    Offset(center.x + cos(angle).toFloat() * outer, center.y + sin(angle).toFloat() * outer),
                    1.3.dp.toPx())
            }
            if (shown != null) {
                val angle = Math.toRadians(135.0 + 270.0 * fraction)
                val marker = Offset(center.x + cos(angle).toFloat() * radius,
                    center.y + sin(angle).toFloat() * radius)
                drawCircle(accent, radius = 8.dp.toPx(), center = marker)
                drawCircle(track, radius = 3.dp.toPx(), center = marker)
            }
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(top = 34.dp)) {
            Text(when {
                showPeak -> "INTERVAL PEAK"
                running -> "IPERF SPEED"
                finalMbps != null -> "IPERF RESULT"
                measured.isNotEmpty() -> "LAST INTERVAL · INCOMPLETE TEST"
                else -> "READY TO TEST"
            },
                style = MaterialTheme.typography.labelMedium, color = ink.copy(alpha = .7f))
            Text(shown?.let { String.format(Locale.ITALY, "%.1f", it) } ?: "—",
                style = MaterialTheme.typography.headlineLarge, color = ink,
                maxLines = 1, textAlign = TextAlign.Center)
            Text("Mbit/s", style = MaterialTheme.typography.titleMedium, color = accent)
            Text("Tap for ${if (showPeak) "speed" else "peak"} · scale 0–${scale.toInt()}",
                style = MaterialTheme.typography.labelSmall, color = ink.copy(alpha = .65f))
        }
    }
}

/** Shared y-axis for all paths; counters include tunnel overhead and other apps. */
@Composable
internal fun IperfPathChart(chart: BandwidthHistoryState, direction: IperfDirection,
    labels: Map<String, String>, modifier: Modifier = Modifier) {
    val samples = chart.samples.drop(1) // first tick establishes a counter baseline
    val ifaces = chart.ifaceSlots.keys.toList()
    var isolated by remember { mutableStateOf<String?>(null) }
    var hidden by remember { mutableStateOf<String?>(null) }
    val visible = ifaces.filter { (isolated == null || it == isolated) && it != hidden }
    val colors = listOf(Color(0xFF2D9D8F), Color(0xFFEF7B45), Color(0xFF7488E9),
        Color(0xFFCE66A3), Color(0xFFC8A53C), Color(0xFF4FA7D8))
    fun bps(sample: BandwidthSample, iface: String): Long =
        (if (direction == IperfDirection.DOWN) sample.perPathRxBps else sample.perPathTxBps)[iface] ?: 0L
    val scale = niceScale((samples.maxOfOrNull { sample ->
        visible.maxOfOrNull { bps(sample, it).toDouble() / 1_000_000.0 } ?: 0.0
    } ?: 0.0).coerceAtLeast(1.0))
    val reveal by animateFloatAsState(samples.size.toFloat(), tween(550), label = "Path samples")
    val grid = MaterialTheme.colorScheme.outlineVariant
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("${String.format(Locale.ITALY, "%.0f", scale)} Mbit/s",
            style = MaterialTheme.typography.labelSmall)
        Canvas(Modifier.fillMaxWidth().height(170.dp)) {
            for (i in 0..4) {
                val y = size.height * i / 4f
                drawLine(grid, Offset(0f, y), Offset(size.width, y), 1.dp.toPx())
            }
            val maxPoints = (samples.size - 1).coerceAtLeast(1).toFloat()
            visible.forEach { iface ->
                val color = colors[(chart.ifaceSlots[iface] ?: 0) % colors.size]
                val path = Path()
                val count = min(samples.size, kotlin.math.ceil(reveal.toDouble()).toInt())
                for (i in 0 until count) {
                    val previous = samples.getOrNull(i - 1)
                    val current = samples[i]
                    val value = if (i == count - 1 && reveal < samples.size && previous != null) {
                        val t = (reveal - (count - 1)).coerceIn(0f, 1f)
                        bps(previous, iface) + ((bps(current, iface) - bps(previous, iface)) * t).toLong()
                    } else bps(current, iface)
                    val x = (i / maxPoints) * size.width
                    val y = size.height * (1f - (value / 1_000_000.0 / scale).coerceIn(0.0, 1.0).toFloat())
                    if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
                }
                drawPath(path, color, style = Stroke(width = 2.5.dp.toPx(), cap = StrokeCap.Round))
            }
        }
        Text("0 Mbit/s · samples every second · shared axis",
            style = MaterialTheme.typography.labelSmall)
        Row(Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            ifaces.forEach { iface ->
                val label = labels[iface] ?: iface
                val text = when {
                    isolated == iface -> "Only $label"
                    hidden == iface -> "$label nascosto"
                    else -> label
                }
                FilterChip(selected = isolated == iface, onClick = {
                    when {
                        isolated == iface -> { isolated = null; hidden = iface }
                        hidden == iface -> hidden = null
                        else -> { isolated = iface; hidden = null }
                    }
                }, label = { Text(text, color = colors[(chart.ifaceSlots[iface] ?: 0) % colors.size]) })
            }
        }
        Text("Tap a connection to isolate, hide, or show all. Counters also include overhead and other apps.",
            style = MaterialTheme.typography.bodySmall)
    }
}

private fun niceScale(value: Double): Double {
    val marks = listOf(1.0, 2.0, 5.0)
    var magnitude = 1.0
    while (magnitude < 1e9) {
        for (mark in marks) if (mark * magnitude >= value) return mark * magnitude
        magnitude *= 10
    }
    return magnitude
}



