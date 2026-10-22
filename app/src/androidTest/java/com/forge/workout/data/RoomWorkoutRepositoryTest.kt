package com.forge.workout.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.forge.workout.data.database.ForgeDatabase
import com.forge.workout.data.seed.DefaultExercises
import com.forge.workout.model.Exercise
import com.forge.workout.model.ExerciseType
import com.forge.workout.model.MuscleGroup
import com.forge.workout.model.Workout
import com.forge.workout.repository.RoomWorkoutRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RoomWorkoutRepositoryTest {
    private lateinit var database: ForgeDatabase
    private lateinit var repository: RoomWorkoutRepository

    @Before fun setup() {
        database = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), ForgeDatabase::class.java).build()
        repository = RoomWorkoutRepository(database)
    }

    @After fun close() = database.close()

    @Test fun seedingIsIdempotentAndDoesNotRecreateDeletedDefaults() = runTest {
        repository.ensureSeeded()
        val seeded = repository.observeExercises().first()
        assertEquals(DefaultExercises.all.size, seeded.size)
        database.workoutDao().deleteExercise(seeded.first().id)
        repository.ensureSeeded()
        assertEquals(seeded.size - 1, repository.observeExercises().first().size)
        assertTrue(repository.observeWorkouts().first().isEmpty())
    }

    @Test fun activeWorkoutIsUniqueAndSurvivesRepositoryRecreation() = runTest {
        val id = repository.startWorkout("Morning workout")
        assertEquals(id, repository.startWorkout("Another workout"))
        val reopened = RoomWorkoutRepository(database)
        assertEquals(id, reopened.observeWorkouts().first().single().workout.id)
        assertNull(reopened.observeWorkouts().first().single().workout.endTime)
    }

    @Test fun workoutSetPersistencePrefillAndCascadeDeletion() = runTest {
        val exerciseId = createExercise()
        val workoutId = repository.startWorkout("Strength")
        repository.addExercise(workoutId, exerciseId)
        val first = database.workoutDao().workoutDetail(workoutId)!!.exercises.single().sets.single()
        repository.updateSet(first.copy(weight = 70.0, reps = 10, completed = true))
        repository.addSet(first.workoutExerciseId)
        val sets = database.workoutDao().sets(first.workoutExerciseId)
        assertEquals(2, sets.size)
        assertEquals(70.0, sets.last().weight, 0.001)
        assertEquals(10, sets.last().reps)
        assertFalse(sets.last().completed)
        repository.finishWorkout(workoutId)
        assertNotNull(database.workoutDao().workout(workoutId)!!.endTime)
        val nextId = repository.startWorkout("Next session")
        repository.addExercise(nextId, exerciseId)
        val prefilled = database.workoutDao().workoutDetail(nextId)!!.exercises.single().sets.single()
        assertEquals(70.0, prefilled.weight, 0.001)
        assertFalse(prefilled.completed)
        repository.deleteWorkout(workoutId)
        assertNull(database.workoutDao().workoutSet(first.id))
        assertTrue(database.workoutDao().workoutExercises(workoutId).isEmpty())
    }

    @Test fun deletingReferencedCustomExercisePreservesHistory() = runTest {
        val exerciseId = createExercise()
        val workoutId = repository.startWorkout("Strength")
        repository.addExercise(workoutId, exerciseId)
        repository.deleteExercise(exerciseId)
        assertTrue(repository.observeExercises().first().isEmpty())
        val detail = database.workoutDao().workoutDetail(workoutId)!!
        assertEquals("Test Press", detail.exercises.single().exercise.name)
        assertTrue(detail.exercises.single().exercise.archived)
    }

    @Test fun deletingUnreferencedCustomExerciseRemovesIt() = runTest {
        val exerciseId = createExercise()
        repository.deleteExercise(exerciseId)
        assertNull(database.workoutDao().exercise(exerciseId))
    }

    @Test fun referencedExerciseTypeCannotReinterpretHistory() = runTest {
        val exerciseId = createExercise()
        val workoutId = repository.startWorkout("Strength")
        repository.addExercise(workoutId, exerciseId)
        val exercise = database.workoutDao().exercise(exerciseId)!!
        var rejected = false
        try { repository.saveExercise(exercise.copy(exerciseType = ExerciseType.DURATION)) }
        catch (_: IllegalArgumentException) { rejected = true }
        assertTrue(rejected)
        repository.saveExercise(exercise.copy(name = "Renamed press", notes = "Controlled tempo"))
        assertEquals("Renamed press", database.workoutDao().workoutDetail(workoutId)!!.exercises.single().exercise.name)
    }

    @Test fun routineCanBeReorderedRenamedStartedAndDeleted() = runTest {
        val firstId = createExercise()
        val secondId = repository.saveExercise(Exercise(name = "Test Row", primaryMuscleGroup = MuscleGroup.BACK))
        val routineId = repository.saveRoutine(name = "Upper")
        repository.addRoutineExercise(routineId, firstId)
        repository.addRoutineExercise(routineId, secondId)
        val entry = database.workoutDao().routineDetail(routineId)!!.sortedExercises.last()
        repository.moveRoutineExercise(entry.entry.id, -1)
        repository.saveRoutine(routineId, "Upper body")
        val routine = repository.observeRoutines().first().single()
        assertEquals("Upper body", routine.routine.name)
        assertEquals(secondId, routine.sortedExercises.first().exercise.id)
        val workoutId = repository.startWorkout("New workout", routineId = routineId)
        val workout = database.workoutDao().workoutDetail(workoutId)!!
        assertEquals("Upper body", workout.workout.name)
        assertEquals(listOf(secondId, firstId), workout.sortedExercises.map { it.exercise.id })
        assertTrue(workout.exercises.all { it.sets.size == 1 })
        repository.deleteRoutine(routineId)
        assertTrue(repository.observeRoutines().first().isEmpty())
        assertEquals(2, database.workoutDao().workoutDetail(workoutId)!!.exercises.size)
    }

    @Test fun setDeletionRenumbersRemainingSets() = runTest {
        val exerciseId = createExercise()
        val workoutId = repository.startWorkout("Strength")
        repository.addExercise(workoutId, exerciseId)
        val entryId = database.workoutDao().workoutExercises(workoutId).single().id
        repository.addSet(entryId)
        repository.addSet(entryId)
        repository.deleteSet(database.workoutDao().sets(entryId)[1].id)
        assertEquals(listOf(1, 2), database.workoutDao().sets(entryId).map { it.setNumber })
    }

    @Test fun repeatWorkoutCopiesValuesWithoutCompletedState() = runTest {
        val exerciseId = createExercise()
        val workoutId = repository.startWorkout("Original")
        repository.addExercise(workoutId, exerciseId)
        val first = database.workoutDao().workoutDetail(workoutId)!!.exercises.single().sets.single()
        repository.updateSet(first.copy(weight = 40.0, reps = 12, completed = true))
        repository.finishWorkout(workoutId)
        val repeated = repository.startWorkout("New workout", repeatWorkoutId = workoutId)
        assertEquals("Original", database.workoutDao().workout(repeated)!!.name)
        val set = database.workoutDao().workoutDetail(repeated)!!.exercises.single().sets.single()
        assertEquals(40.0, set.weight, 0.001)
        assertEquals(12, set.reps)
        assertFalse(set.completed)
    }

    @Test fun emptyWorkoutCannotBeFinished() = runTest {
        val workoutId = repository.startWorkout("Empty")
        var rejected = false
        try { repository.finishWorkout(workoutId) } catch (_: IllegalArgumentException) { rejected = true }
        assertTrue(rejected)
        assertNull(database.workoutDao().workout(workoutId)!!.endTime)
    }

    @Test fun editingOldWorkoutDoesNotPrefillFromFutureHistoryOrItself() = runTest {
        val exerciseId = createExercise()
        val dao = database.workoutDao()
        val earliest = dao.insertWorkout(Workout(name = "Earliest", startTime = 1000, endTime = 2000))
        repository.addExercise(earliest, exerciseId)
        val earlierSet = dao.workoutDetail(earliest)!!.exercises.single().sets.single()
        repository.updateSet(earlierSet.copy(weight = 40.0, reps = 12, completed = true))
        val middle = dao.insertWorkout(Workout(name = "Middle", startTime = 3000, endTime = 4000))
        val future = dao.insertWorkout(Workout(name = "Future", startTime = 5000, endTime = 6000))
        repository.addExercise(future, exerciseId)
        val futureSet = dao.workoutDetail(future)!!.exercises.single().sets.single()
        repository.updateSet(futureSet.copy(weight = 80.0, reps = 8, completed = true))
        repository.addExercise(middle, exerciseId)
        val prefilled = dao.workoutDetail(middle)!!.exercises.single().sets.single()
        assertEquals(40.0, prefilled.weight, 0.001)
        assertEquals(12, prefilled.reps)
        assertEquals(earlierSet.id, dao.previousSet(exerciseId, middle, 3000)!!.id)
    }

    @Test fun duplicateNamesAreRejectedByRepositoryAndDatabase() = runTest {
        createExercise()
        var rejected = false
        try {
            repository.saveExercise(Exercise(name = " test press ", primaryMuscleGroup = MuscleGroup.CHEST))
        } catch (_: IllegalArgumentException) { rejected = true }
        assertTrue(rejected)
        assertEquals(1, repository.observeExercises().first().size)
    }

    @Test fun routineExerciseRemovalCascadesAndRenumbers() = runTest {
        val firstId = createExercise()
        val secondId = repository.saveExercise(Exercise(name = "Test Curl", primaryMuscleGroup = MuscleGroup.BICEPS))
        val routineId = repository.saveRoutine(name = "Arms")
        repository.addRoutineExercise(routineId, firstId)
        repository.addRoutineExercise(routineId, secondId)
        repository.removeRoutineExercise(database.workoutDao().routineExercises(routineId).first().id)
        val remaining = database.workoutDao().routineExercises(routineId).single()
        assertEquals(secondId, remaining.exerciseId)
        assertEquals(0, remaining.orderIndex)
    }

    @Test fun deletingWorkoutExerciseCascadesSetsAndRenumbers() = runTest {
        val firstId = createExercise()
        val secondId = repository.saveExercise(Exercise(name = "Test Row", primaryMuscleGroup = MuscleGroup.BACK))
        val workoutId = repository.startWorkout("Upper")
        repository.addExercise(workoutId, firstId)
        repository.addExercise(workoutId, secondId)
        val removed = database.workoutDao().workoutDetail(workoutId)!!.sortedExercises.first()
        repository.removeWorkoutExercise(removed.entry.id)
        assertNull(database.workoutDao().workoutSet(removed.sets.single().id))
        assertEquals(0, database.workoutDao().workoutExercises(workoutId).single().orderIndex)
    }

    private suspend fun createExercise(): Long = repository.saveExercise(Exercise(name = "Test Press", primaryMuscleGroup = MuscleGroup.CHEST))
}
