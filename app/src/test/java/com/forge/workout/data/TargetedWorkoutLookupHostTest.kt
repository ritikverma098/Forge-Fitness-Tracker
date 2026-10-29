package com.forge.workout.data

import android.app.Application
import androidx.lifecycle.ViewModelStore
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.withTransaction
import com.forge.workout.data.database.ForgeDatabase
import com.forge.workout.model.Exercise
import com.forge.workout.model.ExerciseType
import com.forge.workout.model.MuscleGroup
import com.forge.workout.model.SetType
import com.forge.workout.model.Workout
import com.forge.workout.model.WorkoutExercise
import com.forge.workout.model.WorkoutSet
import com.forge.workout.repository.RoomWorkoutRepository
import com.forge.workout.repository.WorkoutRepository
import com.forge.workout.viewmodel.SetField
import com.forge.workout.viewmodel.WorkoutViewModel
import com.forge.workout.utils.userMessage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executor

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class TargetedWorkoutLookupHostTest {
    private val dispatcher = StandardTestDispatcher()
    private val queries = CopyOnWriteArrayList<CapturedQuery>()
    @Volatile private var captureQueries = false
    private lateinit var database: ForgeDatabase
    private lateinit var repository: RoomWorkoutRepository
    private lateinit var store: ViewModelStore

    @Before fun setUp() {
        Dispatchers.setMain(dispatcher)
        val application = RuntimeEnvironment.getApplication()
        database = Room.inMemoryDatabaseBuilder(application, ForgeDatabase::class.java)
            .setQueryCallback(object : RoomDatabase.QueryCallback {
                override fun onQuery(sqlQuery: String, bindArgs: List<Any?>) {
                    if (captureQueries) queries += CapturedQuery(sqlQuery, bindArgs.toList())
                }
            }, Executor { it.run() }).build()
        repository = RoomWorkoutRepository(database)
        store = ViewModelStore()
    }

    @After fun tearDown() {
        captureQueries = false
        store.clear()
        database.close()
        Dispatchers.resetMain()
    }

    @Test fun `targeted reads return bounded rows use existing indices and preserve missing deleted and set order semantics`() = runTest(dispatcher) {
        seedLargeHistory()
        captureQueries = true
        val baseline = repository.observeWorkouts().first()
        assertEquals(1_000, baseline.size)
        assertEquals(1_001, baseline.sumOf { it.exercises.size })
        assertEquals(3_001, baseline.sumOf { workout -> workout.exercises.sumOf { it.sets.size } })
        assertTrue(queries.any { isFullHistoryQuery(it.sql) })
        queries.clear()

        val workout = repository.workout(CURRENT)
        val set = repository.workoutSet(FIRST_SET)
        val sets = repository.workoutExerciseSets(ENTRY)
        val reads = queries.filter { isApplicationSelect(it.sql) }
        assertEquals(3, reads.size)
        assertEquals(listOf(CURRENT), reads[0].arguments)
        assertEquals(listOf(FIRST_SET), reads[1].arguments)
        assertEquals(listOf(ENTRY), reads[2].arguments)
        assertEquals("History $CURRENT", workout!!.name)
        assertEquals(FIRST_SET, set!!.id)
        assertEquals(listOf(1, 2, 3), sets.map { it.setNumber })
        assertEquals(listOf(FIRST_SET, FIRST_SET + 1, FIRST_SET + 2), sets.map { it.id })
        assertNoFullHistoryRead(reads)
        assertTrue(queryPlan("SELECT * FROM workouts WHERE id = $CURRENT").any { it.contains("INTEGER PRIMARY KEY", true) })
        assertTrue(queryPlan("SELECT * FROM workout_sets WHERE id = $FIRST_SET").any { it.contains("INTEGER PRIMARY KEY", true) })
        assertTrue(queryPlan("SELECT * FROM workout_sets WHERE workoutExerciseId = $ENTRY ORDER BY setNumber, id")
            .any { it.contains("index_workout_sets_workoutExerciseId", true) })
        assertNull(repository.workout(-1))
        assertNull(repository.workoutSet(-1))
        assertTrue(repository.workoutExerciseSets(-1).isEmpty())
        repository.deleteSet(FIRST_SET)
        assertNull(repository.workoutSet(FIRST_SET))
        assertEquals(listOf(1, 2), repository.workoutExerciseSets(ENTRY).map { it.setNumber })
        repository.removeWorkoutExercise(ENTRY)
        assertTrue(repository.workoutExerciseSets(ENTRY).isEmpty())
        assertNull(repository.workoutSet(FIRST_SET + 1))
        println("Baseline materialization: ${baseline.size} workouts / 3001 sets; targeted reads: ${reads.size} SELECTs / 1 workout + 1 set + ${sets.size} entry sets. Existing primary-key and foreign-key indices verified.")
    }

    @Test fun `queued single row edits observe current values without full history SELECT and leave old workouts untouched`() = runTest(dispatcher) {
        seedLargeHistory()
        val originalHistory = repository.observeWorkouts().first().filter { it.workout.id != CURRENT }
        val original = repository.workoutSet(FIRST_SET)!!
        val model = model()
        captureQueries = true
        queries.clear()
        model.setValue(FIRST_SET, SetField.WEIGHT, 80.0)
        model.setValue(FIRST_SET, SetField.REPS, 12.0)
        model.setType(FIRST_SET, SetType.FAILURE)
        model.completeSet(FIRST_SET, true, CURRENT, 90, editing = false)
        model.addSet(ENTRY)
        model.toggleMuscleGroup(CURRENT, MuscleGroup.BICEPS)
        model.toggleMuscleGroup(CURRENT, MuscleGroup.TRICEPS)
        model.toggleMuscleGroup(CURRENT, MuscleGroup.BICEPS)
        model.adjustRest(CURRENT, 15)
        model.adjustRest(CURRENT, 15)
        model.adjustRest(CURRENT, -15)
        assertTrue(model.flush())
        assertNull(model.error.value)
        val mutationQueries = queries.toList()
        assertNoFullHistoryRead(mutationQueries)
        assertTrue(mutationQueries.any { normalize(it.sql) == "select * from workout_sets where id = ?" && it.arguments == listOf(FIRST_SET) })
        assertTrue(mutationQueries.any { normalize(it.sql) == "select * from workouts where id = ?" && it.arguments == listOf(CURRENT) })
        assertEquals(original.copy(weight = 80.0, reps = 12, completed = true, setType = SetType.FAILURE), repository.workoutSet(FIRST_SET))
        val current = repository.workout(CURRENT)!!
        assertEquals(listOf(MuscleGroup.CHEST, MuscleGroup.TRICEPS), current.muscleGroups)
        assertEquals(NOW + 105_000, current.restEndTime)
        val next = repository.workoutExerciseSets(ENTRY).last()
        assertEquals(4, next.setNumber)
        // Add Set duplicates the latest set in the exercise, independently of the edited first set.
        assertEquals(53.0, next.weight, 0.000001)
        assertEquals(11, next.reps)
        assertFalse(next.completed)
        assertEquals(SetType.WORKING, next.setType)
        assertEquals(originalHistory, repository.observeWorkouts().first().filter { it.workout.id != CURRENT })
        println("Queued mutations against 1000 workouts: ${mutationQueries.count { isApplicationSelect(it.sql) }} bounded SELECTs; zero full-history SELECTs; 999 unrelated workout graphs unchanged.")
    }

    @Test fun `deletion and removal use narrow reads clear only affected validation and cannot resurrect missing sets`() = runTest(dispatcher) {
        seedLargeHistory()
        val originalHistory = repository.observeWorkouts().first().filter { it.workout.id != CURRENT }
        val model = model()
        model.inputValidity(CURRENT, "set_${FIRST_SET}_weight", false)
        model.inputValidity(CURRENT, "set_${FIRST_SET + 1}_weight", false)
        model.inputValidity(CURRENT, "set_${FIRST_SET + 2}_reps", false)
        model.inputValidity(CURRENT, "notes_$ENTRY", false)
        model.inputValidity(CURRENT, "exercise_${ENTRY}_reps", false)
        model.inputValidity(CURRENT, "set_${SECOND_SET}_reps", false)
        model.inputValidity(1, "notes_10001", false)
        captureQueries = true
        queries.clear()
        model.deleteSet(FIRST_SET)
        model.setType(FIRST_SET, SetType.DROP_SET)
        model.setValue(FIRST_SET, SetField.WEIGHT, 999.0)
        model.completeSet(FIRST_SET, true, CURRENT, 90, editing = false)
        assertTrue(model.flush())
        assertNull(repository.workoutSet(FIRST_SET))
        assertNull(repository.workout(CURRENT)!!.restEndTime)
        model.removeExercise(ENTRY)
        assertTrue(model.flush())
        assertNull(model.error.value)
        val mutationQueries = queries.toList()
        assertNoFullHistoryRead(mutationQueries)
        assertTrue(mutationQueries.any { normalize(it.sql) == "select * from workout_sets where workoutexerciseid = ? order by setnumber, id" && it.arguments == listOf(ENTRY) })
        assertTrue(repository.workoutExerciseSets(ENTRY).isEmpty())
        val remaining = database.workoutDao().workoutExercises(CURRENT).single()
        assertEquals(SECOND_ENTRY, remaining.id)
        assertEquals(0, remaining.orderIndex)
        assertEquals(setOf("set_${SECOND_SET}_reps"), model.invalidInputs.value[CURRENT])
        assertEquals(setOf("notes_10001"), model.invalidInputs.value[1])
        assertEquals(originalHistory, repository.observeWorkouts().first().filter { it.workout.id != CURRENT })
    }

    @Test fun `selecting an existing workout exercise remains satisfied without writes or full history reads`() = runTest(dispatcher) {
        seedLargeHistory()
        val entries = database.workoutDao().workoutExercises(CURRENT)
        val sets = repository.workoutExerciseSets(ENTRY)
        val model = model()
        captureQueries = true
        queries.clear()

        model.addExercise(CURRENT, 1)
        model.addExercise(CURRENT, 1)
        assertTrue(model.flush())
        model.retryFailedChanges()
        assertTrue(model.flush())
        val mutationQueries = queries.toList()

        assertNull(model.error.value)
        assertNoFullHistoryRead(mutationQueries)
        assertTrue(mutationQueries.any { normalize(it.sql) == "select * from workout_exercises where workoutid = ? order by orderindex, id" && it.arguments == listOf(CURRENT) })
        assertFalse(mutationQueries.any { normalize(it.sql).startsWith("insert") })
        assertEquals(entries, database.workoutDao().workoutExercises(CURRENT))
        assertEquals(sets, repository.workoutExerciseSets(ENTRY))
    }

    @Test fun `retry resolves a failed selection already satisfied by another writer without duplicating its set`() = runTest(dispatcher) {
        val (workoutId, exerciseId) = seedSelection()
        database.openHelper.writableDatabase.execSQL("""
            CREATE TRIGGER reject_added_exercise BEFORE INSERT ON workout_exercises
            BEGIN SELECT RAISE(ABORT, 'Injected exercise storage failure'); END
        """.trimIndent())
        val model = model()
        model.addExercise(workoutId, exerciseId)
        assertFalse(model.flush())
        assertTrue(database.workoutDao().workoutExercises(workoutId).isEmpty())
        database.openHelper.writableDatabase.execSQL("DROP TRIGGER reject_added_exercise")
        repository.addExercise(workoutId, exerciseId)
        val entry = database.workoutDao().workoutExercises(workoutId).single()
        val sets = repository.workoutExerciseSets(entry.id)
        captureQueries = true
        queries.clear()

        model.retryFailedChanges()
        model.retryFailedChanges()
        assertTrue(model.flush())
        val mutationQueries = queries.toList()

        assertNull(model.error.value)
        assertNoFullHistoryRead(mutationQueries)
        assertFalse(mutationQueries.any { normalize(it.sql).startsWith("insert") })
        assertEquals(listOf(entry), database.workoutDao().workoutExercises(workoutId))
        assertEquals(sets, repository.workoutExerciseSets(entry.id))
    }

    @Test fun `selection reconciles membership created between its read and attempted insert`() = runTest(dispatcher) {
        val (workoutId, exerciseId) = seedSelection()
        val concurrentWriter = object : WorkoutRepository by repository {
            override suspend fun addExercise(workoutId: Long, exerciseId: Long) {
                repository.addExercise(workoutId, exerciseId)
                // The requested insert now encounters the ordinary duplicate validation.
                repository.addExercise(workoutId, exerciseId)
            }
        }
        val model = WorkoutViewModel(concurrentWriter, { it.message.orEmpty() }).also { store.put("workout", it) }
        captureQueries = true
        queries.clear()
        model.addExercise(workoutId, exerciseId)
        assertTrue(model.flush())
        val mutationQueries = queries.toList()

        assertNull(model.error.value)
        assertNoFullHistoryRead(mutationQueries)
        val entry = database.workoutDao().workoutExercises(workoutId).single()
        assertEquals(1, repository.workoutExerciseSets(entry.id).size)
    }

    private suspend fun seedSelection(): Pair<Long, Long> {
        val exercise = database.workoutDao().insertExercise(Exercise(name = "Selection press", primaryMuscleGroup = MuscleGroup.CHEST))
        return repository.startWorkout("Selection workout") to exercise
    }

    private fun model(): WorkoutViewModel = WorkoutViewModel(repository,
        { userMessage(RuntimeEnvironment.getApplication(), it) }, clock = { NOW })
        .also { store.put("workout", it) }

    private suspend fun seedLargeHistory() = database.withTransaction {
        val dao = database.workoutDao()
        dao.insertExercise(Exercise(id = 1, name = "Press", primaryMuscleGroup = MuscleGroup.CHEST))
        dao.insertExercise(Exercise(id = 2, name = "Pull up", primaryMuscleGroup = MuscleGroup.BACK, exerciseType = ExerciseType.BODYWEIGHT))
        for (id in 1L..CURRENT) {
            dao.insertWorkout(Workout(id = id, name = "History $id", startTime = id * 1_000,
                endTime = if (id == CURRENT) null else id * 1_000 + 500, notes = "History notes $id", muscleGroups = listOf(MuscleGroup.CHEST)))
            val entryId = 10_000 + id
            dao.insertWorkoutExercise(WorkoutExercise(id = entryId, workoutId = id, exerciseId = 1, orderIndex = 0, notes = "Entry notes $id"))
            for (number in listOf(3, 1, 2)) {
                dao.insertSet(WorkoutSet(id = entryId * 10 + number, workoutExerciseId = entryId, setNumber = number,
                    weight = 50.0 + number, reps = 8 + number, completed = id != CURRENT))
            }
        }
        dao.insertWorkoutExercise(WorkoutExercise(id = SECOND_ENTRY, workoutId = CURRENT, exerciseId = 2, orderIndex = 1))
        dao.insertSet(WorkoutSet(id = SECOND_SET, workoutExerciseId = SECOND_ENTRY, setNumber = 1, reps = 8))
    }

    private fun queryPlan(sql: String): List<String> = database.openHelper.writableDatabase.query("EXPLAIN QUERY PLAN $sql").use { cursor ->
        buildList { while (cursor.moveToNext()) add(cursor.getString(cursor.getColumnIndexOrThrow("detail"))) }
    }

    private fun assertNoFullHistoryRead(captured: List<CapturedQuery>) {
        assertFalse("Mutation unexpectedly materialized full history", captured.any { isFullHistoryQuery(it.sql) })
        assertTrue("Workout SELECTs must use the target ID", captured.filter { normalize(it.sql).startsWith("select * from workouts ") }
            .all { normalize(it.sql) == "select * from workouts where id = ?" })
    }

    private fun isFullHistoryQuery(sql: String) = normalize(sql).startsWith("select * from workouts order by starttime")
    private fun isApplicationSelect(sql: String) = normalize(sql).let { normalized ->
        normalized.startsWith("select * from workouts ") || normalized.startsWith("select * from workout_sets ") ||
            normalized.startsWith("select * from workout_exercises ") || normalized.startsWith("select * from exercises ")
    }
    private fun normalize(sql: String) = sql.replace("`", "").lowercase(Locale.ROOT).replace(Regex("\\s+"), " ").trim()
    private data class CapturedQuery(val sql: String, val arguments: List<Any?>)

    private companion object {
        const val CURRENT = 1_000L
        const val ENTRY = 11_000L
        const val FIRST_SET = 110_001L
        const val SECOND_ENTRY = 11_001L
        const val SECOND_SET = 110_011L
        const val NOW = 1_000_100L
    }
}
