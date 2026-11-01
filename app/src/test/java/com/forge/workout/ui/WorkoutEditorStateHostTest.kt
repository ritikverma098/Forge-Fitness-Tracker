package com.forge.workout.ui

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.LocalSaveableStateRegistry
import androidx.compose.runtime.saveable.SaveableStateRegistry
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTextReplacement
import com.forge.workout.ui.screens.workout.rememberWorkoutEditorText
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
@LooperMode(LooperMode.Mode.PAUSED)
class WorkoutEditorStateHostTest {
    @get:Rule val compose = createComposeRule()
    private var visible by mutableStateOf(true)
    private var entityId by mutableStateOf(1L)
    private var owner by mutableStateOf("writer-one")
    private var registry by mutableStateOf(SaveableStateRegistry(null) { true })
    private var resetNotices = 0

    @Test fun differentEntityDoesNotInheritSameOwnerText() {
        showEditor()
        editAndRestore(id = 2L, restoredOwner = "writer-one")

        compose.onNodeWithTag("editor").assertTextEquals("Workout 2")
        compose.runOnIdle { assertEquals(1, resetNotices) }
    }

    @Test fun sameEntityAndOwnerRetainPendingText() {
        showEditor()
        editAndRestore(id = 1L, restoredOwner = "writer-one")

        compose.onNodeWithTag("editor").assertTextEquals("Pending first text")
        compose.runOnIdle { assertEquals(0, resetNotices) }
    }

    @Test fun newOwnerUsesAuthoritativeText() {
        showEditor()
        editAndRestore(id = 1L, restoredOwner = "writer-two")

        compose.onNodeWithTag("editor").assertTextEquals("Workout 1")
        compose.runOnIdle { assertEquals(1, resetNotices) }
    }

    private fun showEditor() {
        compose.setContent {
            MaterialTheme {
                CompositionLocalProvider(LocalSaveableStateRegistry provides registry) {
                    if (visible) {
                        val text = rememberWorkoutEditorText(entityId, "Workout $entityId", owner) { resetNotices++ }
                        OutlinedTextField(text.value, { text.value = it }, modifier = Modifier.testTag("editor"))
                    }
                }
            }
        }
    }

    private fun editAndRestore(id: Long, restoredOwner: String) {
        compose.onNodeWithTag("editor").performTextReplacement("Pending first text")
        val saved = compose.runOnIdle { registry.performSave() }
        compose.runOnIdle { visible = false }
        compose.waitForIdle()
        compose.runOnIdle {
            entityId = id
            owner = restoredOwner
            registry = SaveableStateRegistry(saved) { true }
            visible = true
        }
        compose.waitForIdle()
    }
}
