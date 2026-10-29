package com.forge.workout.ui

import android.Manifest
import android.app.Application
import android.os.Looper
import android.os.SystemClock
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composer
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.CompositionTracer
import androidx.compose.runtime.InternalComposeTracingApi
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.forge.workout.R
import com.forge.workout.model.*
import com.forge.workout.preferences.UserPreferences
import com.forge.workout.repository.WorkoutRepository
import com.forge.workout.ui.screens.workout.WorkoutScreen
import com.forge.workout.viewmodel.WorkoutViewModel
import com.forge.workout.utils.userMessage
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

@OptIn(InternalComposeTracingApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "en-rUS-w600dp-h900dp-mdpi")
@LooperMode(LooperMode.Mode.PAUSED)
class WorkoutPerformanceHostTest {
    @get:Rule val compose = createComposeRule()
    private val trace = WorkoutBodyTrace()
    private lateinit var repository: LoggingRepository
    private lateinit var lifecycle: Lifecycle
    private val viewportWidth = mutableFloatStateOf(411f)
    private val displayDensity = mutableFloatStateOf(1f)
    private val fontScale = mutableFloatStateOf(1f)
    private val dark = mutableStateOf(false)

    @Before fun startTrace() {
        shadowOf(RuntimeEnvironment.getApplication() as Application).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        Composer.setTracer(trace)
    }

    @After fun stopTrace() { Composer.setTracer(null) }

    @Test fun `elapsed and rest clocks advance without recomposing the logging screen cards or rows`() {
        showWorkout(editing = false)
        val elapsedBefore = displayedSeconds("workout_elapsed_time")
        val restBefore = displayedSeconds("rest_timer_value")
        val wallBefore = System.currentTimeMillis()
        val uptimeBefore = SystemClock.uptimeMillis()
        assertTrue("Fixture must compose an exercise card", trace.count("ExerciseLogCard") > 0)
        assertTrue("Fixture must compose editable set rows", trace.count("SetRow") > 0)
        trace.reset()

        advanceClocks(3_000)

        assertTrue("The elapsed clock must actually update: wall delta=${System.currentTimeMillis() - wallBefore}, " +
            "uptime delta=${SystemClock.uptimeMillis() - uptimeBefore}, lifecycle=${lifecycle.currentState}, " +
            "elapsed=$elapsedBefore→${displayedSeconds("workout_elapsed_time")}, " +
            "elapsed executions=${trace.count("WorkoutElapsedTime")}, rest executions=${trace.count("WorkoutRestSection")}",
            displayedSeconds("workout_elapsed_time") > elapsedBefore)
        assertTrue("The rest clock must actually update", displayedSeconds("rest_timer_value") < restBefore)
        assertTrue(trace.count("WorkoutElapsedTime") > 0)
        assertTrue(trace.count("WorkoutRestSection") > 0)
        assertLoggingBodiesUntouched()
    }

    @Test fun `completed editor has fixed duration and starts no periodic clock work`() {
        showWorkout(editing = true)
        val duration = displayedSeconds("workout_elapsed_time")
        assertEquals(120L, duration)
        trace.reset()

        advanceClocks(3_000)

        assertEquals(duration, displayedSeconds("workout_elapsed_time"))
        assertEquals(0, trace.count("WorkoutElapsedTime"))
        assertEquals(0, trace.count("WorkoutRestSection"))
        assertLoggingBodiesUntouched()
    }

    @Test fun `editing one set persists the value while equal fresh graph entries skip unrelated cards`() {
        showWorkout(editing = true)
        assertTrue("Fixture must compose at least two cards", trace.count("ExerciseLogCard") >= 2)
        val unchanged = repository.detail.exercises.drop(1)
        trace.reset()

        compose.onNodeWithTag("numeric_1000_WEIGHT").performTextReplacement("82.5")
        compose.waitUntil(5_000) { repository.detail.exercises.first().sets.single().weight == 82.5 }
        compose.waitForIdle()

        assertEquals(82.5, repository.detail.exercises.first().sets.single().weight, 0.000001)
        assertEquals(8, repository.detail.exercises.first().sets.single().reps)
        assertFalse(repository.detail.exercises.first().sets.single().completed)
        assertEquals(unchanged, repository.detail.exercises.drop(1))
        assertEquals("Only the edited exercise should execute its body", 1, trace.count("ExerciseLogCard"))
        assertEquals("Only the edited set should execute its body", 1, trace.count("SetRow"))
    }

    @Test fun `first drag after idle composes new sets without per-row measure-time subcomposition`() {
        showWorkout(editing = false, setCount = 6)
        repeat(3) { cycle ->
            advanceClocks(3_000)
            trace.reset()
            compose.onNodeWithTag("workout_exercise_list").performTouchInput {
                if (cycle % 2 == 0) swipeUp(durationMillis = 400) else swipeDown(durationMillis = 400)
            }
            compose.waitForIdle()
            if (cycle == 0) assertTrue("The drag must actually compose unseen sets", trace.count("SetRow") > 0)
            assertEquals("New/re-entering cards must not start width-dependent subcompositions for every set", 0,
                trace.count("BoxWithConstraints"))
        }
    }

    @Test fun `large exercise composes only visible sets and retains a draft when its row leaves the viewport`() {
        showWorkout(editing = true, setCount = 40)
        assertTrue("An exercise must not eagerly compose all forty editable rows", trace.count("SetRow") < 40)
        compose.onNodeWithTag("numeric_1000_WEIGHT").performTextReplacement("-")
        compose.onNodeWithTag("numeric_1000_REPS").performImeAction()
        compose.onNodeWithTag("workout_exercise_list")
            .performScrollToNode(hasTestTag("workout_set_1039"))
        compose.onNodeWithTag("numeric_1039_WEIGHT").assertIsDisplayed()
        compose.onNodeWithTag("workout_exercise_list")
            .performScrollToNode(hasTestTag("workout_set_1000"))
        compose.onNodeWithTag("numeric_1000_WEIGHT").assertTextEquals("-")
        assertEquals(60.0, repository.detail.exercises.first().sets.first().weight, .000001)
    }

    @Test fun `shared width decision preserves breakpoint geometry and draft through resize font and theme changes`() {
        showWorkout(editing = true)
        val input = compose.onNodeWithTag("numeric_1000_WEIGHT")
        input.performTextReplacement("-")

        // At density 1.1, independent padding rounding leaves 259.09dp at 332dp list width.
        // Subtracting an unrounded 72dp would incorrectly keep the previous column inline.
        listOf(
            DisplayCase(332f, 1f, 1f, false, true),
            DisplayCase(331f, 1f, 1f, true, false),
            DisplayCase(332f, 1.1f, 1f, false, false),
            DisplayCase(333f, 1.1f, 1f, true, true),
            DisplayCase(411f, 1f, 1.6f, true, false),
            DisplayCase(411f, 1f, 1f, false, true),
        ).forEach { display ->
            compose.runOnIdle {
                viewportWidth.floatValue = display.width
                displayDensity.floatValue = display.density
                fontScale.floatValue = display.fontScale
                dark.value = display.dark
            }
            compose.waitForIdle()
            val previous = compose.onNode(hasContentDescription(
                (RuntimeEnvironment.getApplication() as Application).getString(R.string.previous, "70 kg × 8"))
                and hasAnyAncestor(hasTestTag("workout_set_1000")))
                .fetchSemanticsNode().boundsInRoot
            val checkbox = compose.onNodeWithTag("set_done_1000").fetchSemanticsNode().boundsInRoot
            if (display.inline) assertTrue("Previous must remain in the row at $display", previous.bottom <= checkbox.bottom)
            else assertTrue("Previous must wrap below the row at $display", previous.top >= checkbox.bottom)
            input.assertTextEquals("-")
            assertEquals("An invalid draft must not overwrite saved data", 60.0,
                repository.detail.exercises.first().sets.first().weight, 0.000001)
        }
        input.performTextReplacement("82.5")
        compose.waitUntil(5_000) { repository.detail.exercises.first().sets.first().weight == 82.5 }
        assertTrue(repository.detail.exercises.drop(1).all { entry -> entry.sets.all { it.weight == 60.0 } })
    }

    private data class DisplayCase(val width: Float, val density: Float, val fontScale: Float,
        val dark: Boolean, val inline: Boolean)

    private fun showWorkout(editing: Boolean, setCount: Int = 1) {
        val application = RuntimeEnvironment.getApplication() as Application
        repository = LoggingRepository(editing, System.currentTimeMillis(), setCount)
        val viewModel = WorkoutViewModel(repository, { userMessage(application, it) })
        val preferences = UserPreferences(restSeconds = 0)
        compose.setContent {
            val owner = LocalLifecycleOwner.current
            SideEffect { lifecycle = owner.lifecycle }
            val workouts by repository.state.collectAsState()
            CompositionLocalProvider(LocalDensity provides Density(displayDensity.floatValue, fontScale.floatValue)) {
            MaterialTheme(colorScheme = if (dark.value) darkColorScheme() else lightColorScheme()) {
            Box(Modifier.width(viewportWidth.floatValue.dp)) {
                WorkoutScreen(
                    detail = workouts.first(), history = workouts, preferences = preferences,
                    viewModel = viewModel, editing = editing, onStart = {}, onRoutines = {},
                    onAddExercise = {}, onFinish = {}, onDiscard = {}, onBack = {}, onRestPreset = {},
                )
            }
            }
            }
        }
        // Wait for the off-main previous-performance index before taking the steady-state baseline.
        val previous = application.getString(R.string.previous, "70 kg × 8")
        compose.waitUntil(5_000) {
            compose.onAllNodesWithContentDescription(previous).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("numeric_1000_WEIGHT").assertIsDisplayed()
        compose.waitForIdle()
    }

    private fun advanceClocks(millis: Long) {
        compose.mainClock.autoAdvance = false
        try {
            repeat((millis / 1_000).toInt()) {
                // App System.currentTimeMillis is real JVM time here; a virtual-only tick reads the same second.
                Thread.sleep(1_100)
                // repeatOnLifecycle runs its delay on Android's Main dispatcher, outside the Compose clock.
                compose.runOnIdle { shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1)) }
                compose.mainClock.advanceTimeBy(1_000)
                compose.waitForIdle()
            }
        } finally { compose.mainClock.autoAdvance = true }
        compose.waitForIdle()
    }

    private fun displayedSeconds(tag: String): Long {
        val text = compose.onNodeWithTag(tag).fetchSemanticsNode().config[SemanticsProperties.Text].single().text
        return text.split(':').fold(0L) { total, part -> total * 60 + part.toLong() }
    }

    private fun assertLoggingBodiesUntouched() {
        assertEquals("Timer ticks must not execute WorkoutScreen", 0, trace.count("WorkoutScreen"))
        assertEquals("Timer ticks must not execute exercise cards", 0, trace.count("ExerciseLogCard"))
        assertEquals("Timer ticks must not execute set rows", 0, trace.count("SetRow"))
    }

    private class WorkoutBodyTrace : CompositionTracer {
        private val counts = ConcurrentHashMap<String, AtomicInteger>()
        private val names = setOf("WorkoutScreen", "ExerciseLogCard", "SetRow", "WorkoutElapsedTime", "WorkoutRestSection")
        override fun isTraceInProgress() = true
        override fun traceEventStart(key: Int, dirty1: Int, dirty2: Int, info: String) {
            if (info.startsWith("androidx.compose.foundation.layout.BoxWithConstraints (")) {
                counts.getOrPut("BoxWithConstraints") { AtomicInteger() }.incrementAndGet()
            }
            if (!info.startsWith("com.forge.workout.ui.screens.workout.")) return
            val name = info.substringBefore(" (").substringAfterLast('.')
            if (name in names) counts.getOrPut(name) { AtomicInteger() }.incrementAndGet()
        }
        override fun traceEventEnd() = Unit
        fun count(name: String) = counts[name]?.get() ?: 0
        fun reset() { counts.values.forEach { it.set(0) } }
    }

    /** Rebuild all graph objects on writes, as Room does, to exercise structural-equality retention. */
    private class LoggingRepository(editing: Boolean, now: Long, private val setCount: Int = 1) : WorkoutRepository {
        private val exercises = List(3) { index ->
            Exercise(id = index + 10L, name = "Exercise ${index + 1}", primaryMuscleGroup = MuscleGroup.CHEST)
        }
        val state = MutableStateFlow(listOf(
            WorkoutDetail(Workout(id = 1, name = "Performance session", startTime = now - 120_000,
                endTime = if (editing) now else null, restEndTime = if (editing) null else now + 90_000),
                exercises.mapIndexed { index, exercise -> entry(exercise, index, 1, 60.0, false) }),
        ) + List(24) { index ->
            WorkoutDetail(Workout(id = index + 2L, name = "Past session", startTime = now - (index + 1L) * 86_400_000,
                endTime = now - (index + 1L) * 86_400_000 + 120_000),
                exercises.mapIndexed { position, exercise -> entry(exercise, position, index + 2L, 70.0, true) })
        })
        val detail: WorkoutDetail get() = state.value.first()

        private fun entry(exercise: Exercise, index: Int, workoutId: Long, weight: Double, completed: Boolean): WorkoutExerciseDetail {
            val entryId = workoutId * 100 + index
            return WorkoutExerciseDetail(WorkoutExercise(entryId, workoutId, exercise.id, index), exercise,
                List(setCount) { position ->
                    WorkoutSet(id = workoutId * 1_000 + index * setCount + position,
                        workoutExerciseId = entryId, setNumber = position + 1,
                        weight = weight, reps = 8, completed = completed)
                })
        }

        override fun observeWorkouts(): Flow<List<WorkoutDetail>> = state
        override suspend fun workout(id: Long): Workout? = state.value.firstOrNull { it.workout.id == id }?.workout
        override suspend fun hasWorkoutExercise(workoutId: Long, exerciseId: Long): Boolean = state.value
            .firstOrNull { it.workout.id == workoutId }?.exercises?.any { it.exercise.id == exerciseId } == true
        override suspend fun workoutSet(id: Long): WorkoutSet? = state.value.asSequence()
            .flatMap { it.exercises.asSequence() }.flatMap { it.sets.asSequence() }.firstOrNull { it.id == id }
        override suspend fun workoutExerciseSets(workoutExerciseId: Long): List<WorkoutSet> = state.value.asSequence()
            .flatMap { it.exercises.asSequence() }.firstOrNull { it.entry.id == workoutExerciseId }?.sets.orEmpty()
        override fun observeExercises(): Flow<List<Exercise>> = flowOf(exercises)
        override fun observeExerciseUsage(): Flow<List<ExerciseUsage>> = flowOf(emptyList())
        override fun observeRoutines(): Flow<List<RoutineDetail>> = flowOf(emptyList())
        override suspend fun ensureSeeded() = Unit
        override suspend fun updateSet(set: WorkoutSet) {
            state.value = state.value.map { workout -> workout.copy(workout = workout.workout.copy(),
                exercises = workout.exercises.map { entry -> entry.copy(entry = entry.entry.copy(), exercise = entry.exercise.copy(),
                    sets = entry.sets.map { old -> if (old.id == set.id) set else old.copy() }) }) }
        }
        override suspend fun updateWorkout(id: Long, name: String, notes: String) = error("Unused operation")
        override suspend fun updateMuscleGroups(id: Long, groups: Set<MuscleGroup>) = error("Unused operation")
        override suspend fun saveExercise(exercise: Exercise): Long = error("Unused operation")
        override suspend fun deleteExercise(id: Long) = error("Unused operation")
        override suspend fun startWorkout(name: String, routineId: Long?, repeatWorkoutId: Long?): Long = error("Unused operation")
        override suspend fun finishWorkout(id: Long) = error("Unused operation")
        override suspend fun deleteWorkout(id: Long) = error("Unused operation")
        override suspend fun addExercise(workoutId: Long, exerciseId: Long) = error("Unused operation")
        override suspend fun removeWorkoutExercise(id: Long) = error("Unused operation")
        override suspend fun moveWorkoutExercise(id: Long, delta: Int) = error("Unused operation")
        override suspend fun addSet(workoutExerciseId: Long) = error("Unused operation")
        override suspend fun deleteSet(id: Long) = error("Unused operation")
        override suspend fun updateExerciseNotes(workoutExerciseId: Long, notes: String) = error("Unused operation")
        override suspend fun setRestEndTime(workoutId: Long, endTime: Long?) = error("Unused operation")
        override suspend fun consumeRestTimer(workoutId: Long, expectedDeadline: Long, now: Long): Boolean = error("Unused operation")
        override suspend fun saveRoutine(id: Long?, name: String): Long = error("Unused operation")
        override suspend fun addRoutineExercise(routineId: Long, exerciseId: Long) = error("Unused operation")
        override suspend fun removeRoutineExercise(id: Long) = error("Unused operation")
        override suspend fun moveRoutineExercise(id: Long, delta: Int) = error("Unused operation")
        override suspend fun deleteRoutine(id: Long) = error("Unused operation")
    }
}
