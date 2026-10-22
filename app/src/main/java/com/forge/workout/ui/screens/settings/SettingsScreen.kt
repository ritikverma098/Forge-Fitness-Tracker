package com.forge.workout.ui.screens.settings

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.forge.workout.R
import com.forge.workout.preferences.*
import com.forge.workout.ui.components.ScreenHeader
import com.forge.workout.ui.components.RestAlertSettings
import com.forge.workout.ui.screens.workout.restLabel
import com.forge.workout.viewmodel.BackupUiState

@Composable fun SettingsScreen(
    preferences: UserPreferences,
    onBack: () -> Unit,
    onTheme: (ThemeMode) -> Unit,
    onUnit: (WeightUnit) -> Unit,
    onRest: (Int) -> Unit,
    onConfirmation: (Boolean) -> Unit,
    backupState: BackupUiState,
    onWriteBackup: (Uri) -> Unit,
    onReadBackup: (Uri) -> Unit,
    onConfirmRestore: () -> Unit,
    onCancelRestore: () -> Unit,
) {
    BackHandler(enabled = backupState.busy) { }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()),verticalArrangement = Arrangement.spacedBy(16.dp)) {
        ScreenHeader(stringResource(R.string.settings),stringResource(R.string.settings_subtitle),{ if(!backupState.busy) onBack() })
        SettingsCard(stringResource(R.string.appearance)) {
            ThemeMode.entries.forEach { mode -> RadioSetting(stringResource(when(mode) { ThemeMode.SYSTEM -> R.string.theme_system; ThemeMode.LIGHT -> R.string.theme_light; ThemeMode.DARK -> R.string.theme_dark }),preferences.themeMode == mode, !backupState.busy) { onTheme(mode) } }
        }
        SettingsCard(stringResource(R.string.weight_unit)) {
            WeightUnit.entries.forEach { unit -> RadioSetting(stringResource(if(unit == WeightUnit.KG) R.string.kilograms else R.string.pounds),preferences.weightUnit == unit, !backupState.busy) { onUnit(unit) } }
        }
        SettingsCard(stringResource(R.string.rest_timer)) {
            Text(stringResource(R.string.rest_description),style = MaterialTheme.typography.bodySmall,color = MaterialTheme.colorScheme.onSurfaceVariant)
            PreferencesRepository.REST_PRESETS.forEach { seconds -> RadioSetting(restLabel(seconds),preferences.restSeconds == seconds, !backupState.busy) { onRest(seconds) } }
        }
        SettingsCard(stringResource(R.string.rest_alert_settings_title)) {
            RestAlertSettings(enabled = !backupState.busy)
        }
        SettingsCard(stringResource(R.string.confirm_deletion)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.confirm_deletion_description),Modifier.weight(1f),style = MaterialTheme.typography.bodyMedium)
                Switch(preferences.confirmDeletion,onConfirmation,enabled = !backupState.busy)
            }
        }
        SettingsCard(stringResource(R.string.backup_title)) {
            BackupRestoreContent(backupState, onWriteBackup, onReadBackup, onConfirmRestore, onCancelRestore)
        }
        SettingsCard(stringResource(R.string.about)) { Text(stringResource(R.string.about_description),style = MaterialTheme.typography.bodyMedium) }
        Spacer(Modifier.height(24.dp))
    }
}
@Composable private fun SettingsCard(title: String,content: @Composable ColumnScope.() -> Unit) {
    Surface(Modifier.fillMaxWidth().padding(horizontal = 20.dp),shape = RoundedCornerShape(24.dp),color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(Modifier.padding(20.dp),verticalArrangement = Arrangement.spacedBy(10.dp)) { Text(title,style = MaterialTheme.typography.titleMedium); content() }
    }
}
@Composable private fun RadioSetting(label: String,selected: Boolean,enabled: Boolean,onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth(),verticalAlignment = Alignment.CenterVertically) { RadioButton(selected,onClick,enabled = enabled); TextButton(onClick,enabled = enabled) { Text(label,color = MaterialTheme.colorScheme.onSurface) } }
}
