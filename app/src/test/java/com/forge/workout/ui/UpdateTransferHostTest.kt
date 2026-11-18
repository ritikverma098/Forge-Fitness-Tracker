package com.forge.workout.ui

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.forge.workout.R
import com.forge.workout.ui.components.UpdateTransferContent
import com.forge.workout.ui.components.UpdateTransferDialogFrame
import com.forge.workout.updates.ApkUpdateState
import com.forge.workout.updates.UpdateFailure
import com.forge.workout.updates.UpdatePhase
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "en-rUS-w400dp-h800dp-mdpi")
@LooperMode(LooperMode.Mode.PAUSED)
class UpdateTransferHostTest {
    @get:Rule val compose = createComposeRule()
    private var cancelled = 0
    private var retried = 0
    private var installed = 0
    private var settings = 0
    private var discarded = 0
    private var migrationGuides = 0

    @Test fun aNewFastAttemptReopensFeedbackWhileHidingRemainsStableWithinOneAttempt() {
        val state = mutableStateOf(ApkUpdateState(UpdatePhase.FAILED, "1.4.2", failure = UpdateFailure.NETWORK, attempt = 1))
        compose.setContent { MaterialTheme { UpdateTransferDialogFrame(state.value, true) { Text("Transfer status") } } }
        node(R.string.update_transfer_title).assertIsDisplayed()
        node(R.string.update_later).performClick()
        node(R.string.update_transfer_title).assertDoesNotExist()
        compose.runOnIdle { state.value = state.value.copy(phase = UpdatePhase.DOWNLOADING, bytes = 40, total = 100) }
        node(R.string.update_transfer_title).assertDoesNotExist()
        // A fast attempt can reach FAILED without Compose observing a PREPARING frame.
        compose.runOnIdle { state.value = state.value.copy(phase = UpdatePhase.FAILED, attempt = 2) }
        node(R.string.update_transfer_title).assertIsDisplayed()
        node(R.string.update_later).performClick()
        compose.runOnIdle { state.value = state.value.copy(failure = UpdateFailure.STORAGE) }
        node(R.string.update_transfer_title).assertDoesNotExist()
    }

    @Test fun progressShowsBytesPercentAndCancelWithoutAnInstallerAction() {
        show(ApkUpdateState(UpdatePhase.DOWNLOADING, "1.4.2", 50, 100))
        compose.onNodeWithTag("update_download_progress").assertIsDisplayed()
        compose.onNodeWithText("50%", substring = true).assertIsDisplayed()
        node(R.string.cancel).performClick()
        assertEquals(1, cancelled)
        node(R.string.update_transfer_install).assertDoesNotExist()
    }

    @Test fun activeWorkoutPreventsInstallationButKeepsDiscardAccessible() {
        show(ApkUpdateState(UpdatePhase.READY, "1.4.2", verified = true), canInstall = false)
        node(R.string.update_transfer_install).assertIsNotEnabled()
        node(R.string.update_transfer_install_later).assertIsDisplayed()
        node(R.string.update_transfer_discard).performClick()
        assertEquals(1, discarded)
        assertEquals(0, installed)
    }

    @Test fun readyRequiresExplicitInstallClick() {
        show(ApkUpdateState(UpdatePhase.READY, "1.4.2", verified = true))
        assertEquals(0, installed)
        node(R.string.update_transfer_install).assertIsEnabled().performClick()
        assertEquals(1, installed)
    }

    @Test fun permissionExplainsAndroidApprovalAndDoesNotInstallAutomatically() {
        show(ApkUpdateState(UpdatePhase.PERMISSION_REQUIRED, "1.4.2", verified = true))
        node(R.string.update_transfer_permission_explanation).assertIsDisplayed()
        node(R.string.update_transfer_allow).performClick()
        assertEquals(1, settings)
        assertEquals(0, installed)
        node(R.string.update_transfer_install).assertDoesNotExist()
    }

    @Test fun cancelledInstallationRetriesTheVerifiedFileRatherThanDownloadingAgain() {
        show(ApkUpdateState(UpdatePhase.CANCELLED, "1.4.2", verified = true))
        node(R.string.update_transfer_install_cancelled).assertIsDisplayed()
        node(R.string.update_transfer_retry_install).performClick()
        assertEquals(1, installed)
        assertEquals(0, retried)
        node(R.string.update_transfer_retry_download).assertDoesNotExist()
    }

    @Test fun checksumFailureOffersDownloadRetryAndNeverInstallation() {
        show(ApkUpdateState(UpdatePhase.FAILED, "1.4.2", failure = UpdateFailure.INTEGRITY))
        node(R.string.update_transfer_integrity_error).assertIsDisplayed()
        node(R.string.update_transfer_retry_download).performClick()
        assertEquals(1, retried)
        node(R.string.update_transfer_install).assertDoesNotExist()
    }

    @Test fun handedOffStateDoesNotClaimTheUpdateIsInstalled() {
        show(ApkUpdateState(UpdatePhase.HANDED_TO_INSTALLER, "1.4.2", verified = true))
        node(R.string.update_transfer_handed).assertIsDisplayed()
        node(R.string.update_transfer_installed).assertDoesNotExist()
    }

    @Test fun differentSignerExplainsVerifiedBackupMigrationWithoutOfferingInstallOrPointlessRetry() {
        show(ApkUpdateState(UpdatePhase.FAILED, "1.4.2", failure = UpdateFailure.SIGNATURE_MISMATCH))
        node(R.string.update_transfer_signature_error).assertIsDisplayed()
        node(R.string.update_transfer_migration_guide).performClick()
        assertEquals(1, migrationGuides)
        node(R.string.update_transfer_install).assertDoesNotExist()
        node(R.string.update_transfer_retry_download).assertDoesNotExist()
        assertEquals(0, installed)
    }

    private fun show(state: ApkUpdateState, canInstall: Boolean = true) {
        compose.setContent { MaterialTheme { UpdateTransferContent(state, enabled = true, canInstall,
            onCancel = { cancelled++ }, onRetryDownload = { retried++ }, onInstall = { installed++ },
            onOpenSettings = { settings++ }, onDiscard = { discarded++ },
            onMigrationGuide = { migrationGuides++ }) } }
    }
    private fun node(id: Int) = compose.onNodeWithText(RuntimeEnvironment.getApplication().getString(id))
}
