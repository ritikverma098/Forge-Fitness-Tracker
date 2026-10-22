package com.forge.workout.viewmodel

import android.app.Application
import androidx.lifecycle.ViewModelStore
import androidx.room.Room
import com.forge.workout.R
import com.forge.workout.utils.userMessage
import com.forge.workout.data.database.ForgeDatabase
import com.forge.workout.model.Exercise
import com.forge.workout.model.ExerciseType
import com.forge.workout.model.MuscleGroup
import com.forge.workout.repository.RoomWorkoutRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** Exercises the ranked state with generated Room queries and real invalidations. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class LibraryViewModelHostTest {
    private val dispatcher = StandardTestDispatcher()
    private lateinit var database: ForgeDatabase
    private lateinit var repository: RoomWorkoutRepository
    private lateinit var model: LibraryViewModel
    private lateinit var store: ViewModelStore

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        val application = RuntimeEnvironment.getApplication()
        database = Room.inMemoryDatabaseBuilder(application, ForgeDatabase::class.java).build()
        repository = RoomWorkoutRepository(database)
        model = LibraryViewModel(repository, { userMessage(application, it) })
        store = ViewModelStore().apply { put("library", model) }
    }

    @After
    fun tearDown() {
        store.clear()
        database.close()
        Dispatchers.resetMain()
    }

    @Test
    fun `finishing promotes an exercise while active work does not and deleting history removes its rank`() = runTest(dispatcher) {
        val unused = createExercise("Alpha unused")
        val used = createExercise("Zulu logged")
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { model.rankedExercises.collect() }
        awaitRanking(unused, used)

        val workoutId = repository.startWorkout("Current session")
        repository.addExercise(workoutId, used)
        val set = database.workoutDao().workoutDetail(workoutId)!!.exercises.single().sets.single()
        repository.updateSet(set.copy(weight = 60.0, reps = 10, completed = true))
        assertTrue(repository.observeExerciseUsage().first().isEmpty())
        awaitRanking(unused, used)

        repository.finishWorkout(workoutId)
        awaitRanking(used, unused)
        val usage = repository.observeExerciseUsage().first().single()
        assertEquals(used, usage.exerciseId)
        assertEquals(1, usage.workoutCount)

        repository.deleteWorkout(workoutId)
        awaitRanking(unused, used)
        assertTrue(repository.observeExerciseUsage().first().isEmpty())
        assertNull(model.error.value)
    }

    @Test
    fun `custom create rename and delete refresh ranked state while archived history remains intact`() = runTest(dispatcher) {
        val unused = createExercise("Alpha unused")
        val used = createExercise("Zulu logged")
        val workoutId = repository.startWorkout("Saved session")
        repository.addExercise(workoutId, used)
        val set = database.workoutDao().workoutDetail(workoutId)!!.exercises.single().sets.single()
        repository.updateSet(set.copy(weight = 75.0, reps = 8, completed = true))
        repository.finishWorkout(workoutId)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { model.rankedExercises.collect() }
        awaitRanking(used, unused)

        val original = database.workoutDao().exercise(used)!!
        var renamed = false
        model.saveExercise(original.copy(name = "Bravo logged", notes = "Slow tempo")) { renamed = true }
        awaitOperation()
        assertTrue(renamed)
        val renamedExercises = model.rankedExercises.first { it.firstOrNull()?.name == "Bravo logged" }
        assertEquals(listOf(used, unused), renamedExercises.map { it.id })
        assertEquals("Slow tempo", renamedExercises.first().notes)

        var created = false
        model.saveExercise(Exercise(name = "Aardvark new", primaryMuscleGroup = MuscleGroup.BACK)) { created = true }
        awaitOperation()
        assertTrue(created)
        val added = database.workoutDao().exercises().single { it.name == "Aardvark new" }
        awaitRanking(used, added.id, unused)

        var archived = false
        model.deleteExercise(used) { archived = true }
        awaitOperation()
        assertTrue(archived)
        awaitRanking(added.id, unused)
        assertTrue(database.workoutDao().exercise(used)!!.archived)
        val historyEntry = database.workoutDao().workoutDetail(workoutId)!!.exercises.single()
        assertEquals(set.copy(weight = 75.0, reps = 8, completed = true), historyEntry.sets.single())
        assertEquals("Bravo logged", historyEntry.exercise.name)

        model.deleteExercise(added.id)
        awaitOperation()
        awaitRanking(unused)
        assertNull(database.workoutDao().exercise(added.id))
        assertNull(model.error.value)
    }

    @Test
    fun `ranking starts with a subscriber and resumes with current Room state after subscription stops`() = runTest(dispatcher) {
        val first = createExercise("Alpha unused")
        val second = createExercise("Zulu unused")
        assertTrue(model.rankedExercises.value.isEmpty())
        val collector = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { model.rankedExercises.collect() }
        awaitRanking(first, second)
        collector.cancelAndJoin()
        advanceTimeBy(5_001)
        runCurrent()

        repository.saveExercise(database.workoutDao().exercise(second)!!.copy(name = "Aardvark renamed"))
        val workoutId = repository.startWorkout("While library is away")
        repository.addExercise(workoutId, first)
        val set = database.workoutDao().workoutDetail(workoutId)!!.exercises.single().sets.single()
        repository.updateSet(set.copy(reps = 12, completed = true))
        repository.finishWorkout(workoutId)

        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { model.rankedExercises.collect() }
        val resumed = model.rankedExercises.first {
            it.map { exercise -> exercise.id } == listOf(first, second) && it.last().name == "Aardvark renamed"
        }
        assertEquals(listOf("Alpha unused", "Aardvark renamed"), resumed.map { it.name })
        assertEquals(first, repository.observeExerciseUsage().first().single().exerciseId)
        assertNull(model.error.value)
    }

    @Test
    fun `exercise save callback returns exact persisted ID fields and live library entry`() = runTest(dispatcher) {
        val anchor = createExercise("Anchor")
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { model.rankedExercises.collect() }
        awaitRanking(anchor)
        val requested = Exercise(name = "  Custom interval  ", primaryMuscleGroup = MuscleGroup.CORE,
            secondaryMuscleGroup = MuscleGroup.FULL_BODY, exerciseType = ExerciseType.DURATION,
            notes = "  Keep controlled breathing  ", isCustom = false, archived = true)
        val returned = mutableListOf<Long>()
        model.saveExercise(requested) { returned += it }
        awaitOperation()
        assertEquals(1, returned.size)
        val id = returned.single()
        assertTrue(id > anchor)
        val expected = requested.copy(id = id, name = "Custom interval", notes = "Keep controlled breathing",
            isCustom = true, archived = false)
        assertEquals(expected, database.workoutDao().exercise(id))
        assertEquals(expected, awaitRanking(anchor, id).last())
    }

    @Test
    fun `duplicate and invalid exercise creation never calls saved callback and recovers with useful errors`() = runTest(dispatcher) {
        val existing = createExercise("Existing press")
        val returned = mutableListOf<Long>()
        val application = RuntimeEnvironment.getApplication()
        val invalid = listOf(
            Exercise(name = " existing PRESS ", primaryMuscleGroup = MuscleGroup.CHEST) to R.string.validation_exercise_duplicate,
            Exercise(name = "  ", primaryMuscleGroup = MuscleGroup.CHEST) to R.string.validation_exercise_name_empty,
            Exercise(name = "Same groups", primaryMuscleGroup = MuscleGroup.CORE, secondaryMuscleGroup = MuscleGroup.CORE) to R.string.validation_secondary_muscle,
        )
        for ((exercise, message) in invalid) {
            model.clearError()
            model.saveExercise(exercise) { returned += it }
            model.busy.first { !it }
            assertTrue(returned.isEmpty())
            assertEquals(application.getString(message), model.error.value)
            assertEquals(listOf(existing), database.workoutDao().exercises().map { it.id })
            assertFalse(model.busy.value)
        }
        model.clearError()
        model.saveExercise(Exercise(name = "Recovered exercise", primaryMuscleGroup = MuscleGroup.BACK)) { returned += it }
        awaitOperation()
        assertEquals(1, returned.size)
        assertEquals("Recovered exercise", database.workoutDao().exercise(returned.single())!!.name)
        assertEquals(2, database.workoutDao().exercises().size)
    }

    @Test
    fun `rapid double save creates one exercise and calls one callback while busy`() = runTest(dispatcher) {
        val returned = mutableListOf<Long>()
        val exercise = Exercise(name = "Single interval", primaryMuscleGroup = MuscleGroup.CARDIO, exerciseType = ExerciseType.DURATION)
        model.saveExercise(exercise) { returned += it }
        assertTrue(model.busy.value)
        model.saveExercise(exercise) { returned += it }
        assertTrue(returned.isEmpty())
        awaitOperation()
        assertEquals(1, returned.size)
        assertEquals(returned.single(), database.workoutDao().exercises().single().id)
        assertFalse(model.busy.value)
    }

    @Test
    fun `created ID appends to routine without replacing existing entries and starts with correct tracking type`() = runTest(dispatcher) {
        val first = createExercise("Press")
        val second = repository.saveExercise(Exercise(name = "Pull up", primaryMuscleGroup = MuscleGroup.BACK,
            exerciseType = ExerciseType.BODYWEIGHT))
        val routineId = repository.saveRoutine(name = "Mixed routine")
        repository.addRoutineExercise(routineId, first)
        repository.addRoutineExercise(routineId, second)
        val original = database.workoutDao().routineDetail(routineId)!!
        val returned = mutableListOf<Long>()
        model.saveExercise(Exercise(name = "Timed plank", primaryMuscleGroup = MuscleGroup.CORE,
            secondaryMuscleGroup = MuscleGroup.SHOULDERS, exerciseType = ExerciseType.DURATION, notes = "Neutral spine")) { returned += it }
        awaitOperation()
        val id = returned.single()
        val routineModel = RoutineViewModel(repository, { userMessage(RuntimeEnvironment.getApplication(), it) })
        store.put("routine", routineModel)
        var added = false
        routineModel.addExercise(routineId, id) { added = true }
        routineModel.busy.first { !it }
        assertTrue(added)
        assertNull(routineModel.error.value)
        val updated = database.workoutDao().routineDetail(routineId)!!
        assertEquals(original.routine, updated.routine)
        assertEquals(original.sortedExercises, updated.sortedExercises.take(2))
        assertEquals(listOf(first, second, id), updated.sortedExercises.map { it.exercise.id })
        assertEquals(listOf(0, 1, 2), updated.sortedExercises.map { it.entry.orderIndex })
        val workoutId = repository.startWorkout("Fallback", routineId = routineId)
        val started = database.workoutDao().workoutDetail(workoutId)!!
        assertEquals("Mixed routine", started.workout.name)
        assertEquals(listOf(first, second, id), started.sortedExercises.map { it.exercise.id })
        assertEquals(listOf(ExerciseType.WEIGHT_REPS, ExerciseType.BODYWEIGHT, ExerciseType.DURATION), started.sortedExercises.map { it.exercise.exerciseType })
        val custom = started.sortedExercises.last()
        assertEquals("Neutral spine", custom.exercise.notes)
        assertEquals(MuscleGroup.SHOULDERS, custom.exercise.secondaryMuscleGroup)
        assertEquals(0, custom.sets.single().duration)
        assertFalse(custom.sets.single().completed)
        repository.updateSet(custom.sets.single().copy(duration = 60, completed = true))
        assertEquals(60, database.workoutDao().workoutDetail(workoutId)!!.sortedExercises.last().sets.single().duration)
    }

    @Test
    fun `requested save outcome is retained without UI collector and acknowledges only matching token`() = runTest(dispatcher) {
        val collector = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { model.exerciseSaveResult.collect() }
        val requested = Exercise(name = "  Retained plank  ", primaryMuscleGroup = MuscleGroup.CORE,
            secondaryMuscleGroup = MuscleGroup.SHOULDERS, exerciseType = ExerciseType.DURATION, notes = "  Neutral spine  ")
        val callbacks = mutableListOf<Long>()
        model.saveExercise(requested, requestId = "routine-create-1") { callbacks += it }
        collector.cancelAndJoin()
        awaitOperation()
        val outcome = model.exerciseSaveResult.first { it?.requestId == "routine-create-1" }!!
        assertEquals(1, callbacks.size)
        assertEquals(callbacks.single(), outcome.savedExercise!!.id)
        assertEquals(database.workoutDao().exercise(callbacks.single()), outcome.savedExercise)
        assertNull(outcome.error)
        assertEquals(outcome, model.exerciseSaveResult.first())
        model.clearError()
        model.acknowledgeExerciseSave("unrelated-screen")
        assertSame(outcome, model.exerciseSaveResult.value)
        model.acknowledgeExerciseSave("routine-create-1")
        assertNull(model.exerciseSaveResult.value)
        model.acknowledgeExerciseSave("routine-create-1")
        assertNull(model.exerciseSaveResult.value)
    }

    @Test
    fun `failed requested save retains localized error without callback until matching acknowledgement and recovers`() = runTest(dispatcher) {
        val callbacks = mutableListOf<Long>()
        model.saveExercise(Exercise(name = " ", primaryMuscleGroup = MuscleGroup.CHEST), requestId = "invalid-request") { callbacks += it }
        model.busy.first { !it }
        val failed = model.exerciseSaveResult.first { it?.requestId == "invalid-request" }!!
        assertTrue(callbacks.isEmpty())
        assertNull(failed.savedExercise)
        assertEquals(RuntimeEnvironment.getApplication().getString(R.string.validation_exercise_name_empty), failed.error)
        assertEquals(failed.error, model.error.value)
        assertTrue(database.workoutDao().exercises().isEmpty())
        model.clearError()
        model.acknowledgeExerciseSave("other-request")
        assertSame(failed, model.exerciseSaveResult.value)
        model.acknowledgeExerciseSave("invalid-request")
        assertNull(model.exerciseSaveResult.value)
        model.saveExercise(Exercise(name = "Valid recovery", primaryMuscleGroup = MuscleGroup.BACK), requestId = "recovery-request") { callbacks += it }
        awaitOperation()
        val recovered = model.exerciseSaveResult.first { it?.requestId == "recovery-request" }!!
        assertEquals(callbacks.single(), recovered.savedExercise!!.id)
        assertNull(recovered.error)
        assertEquals("Valid recovery", recovered.savedExercise!!.name)
    }

    @Test
    fun `busy rejected request and tokenless save cannot overwrite retained requested result`() = runTest(dispatcher) {
        val callbacks = mutableListOf<Long>()
        model.saveExercise(Exercise(name = "First requested", primaryMuscleGroup = MuscleGroup.CORE), requestId = "first-request") { callbacks += it }
        model.saveExercise(Exercise(name = "Rejected busy", primaryMuscleGroup = MuscleGroup.CORE), requestId = "rejected-request") { callbacks += it }
        awaitOperation()
        val outcome = model.exerciseSaveResult.first { it?.requestId == "first-request" }!!
        assertEquals(1, callbacks.size)
        assertEquals(listOf("First requested"), database.workoutDao().exercises().map { it.name })
        model.acknowledgeExerciseSave("rejected-request")
        assertSame(outcome, model.exerciseSaveResult.value)
        model.saveExercise(Exercise(name = "Tokenless exercise", primaryMuscleGroup = MuscleGroup.CHEST)) { callbacks += it }
        awaitOperation()
        assertEquals(2, callbacks.size)
        assertSame(outcome, model.exerciseSaveResult.value)
        assertEquals(2, database.workoutDao().exercises().size)
        model.acknowledgeExerciseSave("first-request")
        assertNull(model.exerciseSaveResult.value)
    }

    private suspend fun createExercise(name: String): Long = repository.saveExercise(
        Exercise(name = name, primaryMuscleGroup = MuscleGroup.CHEST),
    )

    private suspend fun awaitRanking(vararg ids: Long): List<Exercise> = model.rankedExercises.first {
        it.map { exercise -> exercise.id } == ids.toList()
    }

    private suspend fun awaitOperation() {
        model.busy.first { !it }
        assertNull(model.error.value)
    }
}
