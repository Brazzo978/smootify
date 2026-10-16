// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2026 mp0rta and mqvpn contributors

package com.mqvpn.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp

private val PATH_PALETTE = listOf(
    Color(0xFF26A69A), // teal
    Color(0xFFFF9800), // orange
    Color(0xFFAB47BC), // purple
    Color(0xFF42A5F5), // blue
    Color(0xFFEF5350), // red
    Color(0xFF9CCC65), // light green
)

private fun slotColor(slot: Int): Color = PATH_PALETTE[slot % PATH_PALETTE.size]

private data class PlotSeries(val values: List<Long?>, val color: Color, val dashed: Boolean = false)

@Composable
fun BandwidthChart(state: BandwidthHistoryState) {
    val samples = state.samples
    if (samples.isEmpty()) return

    val ifaces = samples.asSequence().flatMap { it.perPathBps.keys }
        .distinct().sortedBy { state.ifaceSlots[it] ?: Int.MAX_VALUE }.toList()
    val totalColor = MaterialTheme.colorScheme.onSurface
    val surfaceColor = MaterialTheme.colorScheme.surfaceVariant
    val gridColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.4f)
    val labelStyle = MaterialTheme.typography.labelSmall
        .copy(color = MaterialTheme.colorScheme.onSurfaceVariant)
    val textMeasurer = rememberTextMeasurer()

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Total RX + TX", style = MaterialTheme.typography.labelMedium)
            Text(formatBps(samples.last().totalBps), style = MaterialTheme.typography.labelMedium)
        }
        RatePlot(
            series = listOf(PlotSeries(samples.map { it.totalBps }, totalColor)),
            top = ceilNice(samples.maxOf { it.totalBps }),
            heightDp = 116,
            surfaceColor = surfaceColor,
            gridColor = gridColor,
            labelStyle = labelStyle,
            textMeasurer = textMeasurer,
        )

        // Each interface gets its own scale. A slow SIM stays legible alongside a
        // much faster one, and TX/RX use the actual independent path counters.
        ifaces.forEach { iface ->
            val color = slotColor(state.ifaceSlots[iface] ?: 0)
            val tx = samples.map { it.perPathTxBps[iface] }
            val rx = samples.map { it.perPathRxBps[iface] }
            val currentTx = samples.last().perPathTxBps[iface]
            val currentRx = samples.last().perPathRxBps[iface]
            val maxPath = maxOf(tx.filterNotNull().maxOrNull() ?: 0L,
                rx.filterNotNull().maxOrNull() ?: 0L)
            Column {
                Text(iface, style = MaterialTheme.typography.labelMedium, color = color)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    LegendEntry("RX " + (currentRx?.let(::formatBps) ?: "inactive"), color, dashed = false)
                    LegendEntry("TX " + (currentTx?.let(::formatBps) ?: "inactive"), color, dashed = true)
                }
                RatePlot(
                    series = listOf(PlotSeries(tx, color, dashed = true), PlotSeries(rx, color)),
                    top = ceilNice(maxPath),
                    heightDp = 92,
                    surfaceColor = surfaceColor,
                    gridColor = gridColor,
                    labelStyle = labelStyle,
                    textMeasurer = textMeasurer,
                )
            }
        }
    }
}

@Composable
private fun RatePlot(
    series: List<PlotSeries>,
    top: Long,
    heightDp: Int,
    surfaceColor: Color,
    gridColor: Color,
    labelStyle: TextStyle,
    textMeasurer: TextMeasurer,
) {
    Canvas(modifier = Modifier.fillMaxWidth().height(heightDp.dp).padding(top = 2.dp)) {
        drawRect(surfaceColor)
        val strokeInset = 1.5.dp.toPx()
        fun yFor(bps: Long): Float =
            size.height - strokeInset -
                (bps.toFloat() / top) * (size.height - 2 * strokeInset)

        drawGridLines(gridColor, strokeInset)
        val dash = PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 4.dp.toPx()))
        val stepX = size.width / (BandwidthHistory.MAX_SAMPLES - 1)
        series.forEach { s ->
            val startOffset = (BandwidthHistory.MAX_SAMPLES - s.values.size) * stepX
            for (i in 1 until s.values.size) {
                val a = s.values[i - 1] ?: continue
                val b = s.values[i] ?: continue
                drawLine(
                    s.color,
                    Offset(startOffset + (i - 1) * stepX, yFor(a)),
                    Offset(startOffset + i * stepX, yFor(b)),
                    strokeWidth = (if (s.dashed) 2f else 2.5f).dp.toPx(),
                    pathEffect = if (s.dashed) dash else null,
                )
            }
        }
        drawGridLabels(top, strokeInset, textMeasurer, labelStyle)
    }
}

@Composable
private fun LegendEntry(label: String, color: Color, dashed: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Canvas(modifier = Modifier.width(16.dp).height(3.dp)) {
            drawLine(
                color, Offset(0f, size.height / 2), Offset(size.width, size.height / 2),
                strokeWidth = 2.dp.toPx(),
                pathEffect = if (dashed) PathEffect.dashPathEffect(
                    floatArrayOf(4.dp.toPx(), 3.dp.toPx())) else null,
            )
        }
        Text(" " + label, style = MaterialTheme.typography.labelSmall)
    }
}

private fun DrawScope.gridY(frac: Float, inset: Float): Float =
    inset + frac * (size.height - 2 * inset)

private fun DrawScope.drawGridLines(gridColor: Color, inset: Float) {
    val dashPx = 6.dp.toPx()
    val dash = PathEffect.dashPathEffect(floatArrayOf(dashPx, dashPx))
    for (frac in listOf(0f, 0.5f)) {
        val y = gridY(frac, inset)
        drawLine(gridColor, Offset(0f, y), Offset(size.width, y), pathEffect = dash)
    }
    val y0 = gridY(1f, inset)
    drawLine(gridColor, Offset(0f, y0), Offset(size.width, y0))
}

private fun DrawScope.drawGridLabels(
    top: Long,
    inset: Float,
    textMeasurer: TextMeasurer,
    labelStyle: TextStyle,
) {
    val pad = 4.dp.toPx()
    for ((frac, bps) in listOf(0f to top, 0.5f to top / 2)) {
        drawText(
            textMeasurer.measure(formatBps(bps), labelStyle),
            topLeft = Offset(pad, gridY(frac, inset) + pad / 2),
        )
    }
    val zeroLayout = textMeasurer.measure("0", labelStyle)
    drawText(
        zeroLayout,
        topLeft = Offset(pad, size.height - zeroLayout.size.height - pad / 2),
    )

    val nowLayout = textMeasurer.measure("now", labelStyle)
    val midLayout = textMeasurer.measure("-30s", labelStyle)
    val yTime = size.height - nowLayout.size.height - pad / 2
    drawText(
        textMeasurer.measure("-60s", labelStyle),
        topLeft = Offset(size.width * 0.02f + pad * 8, yTime),
    )
    drawText(midLayout, topLeft = Offset((size.width - midLayout.size.width) / 2f, yTime))
    drawText(nowLayout, topLeft = Offset(size.width - nowLayout.size.width - pad, yTime))
}

