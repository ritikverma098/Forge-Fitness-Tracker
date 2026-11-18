package com.forge.workout.ui.components

import android.content.ActivityNotFoundException
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.forge.workout.updates.ApkUpdateCoordinator

/** Only installer approval is collected here; download ticks stay inside the update panel. */
@Composable
internal fun UpdateInstallerHost(manager: ApkUpdateCoordinator, canLaunch: Boolean) {
    val confirmation by manager.confirmation.collectAsStateWithLifecycle()
    var resumed by remember(manager) { mutableStateOf(false) }
    val installer = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        // The result code alone cannot prove that a package replacement succeeded.
        manager.refresh()
    }
    LifecycleResumeEffect(manager) {
        resumed = true
        manager.refresh()
        onPauseOrDispose { resumed = false }
    }
    LaunchedEffect(manager, confirmation, resumed, canLaunch) {
        if (resumed && canLaunch && confirmation != null) {
            val intent = manager.takeConfirmation() ?: return@LaunchedEffect
            try { installer.launch(intent) }
            catch (_: ActivityNotFoundException) { manager.installationLaunchFailed() }
            catch (_: SecurityException) { manager.installationLaunchFailed() }
        }
    }
}
