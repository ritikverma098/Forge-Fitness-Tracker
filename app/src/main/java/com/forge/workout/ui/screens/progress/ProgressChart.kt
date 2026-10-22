package com.forge.workout.ui.screens.progress

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.forge.workout.R
import com.forge.workout.ui.components.dateFormat
import com.forge.workout.ui.components.numberFormat
import com.forge.workout.utils.MetricPoint
import kotlin.math.abs
import kotlin.math.roundToInt

internal typealias ChartPoint = MetricPoint

/** Zero baseline and timestamp spacing preserve the scale and gaps between observations. */
@Composable
internal fun ProgressChart(title: String, points: List<ChartPoint>, unit: String, weekly: Boolean = false) {
    val cleanPoints = remember(points) {
        points.map { it.copy(value = if (it.value.isFinite()) it.value.coerceAtLeast(0.0) else 0.0) }
    }
    Card(modifier = Modifier.fillMaxWidth().testTag("progress_chart_card"),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            if (cleanPoints.isEmpty()) {
                Text(stringResource(R.string.progress_chart_empty), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                ChartContent(title, cleanPoints, unit, weekly)
            }
        }
    }
}

@Composable
private fun ChartContent(title: String, points: List<ChartPoint>, unit: String, weekly: Boolean) {
    var selectedIndex by remember(points) { mutableIntStateOf(points.lastIndex) }
    val selected = points[selectedIndex.coerceIn(points.indices)]
    val accent = MaterialTheme.colorScheme.primary
    val lineColor = MaterialTheme.colorScheme.outlineVariant
    val maximum = remember(points) {
        val peak = points.maxOf { it.value }
        val padded = peak * 1.1
        (if (padded.isFinite()) padded else peak).coerceAtLeast(1.0)
    }
    val firstDate = points.first().date.toDouble()
    val dateSpan = (points.last().date.toDouble() - firstDate).coerceAtLeast(0.0)
    val selectedValue = stringResource(R.string.progress_metric_value, numberFormat(selected.value), unit)
    val selectedDescription = stringResource(R.string.progress_point_label, dateFormat(selected.date), selectedValue)
    val description = pluralStringResource(
        if (weekly) R.plurals.progress_weekly_chart_description else R.plurals.progress_chart_description,
        points.size,
        title,
        points.size,
        stringResource(R.string.progress_metric_value, numberFormat(points.first().value), unit),
        stringResource(R.string.progress_metric_value, numberFormat(points.last().value), unit),
    )
    val previousPoint = stringResource(if (weekly) R.string.progress_previous_week else R.string.progress_previous_session)
    val nextPoint = stringResource(if (weekly) R.string.progress_next_week else R.string.progress_next_session)

    Text(selectedDescription, style = MaterialTheme.typography.labelLarge, color = accent)
    Text(stringResource(R.string.progress_metric_value, numberFormat(maximum), unit),
        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Box(
        modifier = Modifier.fillMaxWidth().height(150.dp).testTag("progress_chart")
            .semantics {
                contentDescription = "$description $selectedDescription"
                customActions = listOf(
                    CustomAccessibilityAction(previousPoint) { selectedIndex = (selectedIndex - 1).coerceAtLeast(0); true },
                    CustomAccessibilityAction(nextPoint) { selectedIndex = (selectedIndex + 1).coerceAtMost(points.lastIndex); true },
                )
            }
            .pointerInput(points) {
                detectTapGestures { offset ->
                    val inset = 6.dp.toPx()
                    val width = (size.width - inset * 2).coerceAtLeast(1f)
                    val fraction = ((offset.x - inset) / width).coerceIn(0f, 1f)
                    selectedIndex = if (dateSpan > 0) nearestDateIndex(points, firstDate + dateSpan * fraction)
                        else (fraction * points.lastIndex).roundToInt().coerceIn(points.indices)
                }
            }
            .drawWithCache {
                val inset = 6.dp.toPx()
                val width = (size.width - inset * 2).coerceAtLeast(1f)
                val height = (size.height - inset * 2).coerceAtLeast(1f)
                val offsets = points.mapIndexed { index, point ->
                    val fraction = when {
                        points.size == 1 -> .5
                        dateSpan > 0 -> (point.date.toDouble() - firstDate) / dateSpan
                        else -> index.toDouble() / points.lastIndex
                    }
                    Offset(inset + width * fraction.toFloat(), inset + height * (1 - point.value / maximum).toFloat())
                }
                val path = Path().apply {
                    moveTo(offsets.first().x, offsets.first().y)
                    for (index in 1 until offsets.size) lineTo(offsets[index].x, offsets[index].y)
                }
                val fill = Path().apply {
                    moveTo(offsets.first().x, inset + height)
                    offsets.forEach { lineTo(it.x, it.y) }
                    lineTo(offsets.last().x, inset + height)
                    close()
                }
                val gradient = Brush.verticalGradient(listOf(accent.copy(alpha = .22f), accent.copy(alpha = .01f)))
                val stroke = Stroke(width = 2.5.dp.toPx(), cap = StrokeCap.Round)
                onDrawBehind {
                    repeat(4) { step ->
                        val y = inset + height * step / 3
                        drawLine(lineColor, Offset(inset, y), Offset(inset + width, y), strokeWidth = 1.dp.toPx())
                    }
                    if (offsets.size > 1) {
                        drawPath(fill, gradient)
                        drawPath(path, accent, style = stroke)
                    }
                    offsets.forEach { drawCircle(accent, radius = 2.5.dp.toPx(), center = it) }
                    val chosen = offsets[selectedIndex.coerceIn(offsets.indices)]
                    drawLine(accent.copy(alpha = .25f), Offset(chosen.x, inset), Offset(chosen.x, inset + height), strokeWidth = 1.dp.toPx())
                    drawCircle(accent, radius = 5.dp.toPx(), center = chosen)
                }
            },
    )
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(dateFormat(points.first().date), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (points.size > 1) Text(dateFormat(points.last().date),
            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    Text(stringResource(if (weekly) R.string.progress_week_selection_hint else R.string.progress_selection_hint),
        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

private fun nearestDateIndex(points: List<ChartPoint>, target: Double): Int {
    val found = points.binarySearch { it.date.toDouble().compareTo(target) }
    if (found >= 0) return found
    val after = (-found - 1).coerceAtMost(points.lastIndex)
    val before = (after - 1).coerceAtLeast(0)
    return if (abs(points[before].date.toDouble() - target) <= abs(points[after].date.toDouble() - target)) before else after
}
