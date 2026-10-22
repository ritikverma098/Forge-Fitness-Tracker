package com.forge.workout.ui.screens.workout

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Timer
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.forge.workout.R
import com.forge.workout.ui.components.RestNotificationPermissionHint
import com.forge.workout.ui.components.durationFormat
import com.forge.workout.viewmodel.WorkoutViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

@Composable
internal fun WorkoutElapsedTime(startTime: Long, endTime: Long?, modifier: Modifier = Modifier) {
    val timestamp = if (endTime != null) endTime else {
        val now by lifecycleWallClock(startTime)
        now
    }
    Text(durationFormat(nonnegativeDifference(timestamp, startTime) / 1_000),
        modifier.testTag("workout_elapsed_time"), style = MaterialTheme.typography.titleMedium)
}

@Composable
internal fun WorkoutRestSection(
    workoutId: Long,
    deadline: Long?,
    viewModel: WorkoutViewModel,
    modifier: Modifier = Modifier,
) {
    if (deadline == null) return
    val now by lifecycleWallClock(workoutId to deadline)
    val remainingMillis = nonnegativeDifference(deadline, now)
    val remainingSeconds = remainingMillis / 1_000 + if (remainingMillis % 1_000 == 0L) 0 else 1
    Column(modifier) {
        AnimatedVisibility(remainingSeconds > 0) {
            Column {
                RestTimerBar(remainingSeconds, onSkip = { viewModel.rest(workoutId, null) },
                    onAdjust = { viewModel.adjustRest(workoutId, it) })
                RestNotificationPermissionHint(Modifier.padding(horizontal = 20.dp))
            }
        }
    }
}

/** Each clock owns its updates, and catches up from wall time immediately after returning. */
@Composable
private fun lifecycleWallClock(identity: Any): State<Long> {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    return produceState(initialValue = System.currentTimeMillis(), key1 = lifecycle, key2 = identity) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (isActive) {
                value = System.currentTimeMillis()
                delay(1_000)
            }
        }
    }
}

private fun nonnegativeDifference(later: Long, earlier: Long): Long {
    if (later <= earlier) return 0
    // Saturate if a malformed timestamp spans more than the signed Long range.
    return (later - earlier).takeIf { it >= 0 } ?: Long.MAX_VALUE
}

@Composable
private fun RestTimerBar(remaining: Long, onSkip: () -> Unit, onAdjust: (Int) -> Unit) {
    val remainingDescription = stringResource(R.string.rest_remaining, durationFormat(remaining))
    val subtractDescription = stringResource(R.string.rest_subtract_description)
    val addDescription = stringResource(R.string.rest_add_description)
    Surface(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp).testTag("rest_timer_bar"),
        color = MaterialTheme.colorScheme.primaryContainer,
        shape = RoundedCornerShape(20.dp),
    ) {
        FlowRow(Modifier.padding(horizontal = 8.dp), verticalArrangement = Arrangement.Center,
            horizontalArrangement = Arrangement.SpaceBetween, itemVerticalAlignment = Alignment.CenterVertically) {
            Row(Modifier.heightIn(min = 48.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.Timer, null, Modifier.size(18.dp))
                Text(durationFormat(remaining), Modifier.padding(start = 6.dp).testTag("rest_timer_value").semantics {
                    contentDescription = remainingDescription
                }, style = MaterialTheme.typography.titleMedium)
            }
            TextButton(onClick = { onAdjust(-15) }, modifier = Modifier.testTag("rest_minus_15")
                .semantics { contentDescription = subtractDescription }, contentPadding = PaddingValues(horizontal = 8.dp)) {
                Text(stringResource(R.string.rest_minus_15))
            }
            TextButton(onClick = { onAdjust(15) }, modifier = Modifier.testTag("rest_plus_15")
                .semantics { contentDescription = addDescription }, contentPadding = PaddingValues(horizontal = 8.dp)) {
                Text(stringResource(R.string.rest_plus_15))
            }
            TextButton(onClick = onSkip, contentPadding = PaddingValues(horizontal = 8.dp)) { Text(stringResource(R.string.skip_rest)) }
        }
    }
}
