package com.forge.workout.ui.screens.settings

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.FileDownload
import androidx.compose.material.icons.outlined.FileUpload
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import com.forge.workout.R
import com.forge.workout.ui.components.dateFormat
import com.forge.workout.viewmodel.BackupUiState
import java.time.LocalDate

@Composable
internal fun BackupRestoreContent(
    backupState: BackupUiState,
    onWriteBackup: (Uri) -> Unit,
    onReadBackup: (Uri) -> Unit,
    onConfirmRestore: () -> Unit,
    onCancelRestore: () -> Unit,
) {
    val writeLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        uri?.let(onWriteBackup)
    }
    val readLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(onReadBackup)
    }
    val filename = stringResource(R.string.backup_file_name, LocalDate.now().toString())
    Text(
        stringResource(R.string.backup_description),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Button(
        onClick = { writeLauncher.launch(filename) },
        enabled = !backupState.busy,
        modifier = Modifier.fillMaxWidth().testTag("backup_create"),
        contentPadding = PaddingValues(16.dp),
    ) {
        Icon(Icons.Outlined.FileDownload, null, Modifier.padding(end = 8.dp).size(20.dp))
        Text(stringResource(R.string.backup_create))
    }
    OutlinedButton(
        onClick = { readLauncher.launch(arrayOf("*/*")) },
        enabled = !backupState.busy,
        modifier = Modifier.fillMaxWidth().testTag("backup_restore"),
        contentPadding = PaddingValues(16.dp),
    ) {
        Icon(Icons.Outlined.FileUpload, null, Modifier.padding(end = 8.dp).size(20.dp))
        Text(stringResource(R.string.backup_restore))
    }
    AnimatedVisibility(backupState.busy) {
        BackupProgress(stringResource(R.string.backup_operation_busy))
    }
    if (backupState.pending == null) {
        backupState.errorMessage?.let { message ->
            Text(stringResource(message), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }
    }
    Text(
        stringResource(R.string.backup_file_hint),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    backupState.pending?.let { preview ->
        AlertDialog(
            onDismissRequest = { if (!backupState.busy) onCancelRestore() },
            properties = DialogProperties(
                dismissOnBackPress = !backupState.busy,
                dismissOnClickOutside = !backupState.busy,
            ),
            title = { Text(stringResource(R.string.backup_restore_confirm_title)) },
            text = {
                Column(
                    modifier = Modifier.heightIn(max = 440.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(
                        stringResource(R.string.backup_created_at, dateFormat(preview.createdAt)),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(pluralStringResource(R.plurals.backup_workout_count, preview.workoutCount, preview.workoutCount))
                        Text(pluralStringResource(R.plurals.backup_routine_count, preview.routineCount, preview.routineCount))
                        Text(pluralStringResource(R.plurals.backup_custom_exercise_count, preview.customExerciseCount, preview.customExerciseCount))
                        if (preview.hasNutrition) {
                            Text(pluralStringResource(R.plurals.backup_custom_food_count, preview.customFoodCount, preview.customFoodCount))
                            Text(pluralStringResource(R.plurals.backup_recipe_count, preview.recipeCount, preview.recipeCount))
                            Text(pluralStringResource(R.plurals.backup_diary_count, preview.diaryCount, preview.diaryCount))
                            Text(pluralStringResource(R.plurals.backup_body_weight_count, preview.bodyWeightCount, preview.bodyWeightCount))
                        }
                    }
                    Text(
                        stringResource(if (preview.hasNutrition) R.string.backup_restore_replaces_nutrition else R.string.backup_restore_replaces),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                    )
                    Text(stringResource(if (preview.hasNutrition) R.string.backup_reference_catalogs_kept else R.string.backup_legacy_nutrition_kept),
                        Modifier.testTag("backup_restore_scope"), style = MaterialTheme.typography.bodySmall)
                    backupState.errorMessage?.let { message ->
                        Text(stringResource(message), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    }
                    if (backupState.busy) BackupProgress(stringResource(R.string.backup_restore_busy))
                }
            },
            confirmButton = {
                TextButton(onClick = onConfirmRestore, enabled = !backupState.busy, modifier = Modifier.testTag("backup_confirm_restore")) {
                    Text(stringResource(R.string.backup_restore_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = onCancelRestore, enabled = !backupState.busy) {
                    Text(stringResource(R.string.backup_restore_cancel))
                }
            },
        )
    }
}

@Composable
private fun BackupProgress(message: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
        Text(message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
