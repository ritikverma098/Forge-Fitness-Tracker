package com.forge.workout.ui.screens.nutrition

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DatePickerState
import androidx.compose.material3.DisplayMode
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.forge.workout.R
import com.forge.workout.ui.components.ForgeIconTile
import com.forge.workout.ui.components.ForgePanel
import com.forge.workout.ui.components.ForgeSectionHeading
import java.time.LocalDate
import java.time.ZoneOffset

@Composable
internal fun NutritionCard(
    modifier: Modifier = Modifier,
    emphasized: Boolean = false,
    content: @Composable ColumnScope.() -> Unit,
) {
    ForgePanel(modifier.fillMaxWidth(), emphasized) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp), content = content)
    }
}

@Composable
internal fun NutritionRow(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    ForgePanel(modifier.fillMaxWidth(), onClick = onClick) { content() }
}

@Composable
internal fun NutritionSection(title: String, icon: ImageVector, subtitle: String? = null) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically) {
        ForgeIconTile(icon, highlighted = true)
        ForgeSectionHeading(title, Modifier.weight(1f), subtitle)
    }
}

@Composable
internal fun NutritionLoading(label: String, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth().padding(20.dp), horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically) {
        CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
internal fun NutritionSaveLabel(label: String, busy: Boolean) {
    if (busy) {
        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(8.dp))
    }
    Text(if (busy) stringResource(R.string.nutrition_premium_saving) else label)
}

@Composable
internal fun NutritionFilterChip(
    selected: Boolean,
    onClick: () -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    icon: ImageVector? = null,
) {
    val colors = MaterialTheme.colorScheme
    FilterChip(
        selected, onClick, label = { Text(label) }, modifier = modifier,
        enabled = enabled,
        shape = MaterialTheme.shapes.medium,
        colors = FilterChipDefaults.filterChipColors(
            containerColor = colors.surfaceContainerLow,
            selectedContainerColor = colors.primary,
            selectedLabelColor = colors.onPrimary,
            selectedLeadingIconColor = colors.onPrimary,
        ),
        leadingIcon = if (icon == null) null else { { Icon(icon, null, Modifier.size(18.dp)) } },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun rememberNutritionDatePickerState(date: LocalDate): DatePickerState {
    val size = LocalWindowInfo.current.containerSize
    val useInput = with(LocalDensity.current) { size.width.toDp() < 360.dp || size.height.toDp() < 480.dp || fontScale >= 1.3f }
    return rememberDatePickerState(
        initialSelectedDateMillis = date.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
        yearRange = 1..9999,
        initialDisplayMode = if (useInput) DisplayMode.Input else DisplayMode.Picker,
    )
}
