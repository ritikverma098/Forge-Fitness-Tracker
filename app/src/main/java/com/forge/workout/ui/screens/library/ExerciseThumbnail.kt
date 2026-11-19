package com.forge.workout.ui.screens.library

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.DirectionsRun
import androidx.compose.material.icons.rounded.FitnessCenter
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.unit.dp
import com.forge.workout.model.MuscleGroup

@Composable
internal fun ExerciseThumbnail(group: MuscleGroup, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    Surface(modifier.size(42.dp), shape = MaterialTheme.shapes.medium, color = colors.primaryContainer) {
        Box(contentAlignment = Alignment.Center) {
            val icon = when (group) {
                MuscleGroup.CARDIO -> Icons.AutoMirrored.Rounded.DirectionsRun
                MuscleGroup.OTHER -> Icons.Rounded.FitnessCenter
                else -> null
            }
            if (icon != null) {
                Icon(icon, null, Modifier.size(23.dp), tint = colors.onPrimaryContainer)
            } else {
                Canvas(Modifier.size(26.dp)) {
                    val unit = size.minDimension / 24f
                    val accent = colors.onPrimaryContainer
                    val neutral = colors.onPrimaryContainer.copy(alpha = .4f)
                    for (segment in bodySegments) {
                        val selected = group == MuscleGroup.FULL_BODY || segment.region == group
                        if (segment.region == MuscleGroup.TRICEPS && !selected) continue
                        drawLine(if (selected) accent else neutral,
                            Offset(segment.x1 * unit, segment.y1 * unit), Offset(segment.x2 * unit, segment.y2 * unit),
                            strokeWidth = (if (selected) 2.5f else 1.5f) * unit, cap = StrokeCap.Round)
                    }
                    drawCircle(neutral, radius = 2.1f * unit, center = Offset(12f * unit, 3.1f * unit))
                }
            }
        }
    }
}

private data class BodySegment(val x1: Float, val y1: Float, val x2: Float, val y2: Float, val region: MuscleGroup)

private val bodySegments = listOf(
    BodySegment(12f, 6f, 12f, 12f, MuscleGroup.BACK),
    BodySegment(8f, 7f, 16f, 7f, MuscleGroup.SHOULDERS),
    BodySegment(9.5f, 8.8f, 14.5f, 8.8f, MuscleGroup.CHEST),
    BodySegment(10.5f, 11f, 13.5f, 11f, MuscleGroup.CORE),
    BodySegment(10f, 13.5f, 14f, 13.5f, MuscleGroup.GLUTES),
    BodySegment(7.8f, 7.5f, 6f, 11.5f, MuscleGroup.BICEPS),
    BodySegment(16.2f, 7.5f, 18f, 11.5f, MuscleGroup.BICEPS),
    BodySegment(6.2f, 7.8f, 4.6f, 11.5f, MuscleGroup.TRICEPS),
    BodySegment(17.8f, 7.8f, 19.4f, 11.5f, MuscleGroup.TRICEPS),
    BodySegment(6f, 12f, 4.8f, 16f, MuscleGroup.FOREARMS),
    BodySegment(18f, 12f, 19.2f, 16f, MuscleGroup.FOREARMS),
    BodySegment(10.5f, 14f, 9f, 18f, MuscleGroup.LEGS),
    BodySegment(13.5f, 14f, 15f, 18f, MuscleGroup.LEGS),
    BodySegment(9f, 18.5f, 8f, 22.5f, MuscleGroup.CALVES),
    BodySegment(15f, 18.5f, 16f, 22.5f, MuscleGroup.CALVES),
)
