package com.forge.workout.ui

import android.app.Application
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import com.forge.workout.R
import com.forge.workout.ui.screens.settings.BackupRestoreContent
import com.forge.workout.viewmodel.BackupPreview
import com.forge.workout.viewmodel.BackupUiState
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "en-rUS-w600dp-h1000dp-mdpi")
@LooperMode(LooperMode.Mode.PAUSED)
class BackupRestoreContentHostTest {
    @get:Rule val compose = createComposeRule()

    @Test fun `full backup displays personal nutrition counts and replacement scope before confirmation`() {
        val state = mutableStateOf(BackupUiState(pending = BackupPreview(1_000, 2, 1, 3,
            hasNutrition = true, customFoodCount = 1, recipeCount = 2, diaryCount = 3, bodyWeightCount = 4)))
        var confirmed = 0
        var cancelled = 0
        compose.setContent {
            MaterialTheme { Column { BackupRestoreContent(state.value, {}, {}, { confirmed++ }, { cancelled++ }) } }
        }
        for (count in listOf("1 custom food", "2 recipes", "3 diary entries", "4 body-weight records")) {
            compose.onNodeWithText(count).assertExists()
        }
        compose.onNodeWithText(text(R.string.backup_restore_replaces_nutrition)).assertExists()
        compose.onNodeWithTag("backup_restore_scope").assertTextEquals(text(R.string.backup_reference_catalogs_kept))
        assertEquals(0, confirmed)
        compose.onNodeWithTag("backup_confirm_restore").assertIsEnabled().performClick()
        assertEquals(1, confirmed)
        compose.runOnIdle { state.value = state.value.copy(busy = true) }
        compose.onNodeWithTag("backup_confirm_restore").assertIsNotEnabled()
        compose.onNodeWithText(text(R.string.backup_restore_cancel)).assertIsNotEnabled()
        assertEquals(0, cancelled)
    }

    @Test fun `workout-only backup explicitly keeps nutrition and cancelling never confirms`() {
        var confirmed = 0
        var cancelled = 0
        compose.setContent {
            MaterialTheme { Column { BackupRestoreContent(BackupUiState(pending = BackupPreview(1_000, 1, 0, 0)), {}, {},
                { confirmed++ }, { cancelled++ }) } }
        }
        compose.onNodeWithText(text(R.string.backup_restore_replaces)).assertExists()
        compose.onNodeWithTag("backup_restore_scope").assertTextEquals(text(R.string.backup_legacy_nutrition_kept))
        compose.onNodeWithText("0 custom foods").assertDoesNotExist()
        compose.onNodeWithText(text(R.string.backup_restore_cancel)).assertIsEnabled().performClick()
        assertEquals(1, cancelled)
        assertEquals(0, confirmed)
    }

    private fun text(resource: Int): String = RuntimeEnvironment.getApplication().getString(resource)
}
