package com.forge.workout.data

import android.app.Application
import android.database.sqlite.SQLiteConstraintException
import androidx.room.Room
import com.forge.workout.data.database.ForgeDatabase
import com.forge.workout.data.database.ForgeConverters
import com.forge.workout.data.seed.DefaultExercises
import com.forge.workout.model.Exercise
import com.forge.workout.model.ExerciseType
import com.forge.workout.model.MuscleGroup
import com.forge.workout.model.SetType
import com.forge.workout.model.Workout
import com.forge.workout.repository.RoomWorkoutRepository
import com.forge.workout.utils.WorkoutCalculations
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** Runs the generated Room DAO and repository against Android SQLite on the host. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class RoomHostTest {
    private lateinit var database: ForgeDatabase
    private lateinit var repository: RoomWorkoutRepository

    @Before fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            RuntimeEnvironment.getApplication(), ForgeDatabase::class.java,
        ).build()
        repository = RoomWorkoutRepository(database)
    }

    @After fun tearDown() {
        database.close()
        RuntimeEnvironment.getApplication().deleteDatabase("forge.db")
    }

    @Test fun `muscle groups round trip canonically and can be cleared without changing workout contents`() = runBlocking {
        val exerciseId = press()
        val workoutId = repository.startWorkout("Arms")
        repository.addExercise(workoutId, exerciseId)
        repository.updateWorkout(workoutId, "Arms", "Controlled tempo")
        repository.setRestEndTime(workoutId, 123456789L)
        val before = database.workoutDao().workoutDetail(workoutId)!!

        repository.updateMuscleGroups(workoutId, linkedSetOf(MuscleGroup.TRICEPS, MuscleGroup.CHEST, MuscleGroup.BICEPS))
        val saved = database.workoutDao().workoutDetail(workoutId)!!
        assertEquals(listOf(MuscleGroup.CHEST, MuscleGroup.BICEPS, MuscleGroup.TRICEPS), saved.workout.muscleGroups)
        assertEquals(before.exercises, saved.exercises)
        assertEquals(before.workout.notes, saved.workout.notes)
        assertEquals(before.workout.restEndTime, saved.workout.restEndTime)
        database.openHelper.writableDatabase.query("SELECT muscleGroups FROM workouts WHERE id = $workoutId").use {
            assertTrue(it.moveToFirst())
            assertEquals("CHEST,BICEPS,TRICEPS", it.getString(0))
        }

        repository.updateMuscleGroups(workoutId, emptySet())
        assertTrue(database.workoutDao().workout(workoutId)!!.muscleGroups.isEmpty())
        database.openHelper.writableDatabase.query("SELECT muscleGroups FROM workouts WHERE id = $workoutId").use {
            assertTrue(it.moveToFirst())
            assertEquals("", it.getString(0))
        }
    }

    @Test fun `muscle groups survive production database close reopen finish and name edits`() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        database.close()
        context.deleteDatabase("forge.db")
        database = ForgeDatabase.create(context)
        repository = RoomWorkoutRepository(database)
        val exerciseId = press()
        val workoutId = repository.startWorkout("Upper")
        repository.addExercise(workoutId, exerciseId)
        val set = database.workoutDao().workoutDetail(workoutId)!!.exercises.single().sets.single()
        repository.updateSet(set.copy(weight = 60.0, reps = 12, completed = true))
        repository.updateMuscleGroups(workoutId, setOf(MuscleGroup.BACK, MuscleGroup.CHEST))
        repository.updateWorkout(workoutId, "Upper strength", "Good session")
        repository.finishWorkout(workoutId)
        val original = database.workoutDao().workoutDetail(workoutId)!!
        database.close()

        database = ForgeDatabase.create(context)
        repository = RoomWorkoutRepository(database)
        val recovered = repository.observeWorkouts().first().single()
        assertEquals(original, recovered)
        assertEquals(listOf(MuscleGroup.CHEST, MuscleGroup.BACK), recovered.workout.muscleGroups)
        assertEquals("Upper strength", recovered.workout.name)
        assertNotNull(recovered.workout.endTime)
        assertEquals(720.0, WorkoutCalculations.volume(recovered), 0.001)
    }

    @Test fun `every set type survives actual Room round trip and production database reopen`() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        database.close()
        context.deleteDatabase("forge.db")
        database = ForgeDatabase.create(context)
        repository = RoomWorkoutRepository(database)
        val exerciseId = press()
        val workoutId = repository.startWorkout("Typed sets")
        repository.addExercise(workoutId, exerciseId)
        val dao = database.workoutDao()
        val entryId = dao.workoutExercises(workoutId).single().id
        for ((index, type) in SetType.entries.withIndex()) {
            if (index > 0) repository.addSet(entryId)
            val set = dao.sets(entryId).last()
            assertEquals(SetType.WORKING, set.setType)
            repository.updateSet(set.copy(weight = 50.0 + index, reps = 8, completed = true, setType = type))
        }
        val original = dao.workoutDetail(workoutId)!!
        assertEquals(SetType.entries.toList(), original.exercises.single().sortedSets.map { it.setType })
        database.openHelper.writableDatabase.query("SELECT setType FROM workout_sets ORDER BY setNumber").use { cursor ->
            val stored = mutableListOf<String>()
            while (cursor.moveToNext()) stored += cursor.getString(0)
            assertEquals(SetType.entries.map { it.name }, stored)
        }
        database.close()
        database = ForgeDatabase.create(context)
        repository = RoomWorkoutRepository(database)
        assertEquals(original, repository.observeWorkouts().first().single())
    }

    @Test fun `adding a set and starting from historical values reset type while preserving numbers and source`() = runBlocking {
        val exerciseId = press()
        val sourceId = repository.startWorkout("Original typed session")
        repository.addExercise(sourceId, exerciseId)
        val dao = database.workoutDao()
        val sourceSet = dao.workoutDetail(sourceId)!!.exercises.single().sets.single()
        repository.updateSet(sourceSet.copy(weight = 85.0, reps = 7, completed = true, setType = SetType.FAILURE))
        repository.finishWorkout(sourceId)
        val source = dao.workoutDetail(sourceId)!!
        val nextId = repository.startWorkout("Next session")
        repository.addExercise(nextId, exerciseId)
        val entry = dao.workoutDetail(nextId)!!.exercises.single()
        val prefilled = entry.sets.single()
        assertEquals(85.0, prefilled.weight, 0.000001)
        assertEquals(7, prefilled.reps)
        assertFalse(prefilled.completed)
        assertEquals(SetType.WORKING, prefilled.setType)
        val edited = prefilled.copy(weight = 75.0, reps = 9, setType = SetType.DROP_SET)
        repository.updateSet(edited)
        repository.addSet(entry.entry.id)
        val added = dao.sets(entry.entry.id).last()
        assertEquals(edited.copy(id = added.id, setNumber = 2, completed = false, setType = SetType.WORKING), added)
        assertEquals(edited, dao.workoutSet(edited.id))
        assertEquals(source, dao.workoutDetail(sourceId))
    }

    @Test fun `muscle group converter removes duplicates and normalizes stored order`() {
        val converter = ForgeConverters()
        assertEquals("CHEST,BACK", converter.muscleGroupsToString(listOf(MuscleGroup.BACK, MuscleGroup.CHEST, MuscleGroup.BACK)))
        assertEquals(listOf(MuscleGroup.CHEST, MuscleGroup.BACK), converter.stringToMuscleGroups("BACK,CHEST,BACK"))
        assertTrue(converter.stringToMuscleGroups("").isEmpty())
        assertTrue(converter.stringToMuscleGroups("UNKNOWN_FUTURE_GROUP").isEmpty())
    }

    @Test fun `updating muscle groups for missing workout is rejected without creating a row`() = runBlocking {
        expectInvalid { repository.updateMuscleGroups(1234, setOf(MuscleGroup.CHEST)) }
        assertTrue(repository.observeWorkouts().first().isEmpty())
    }

    @Test fun `seed metadata prevents reseeding and production history starts empty`() = runBlocking {
        repository.ensureSeeded()
        val original = repository.observeExercises().first()
        assertEquals(DefaultExercises.all.size, original.size)
        assertTrue(original.all { !it.isCustom })
        database.workoutDao().deleteExercise(original.first().id)
        repository.ensureSeeded()
        assertEquals(original.size - 1, repository.observeExercises().first().size)
        assertNotNull(database.workoutDao().metadata("default_exercises_v1"))
        assertTrue(repository.observeWorkouts().first().isEmpty())
    }

    @Test fun `active session recovers sets notes and rest deadline without creating a second session`() = runBlocking {
        val exerciseId = press()
        val workoutId = repository.startWorkout("Upper body")
        repository.addExercise(workoutId, exerciseId)
        val entry = database.workoutDao().workoutDetail(workoutId)!!.exercises.single()
        repository.updateSet(entry.sets.single().copy(weight = 65.0, reps = 9, completed = true))
        repository.updateExerciseNotes(entry.entry.id, "Slow eccentric")
        repository.updateWorkout(workoutId, "Upper body", "Good energy")
        repository.setRestEndTime(workoutId, 123456789L)

        val recovered = RoomWorkoutRepository(database)
        assertEquals(workoutId, recovered.startWorkout("Another session"))
        val detail = recovered.observeWorkouts().first().single()
        assertNull(detail.workout.endTime)
        assertEquals("Good energy", detail.workout.notes)
        assertEquals(123456789L, detail.workout.restEndTime)
        assertEquals("Slow eccentric", detail.exercises.single().entry.notes)
        assertEquals(65.0, detail.exercises.single().sets.single().weight, 0.001)
        assertTrue(detail.exercises.single().sets.single().completed)
    }

    @Test fun `finish persists complete set volume and clears rest while excluding planned sets`() = runBlocking {
        val exerciseId = press()
        val workoutId = repository.startWorkout("Strength")
        repository.addExercise(workoutId, exerciseId)
        val dao = database.workoutDao()
        val entryId = dao.workoutExercises(workoutId).single().id
        val first = dao.sets(entryId).single()
        repository.updateSet(first.copy(weight = 70.0, reps = 10, completed = true))
        repository.addSet(entryId)
        repository.updateSet(dao.sets(entryId).last().copy(weight = 75.0, reps = 8, completed = true))
        repository.addSet(entryId)
        repository.setRestEndTime(workoutId, System.currentTimeMillis() + 90000)
        repository.finishWorkout(workoutId)

        val stored = repository.observeWorkouts().first().single()
        val summary = WorkoutCalculations.summary(stored)
        assertNotNull(stored.workout.endTime)
        assertNull(stored.workout.restEndTime)
        assertEquals(2, summary.setCount)
        assertEquals(18, summary.totalReps)
        assertEquals(1300.0, summary.totalVolumeKg, 0.001)
        assertEquals(3, stored.exercises.single().sets.size)
    }

    @Test fun `prefill uses earlier completed history rather than future history or the same workout`() = runBlocking {
        val exerciseId = press()
        val dao = database.workoutDao()
        val earlier = dao.insertWorkout(Workout(name = "Earlier", startTime = 1000, endTime = 2000))
        repository.addExercise(earlier, exerciseId)
        val earlierSet = dao.workoutDetail(earlier)!!.exercises.single().sets.single()
        repository.updateSet(earlierSet.copy(weight = 40.0, reps = 12, completed = true))
        val middle = dao.insertWorkout(Workout(name = "Middle", startTime = 3000, endTime = 4000))
        val later = dao.insertWorkout(Workout(name = "Later", startTime = 5000, endTime = 6000))
        repository.addExercise(later, exerciseId)
        repository.updateSet(dao.workoutDetail(later)!!.exercises.single().sets.single().copy(weight = 90.0, reps = 5, completed = true))

        repository.addExercise(middle, exerciseId)
        val prefilled = dao.workoutDetail(middle)!!.exercises.single().sets.single()
        assertEquals(40.0, prefilled.weight, 0.001)
        assertEquals(12, prefilled.reps)
        assertFalse(prefilled.completed)
        assertEquals(earlierSet.id, dao.previousSet(exerciseId, middle, 3000)!!.id)
    }

    @Test fun `routine edits preserve ordering and deleting template preserves started workout`() = runBlocking {
        val pressId = press()
        val rowId = repository.saveExercise(Exercise(name = "Cable row", primaryMuscleGroup = MuscleGroup.BACK))
        val routineId = repository.saveRoutine(name = "Upper")
        repository.addRoutineExercise(routineId, pressId)
        repository.addRoutineExercise(routineId, rowId)
        val dao = database.workoutDao()
        repository.moveRoutineExercise(dao.routineExercises(routineId).last().id, -1)
        repository.saveRoutine(routineId, "Upper strength")
        val workoutId = repository.startWorkout("Fallback name", routineId = routineId)
        assertEquals("Upper strength", dao.workout(workoutId)!!.name)
        assertEquals(listOf(rowId, pressId), dao.workoutDetail(workoutId)!!.sortedExercises.map { it.exercise.id })
        assertTrue(dao.workoutDetail(workoutId)!!.exercises.all { it.sets.single().completed.not() })
        repository.deleteRoutine(routineId)
        assertTrue(repository.observeRoutines().first().isEmpty())
        assertTrue(dao.routineExercises(routineId).isEmpty())
        assertEquals(2, dao.workoutDetail(workoutId)!!.exercises.size)
    }

    @Test fun `repeat creates one set from first incomplete source set and preserves workout info`() = runBlocking {
        val exerciseId = press()
        val originalId = repository.startWorkout("Push day")
        repository.addExercise(originalId, exerciseId)
        val dao = database.workoutDao()
        val entry = dao.workoutDetail(originalId)!!.exercises.single()
        repository.updateWorkout(originalId, "Push day", "Keep the session notes")
        repository.updateMuscleGroups(originalId, setOf(MuscleGroup.TRICEPS, MuscleGroup.CHEST))
        repository.updateExerciseNotes(entry.entry.id, "Paused reps")
        repository.updateSet(entry.sets.single().copy(weight = 60.0, reps = 10, completed = false, setType = SetType.WARM_UP))
        repository.addSet(entry.entry.id)
        repository.updateSet(dao.sets(entry.entry.id).last().copy(weight = 80.0, reps = 6, completed = true, setType = SetType.DROP_SET))
        repository.addSet(entry.entry.id)
        repository.updateSet(dao.sets(entry.entry.id).last().copy(weight = 90.0, reps = 4, completed = true, setType = SetType.FAILURE))
        repository.finishWorkout(originalId)
        val source = dao.workoutDetail(originalId)!!
        assertEquals(3, source.exercises.single().sets.size)
        val repeatedId = repository.startWorkout("Fallback", repeatWorkoutId = originalId)
        val repeated = dao.workoutDetail(repeatedId)!!
        assertEquals("Push day", repeated.workout.name)
        assertEquals(source.workout.notes, repeated.workout.notes)
        assertEquals(source.workout.muscleGroups, repeated.workout.muscleGroups)
        assertEquals("Paused reps", repeated.exercises.single().entry.notes)
        assertNull(repeated.workout.endTime)
        assertNull(repeated.workout.restEndTime)
        val copied = repeated.exercises.single().sets.single()
        assertEquals(60.0, copied.weight, 0.001)
        assertEquals(10, copied.reps)
        assertEquals(1, copied.setNumber)
        assertFalse(copied.completed)
        assertEquals(SetType.WORKING, copied.setType)
        assertTrue(copied.id != entry.sets.single().id)
        assertTrue(repeated.exercises.single().entry.id != entry.entry.id)
        assertEquals(repeated.exercises.single().entry.id, copied.workoutExerciseId)
        assertEquals(source, dao.workoutDetail(originalId))

        val edited = copied.copy(weight = 75.0, reps = 9, completed = true, setType = SetType.FAILURE)
        repository.updateSet(edited)
        repository.addSet(repeated.exercises.single().entry.id)
        val next = dao.sets(repeated.exercises.single().entry.id).last()
        assertEquals(edited.copy(id = next.id, setNumber = 2, completed = false, setType = SetType.WORKING), next)
        assertEquals(source, dao.workoutDetail(originalId))
    }

    @Test fun `repeat preserves all mixed type and archived exercises in relative order with one first set`() = runBlocking {
        val dao = database.workoutDao()
        val sourceId = dao.insertWorkout(Workout(name = "Mixed session", startTime = 1000, endTime = 2000,
            notes = "Keep every exercise", restEndTime = 12345, muscleGroups = listOf(MuscleGroup.CHEST, MuscleGroup.CORE, MuscleGroup.CARDIO)))
        val samples = listOf(
            Triple("Weighted press", MuscleGroup.CHEST, ExerciseType.WEIGHT_REPS),
            Triple("Bodyweight push up", MuscleGroup.CHEST, ExerciseType.BODYWEIGHT),
            Triple("Core repetitions", MuscleGroup.CORE, ExerciseType.REPS_ONLY),
            Triple("Archived plank", MuscleGroup.CORE, ExerciseType.DURATION),
            Triple("Outdoor run", MuscleGroup.CARDIO, ExerciseType.DISTANCE),
        )
        val entryIds = mutableListOf<Long>()
        for ((name, muscle, type) in samples) {
            val exerciseId = repository.saveExercise(Exercise(name = name, primaryMuscleGroup = muscle, exerciseType = type))
            repository.addExercise(sourceId, exerciseId)
            val entry = dao.workoutDetail(sourceId)!!.exercises.single { it.exercise.id == exerciseId }
            entryIds += entry.entry.id
            val first = when (type) {
                ExerciseType.WEIGHT_REPS -> entry.sets.single().copy(weight = 72.5, reps = 8)
                ExerciseType.BODYWEIGHT -> entry.sets.single().copy(reps = 15)
                ExerciseType.REPS_ONLY -> entry.sets.single().copy(reps = 20)
                ExerciseType.DURATION -> entry.sets.single().copy(duration = 90)
                ExerciseType.DISTANCE -> entry.sets.single().copy(duration = 240, distance = 2.4)
            }
            repository.updateSet(first.copy(setType = SetType.entries[(entryIds.size - 1) % SetType.entries.size]))
            repository.updateExerciseNotes(entry.entry.id, "Notes for $name")
            repository.addSet(entry.entry.id)
            val later = dao.sets(entry.entry.id).last()
            repository.updateSet(when (type) {
                ExerciseType.WEIGHT_REPS -> later.copy(weight = 82.5, reps = 6, completed = true)
                ExerciseType.BODYWEIGHT, ExerciseType.REPS_ONLY -> later.copy(reps = first.reps + 4, completed = true)
                ExerciseType.DURATION -> later.copy(duration = 120, completed = true)
                ExerciseType.DISTANCE -> later.copy(duration = 300, distance = 3.0, completed = true)
            })
            if (type == ExerciseType.DURATION) repository.deleteExercise(exerciseId)
        }
        repository.moveWorkoutExercise(entryIds.last(), -4)
        val source = dao.workoutDetail(sourceId)!!
        val repeatedId = repository.startWorkout("Fallback", repeatWorkoutId = sourceId)
        val repeated = dao.workoutDetail(repeatedId)!!
        assertEquals(5, repeated.exercises.size)
        assertEquals(source.sortedExercises.map { it.exercise.id }, repeated.sortedExercises.map { it.exercise.id })
        assertEquals(listOf(0, 1, 2, 3, 4), repeated.sortedExercises.map { it.entry.orderIndex })
        assertEquals(source.workout.notes, repeated.workout.notes)
        assertEquals(source.workout.muscleGroups, repeated.workout.muscleGroups)
        assertTrue(repeated.workout.startTime > source.workout.startTime)
        assertNull(repeated.workout.endTime)
        assertNull(repeated.workout.restEndTime)
        source.sortedExercises.zip(repeated.sortedExercises).forEach { (old, new) ->
            val copied = new.sets.single()
            val first = old.sortedSets.first()
            assertEquals(old.exercise, new.exercise)
            assertEquals(old.entry.notes, new.entry.notes)
            assertEquals(first.copy(id = copied.id, workoutExerciseId = new.entry.id, setNumber = 1, completed = false, setType = SetType.WORKING), copied)
            assertTrue(copied.id != first.id)
            assertTrue(new.entry.id != old.entry.id)
            assertFalse(copied.completed)
        }
        assertTrue(repeated.exercises.single { it.exercise.exerciseType == ExerciseType.DURATION }.exercise.archived)
        assertEquals(source, dao.workoutDetail(sourceId))
    }

    @Test fun `repeat with source exercise lacking sets creates blank set despite unrelated later history`() = runBlocking {
        val exerciseId = press()
        val dao = database.workoutDao()
        val sourceId = dao.insertWorkout(Workout(name = "Empty source exercise", startTime = 1000, endTime = 2000))
        repository.addExercise(sourceId, exerciseId)
        val sourceEntry = dao.workoutDetail(sourceId)!!.exercises.single()
        repository.deleteSet(sourceEntry.sets.single().id)
        val donorId = dao.insertWorkout(Workout(name = "Later history", startTime = 3000, endTime = 4000))
        repository.addExercise(donorId, exerciseId)
        val donorSet = dao.workoutDetail(donorId)!!.exercises.single().sets.single()
        repository.updateSet(donorSet.copy(weight = 95.0, reps = 7, completed = true))
        val source = dao.workoutDetail(sourceId)!!
        val donor = dao.workoutDetail(donorId)!!

        val repeatedId = repository.startWorkout("Fallback", repeatWorkoutId = sourceId)
        val repeated = dao.workoutDetail(repeatedId)!!
        val entry = repeated.exercises.single()
        val set = entry.sets.single()
        assertEquals(1, set.setNumber)
        assertEquals(0.0, set.weight, 0.000001)
        assertEquals(0, set.reps)
        assertEquals(0, set.duration)
        assertEquals(0.0, set.distance, 0.000001)
        assertFalse(set.completed)
        assertEquals(SetType.WORKING, set.setType)
        assertEquals(95.0, dao.previousSet(exerciseId, repeatedId, repeated.workout.startTime)!!.weight, 0.000001)
        repository.addSet(entry.entry.id)
        val next = dao.sets(entry.entry.id).last()
        assertEquals(set.copy(id = next.id, setNumber = 2), next)
        assertEquals(source, dao.workoutDetail(sourceId))
        assertEquals(donor, dao.workoutDetail(donorId))
    }

    @Test fun `foreign key cascades remove sets and workout rows and renumber remaining exercises`() = runBlocking {
        val pressId = press()
        val curlId = repository.saveExercise(Exercise(name = "Cable curl", primaryMuscleGroup = MuscleGroup.BICEPS))
        val workoutId = repository.startWorkout("Upper")
        repository.addExercise(workoutId, pressId)
        repository.addExercise(workoutId, curlId)
        val dao = database.workoutDao()
        val removed = dao.workoutDetail(workoutId)!!.sortedExercises.first()
        repository.removeWorkoutExercise(removed.entry.id)
        assertNull(dao.workoutSet(removed.sets.single().id))
        val remaining = dao.workoutDetail(workoutId)!!.exercises.single()
        assertEquals(0, remaining.entry.orderIndex)
        repository.deleteWorkout(workoutId)
        assertNull(dao.workout(workoutId))
        assertTrue(dao.workoutExercises(workoutId).isEmpty())
        assertNull(dao.workoutSet(remaining.sets.single().id))
    }

    @Test fun `referenced exercise is archived with history intact but unreferenced exercise is removed`() = runBlocking {
        val pressId = press()
        val unusedId = repository.saveExercise(Exercise(name = "Unused curl", primaryMuscleGroup = MuscleGroup.BICEPS))
        val workoutId = repository.startWorkout("Strength")
        repository.addExercise(workoutId, pressId)
        val first = database.workoutDao().workoutDetail(workoutId)!!.exercises.single().sets.single()
        repository.updateSet(first.copy(weight = 60.0, reps = 10, completed = true))
        repository.finishWorkout(workoutId)
        repository.deleteExercise(pressId)
        repository.deleteExercise(unusedId)
        assertTrue(repository.observeExercises().first().isEmpty())
        assertNull(database.workoutDao().exercise(unusedId))
        val saved = database.workoutDao().workoutDetail(workoutId)!!
        assertTrue(saved.exercises.single().exercise.archived)
        assertEquals(600.0, WorkoutCalculations.volume(saved), 0.001)
        val nextId = repository.startWorkout("Next")
        expectInvalid { repository.addExercise(nextId, pressId) }
        assertTrue(database.workoutDao().workoutExercises(nextId).isEmpty())
    }

    @Test fun `duplicate names and referenced type changes are rejected without corrupting SQLite rows`() = runBlocking {
        val exerciseId = press()
        expectInvalid { repository.saveExercise(Exercise(name = " test press ", primaryMuscleGroup = MuscleGroup.CHEST)) }
        var constrained = false
        try {
            database.workoutDao().insertExercise(Exercise(name = "TEST PRESS", primaryMuscleGroup = MuscleGroup.CHEST))
        } catch (_: SQLiteConstraintException) { constrained = true }
        assertTrue(constrained)
        assertEquals(1, repository.observeExercises().first().size)
        val workoutId = repository.startWorkout("Strength")
        repository.addExercise(workoutId, exerciseId)
        val exercise = database.workoutDao().exercise(exerciseId)!!
        expectInvalid { repository.saveExercise(exercise.copy(exerciseType = ExerciseType.DURATION)) }
        assertEquals(ExerciseType.WEIGHT_REPS, database.workoutDao().exercise(exerciseId)!!.exerciseType)
        repository.saveExercise(exercise.copy(name = "Paused press", notes = "Two second pause"))
        assertEquals("Paused press", database.workoutDao().workoutDetail(workoutId)!!.exercises.single().exercise.name)
    }

    @Test fun `invalid finish leaves active session and rest deadline unchanged`() = runBlocking {
        val workoutId = repository.startWorkout("Empty")
        expectInvalid { repository.finishWorkout(workoutId) }
        assertNull(database.workoutDao().workout(workoutId)!!.endTime)
        val exerciseId = press()
        repository.addExercise(workoutId, exerciseId)
        val dao = database.workoutDao()
        val set = dao.workoutDetail(workoutId)!!.exercises.single().sets.single()
        expectInvalid { repository.updateSet(set.copy(reps = 0, completed = true)) }
        assertFalse(dao.workoutSet(set.id)!!.completed)
        // Simulate a malformed old row to verify finish revalidates saved data transactionally.
        dao.updateSet(set.copy(reps = 0, completed = true))
        repository.setRestEndTime(workoutId, 123456789L)
        expectInvalid { repository.finishWorkout(workoutId) }
        assertNull(dao.workout(workoutId)!!.endTime)
        assertEquals(123456789L, dao.workout(workoutId)!!.restEndTime)
    }

    private suspend fun press(): Long = repository.saveExercise(Exercise(name = "Test Press", primaryMuscleGroup = MuscleGroup.CHEST))

    private suspend fun expectInvalid(operation: suspend () -> Unit) {
        try {
            operation()
            fail("Expected validation to reject the operation")
        } catch (_: IllegalArgumentException) { /* Expected repository validation. */ }
    }
}
