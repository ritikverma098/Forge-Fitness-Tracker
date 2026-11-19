package com.forge.workout.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.forge.workout.R

@Composable
internal fun ForgePanel(
    modifier: Modifier = Modifier,
    emphasized: Boolean = false,
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val shape = MaterialTheme.shapes.large
    val border = BorderStroke(1.dp, if (emphasized) colors.primary.copy(alpha = .67f) else colors.outlineVariant)
    if (onClick != null) {
        Surface(onClick, modifier, shape = shape, color = colors.surfaceContainerLow, border = border) {
            Column(content = content)
        }
    } else {
        Surface(modifier, shape = shape, color = colors.surfaceContainerLow, border = border) {
            Column(content = content)
        }
    }
}

@Composable
internal fun ForgeIconTile(
    icon: ImageVector,
    modifier: Modifier = Modifier,
    highlighted: Boolean = false,
) {
    val colors = MaterialTheme.colorScheme
    Surface(
        modifier = modifier.size(42.dp),
        shape = MaterialTheme.shapes.small,
        color = if (highlighted) colors.primaryContainer else colors.surfaceContainerHigh,
        contentColor = if (highlighted) colors.onPrimaryContainer else colors.onSurfaceVariant,
    ) {
        Box(contentAlignment = Alignment.Center) { Icon(icon, contentDescription = null, Modifier.size(22.dp)) }
    }
}

@Composable
internal fun ForgeSectionHeading(title: String, modifier: Modifier = Modifier, subtitle: String? = null) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text(title, Modifier.semantics { heading() }, style = MaterialTheme.typography.titleLarge)
        subtitle?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}

@Composable
internal fun ForgeEyebrow(text: String, modifier: Modifier = Modifier) {
    Text(text, modifier, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelMedium)
}

@Composable
internal fun ForgeProgressBar(progress: Float, modifier: Modifier = Modifier) {
    val value = if (progress.isFinite()) progress.coerceIn(0f, 1f) else 0f
    LinearProgressIndicator(
        progress = { value },
        modifier = modifier.fillMaxWidth().height(8.dp),
        trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
        strokeCap = StrokeCap.Round,
        gapSize = 0.dp,
        drawStopIndicator = {},
    )
}

@Composable
internal fun ForgeSearchField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    clearDescription: String,
    modifier: Modifier = Modifier,
) {
    OutlinedTextField(
        value, onValueChange, modifier = modifier.fillMaxWidth(), singleLine = true,
        shape = MaterialTheme.shapes.medium,
        placeholder = { Text(placeholder) },
        leadingIcon = { Icon(Icons.Rounded.Search, null) },
        trailingIcon = {
            if (value.isNotEmpty()) IconButton(onClick = { onValueChange("") }) {
                Icon(Icons.Rounded.Close, clearDescription)
            }
        },
        colors = OutlinedTextFieldDefaults.colors(
            focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLow,
            unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
    )
}

@Composable
internal fun ForgeHero(
    icon: ImageVector,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    ForgePanel(modifier, emphasized = true) {
        Box(
            Modifier.fillMaxWidth().drawWithCache {
                val slash = Path().apply {
                    moveTo(size.width * .76f, 0f)
                    lineTo(size.width * .94f, 0f)
                    lineTo(size.width * .65f, size.height)
                    lineTo(size.width * .47f, size.height)
                    close()
                }
                val brush = Brush.verticalGradient(listOf(colors.primary.copy(alpha = .22f), colors.primary.copy(alpha = 0f)))
                onDrawBehind { drawPath(slash, brush) }
            },
        ) {
            Icon(
                icon, null, Modifier.align(Alignment.CenterEnd).padding(end = 18.dp).size(106.dp),
                tint = colors.primary.copy(alpha = .14f),
            )
            Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp), content = content)
        }
    }
}

@Composable
internal fun ForgeLoading(message: String, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth().padding(24.dp), Arrangement.spacedBy(12.dp), Alignment.CenterHorizontally) {
        CircularProgressIndicator()
        Text(message, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
    }
}
