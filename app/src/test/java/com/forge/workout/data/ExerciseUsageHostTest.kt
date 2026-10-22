package com.forge.workout.data

import android.app.Application
import androidx.room.Room
import com.forge.workout.backup.ForgeBackup
import com.forge.workout.backup.RoomBackupRepository
import com.forge.workout.data.database.ForgeDatabase
import com.forge.workout.model.Exercise
import com.forge.workout.model.ExerciseUsage
import com.forge.workout.model.MuscleGroup
import com.forge.workout.model.SetType
import com.forge.workout.model.Workout
import com.forge.workout.model.WorkoutExercise
import com.forge.workout.model.WorkoutSet
import com.forge.workout.repository.RoomWorkoutRepository
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class ExerciseUsageHostTest {
    private lateinit var database: ForgeDatabase
    private lateinit var repository: RoomWorkoutRepository

    @Before fun setUp() {
        database = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), ForgeDatabase::class.java).build()
        repository = RoomWorkoutRepository(database)
    }

    @After fun tearDown() { database.close() }

    @Test fun `usage counts distinct completed workouts with a completed set and takes latest start date`() = runBlocking {
        val default = exercise("Default press", custom = false)
        val custom = exercise("Custom press")
        val incomplete = exercise("Never completed")
        val activeOnly = exercise("Active only")
        val unused = exercise("Unused custom")
        val archived = exercise("Archived used press")
        session(1000, 30000, listOf(default to listOf(true, true, false), custom to listOf(true),
            incomplete to listOf(false), archived to listOf(true)))
        session(5000, 6000, listOf(default to listOf(true), custom to listOf(false), incomplete to listOf(false)))
        session(9000, 10000, listOf(custom to listOf(true, true)))
        session(11000, null, listOf(default to listOf(true), activeOnly to listOf(true, true)))
        repository.deleteExercise(archived)

        val usage = repository.observeExerciseUsage().first().associateBy { it.exerciseId }
        assertEquals(3, usage.size)
        assertEquals(ExerciseUsage(default, 5000, 2), usage.getValue(default))
        assertEquals(ExerciseUsage(custom, 9000, 2), usage.getValue(custom))
        assertEquals(ExerciseUsage(archived, 1000, 1), usage.getValue(archived))
        assertFalse(incomplete in usage)
        assertFalse(activeOnly in usage)
        assertFalse(unused in usage)
        assertTrue(repository.observeExercises().first().none { it.id == archived })
    }

    @Test fun `empty workout history produces no usage rows even with default and custom exercises`() = runBlocking {
        repository.ensureSeeded()
        exercise("Fresh custom")
        assertTrue(repository.observeExercises().first().isNotEmpty())
        assertTrue(repository.observeExerciseUsage().first().isEmpty())
    }

    @Test fun `usage flow updates after finishing active workout without counting its sets prematurely`() = runBlocking {
        val exerciseId = exercise("Press")
        val activeOnly = exercise("Core press")
        session(1000, 2000, listOf(exerciseId to listOf(true)))
        val activeId = session(3000, null, listOf(exerciseId to listOf(true, true), activeOnly to listOf(true)))
        assertEquals(listOf(ExerciseUsage(exerciseId, 1000, 1)), repository.observeExerciseUsage().first())
        val changed = async(start = CoroutineStart.UNDISPATCHED) {
            withTimeout(10000) {
                repository.observeExerciseUsage().first { rows -> rows.associateBy { it.exerciseId }[exerciseId]?.workoutCount == 2 }
            }
        }
        repository.finishWorkout(activeId)
        assertEquals(mapOf(exerciseId to ExerciseUsage(exerciseId, 3000, 2), activeOnly to ExerciseUsage(activeOnly, 3000, 1)),
            changed.await().associateBy { it.exerciseId })
    }

    @Test fun `usage flow falls back to earlier completed date after deleting latest workout`() = runBlocking {
        val exerciseId = exercise("Press")
        session(1000, 2000, listOf(exerciseId to listOf(true)))
        val newestId = session(5000, 6000, listOf(exerciseId to listOf(true, true)))
        assertEquals(listOf(ExerciseUsage(exerciseId, 5000, 2)), repository.observeExerciseUsage().first())
        val changed = async(start = CoroutineStart.UNDISPATCHED) {
            withTimeout(10000) { repository.observeExerciseUsage().first { it == listOf(ExerciseUsage(exerciseId, 1000, 1)) } }
        }
        repository.deleteWorkout(newestId)
        assertEquals(listOf(ExerciseUsage(exerciseId, 1000, 1)), changed.await())
    }

    @Test fun `unchecking the only completed historical set removes that exercise usage`() = runBlocking {
        val exerciseId = exercise("Press")
        val workoutId = session(1000, 2000, listOf(exerciseId to listOf(true, false)))
        assertEquals(listOf(ExerciseUsage(exerciseId, 1000, 1)), repository.observeExerciseUsage().first())
        val changed = async(start = CoroutineStart.UNDISPATCHED) {
            withTimeout(10000) { repository.observeExerciseUsage().first { it.isEmpty() } }
        }
        val set = database.workoutDao().workoutDetail(workoutId)!!.exercises.single().sortedSets.first()
        repository.updateSet(set.copy(completed = false))
        assertTrue(changed.await().isEmpty())
    }

    @Test fun `restoring replacement backup refreshes usage from imported history and excludes imported active sets`() = runBlocking {
        val original = exercise("Original press")
        session(1000, 2000, listOf(original to listOf(true)))
        val replacement = ForgeBackup(
            exercises = listOf(Exercise(id = 100, name = "Imported custom", primaryMuscleGroup = MuscleGroup.CHEST),
                Exercise(id = 101, name = "Imported active", primaryMuscleGroup = MuscleGroup.CORE)),
            workouts = listOf(Workout(id = 200, name = "Imported finished", startTime = 8000, endTime = 9000),
                Workout(id = 201, name = "Imported active", startTime = 10000)),
            workoutExercises = listOf(WorkoutExercise(id = 300, workoutId = 200, exerciseId = 100, orderIndex = 0),
                WorkoutExercise(id = 301, workoutId = 201, exerciseId = 101, orderIndex = 0)),
            sets = listOf(WorkoutSet(id = 400, workoutExerciseId = 300, setNumber = 1, weight = 50.0, reps = 8, completed = true),
                WorkoutSet(id = 401, workoutExerciseId = 300, setNumber = 2, weight = 50.0, reps = 8, completed = true),
                WorkoutSet(id = 402, workoutExerciseId = 301, setNumber = 1, reps = 8, completed = true)),
        )
        val expected = listOf(ExerciseUsage(100, 8000, 1))
        val changed = async(start = CoroutineStart.UNDISPATCHED) {
            withTimeout(10000) { repository.observeExerciseUsage().first { it == expected } }
        }
        RoomBackupRepository(database).restore(replacement)
        assertEquals(expected, changed.await())
        assertEquals(listOf(100L, 101L), repository.observeExercises().first().map { it.id }.sorted())
        assertFalse(repository.observeExerciseUsage().first().any { it.exerciseId == original })
    }

    private suspend fun exercise(name: String, custom: Boolean = true): Long =
        database.workoutDao().insertExercise(Exercise(name = name, primaryMuscleGroup = MuscleGroup.CHEST, isCustom = custom))

    private suspend fun session(start: Long, end: Long?, exercises: List<Pair<Long, List<Boolean>>>): Long {
        val dao = database.workoutDao()
        val id = dao.insertWorkout(Workout(name = "Session $start", startTime = start, endTime = end))
        for ((exerciseId, completion) in exercises) {
            repository.addExercise(id, exerciseId)
            val entryId = dao.workoutExercises(id).single { it.exerciseId == exerciseId }.id
            for ((index, completed) in completion.withIndex()) {
                if (index > 0) repository.addSet(entryId)
                val set = dao.sets(entryId).last()
                repository.updateSet(set.copy(weight = 50.0, reps = 8, completed = completed,
                    setType = if (index == 0) SetType.WARM_UP else SetType.FAILURE))
            }
        }
        return id
    }
}
