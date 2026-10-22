package com.forge.workout.data

import android.app.Application
import androidx.room.Room
import com.forge.workout.backup.ForgeBackup
import com.forge.workout.backup.RoomBackupRepository
import com.forge.workout.data.database.ForgeDatabase
import com.forge.workout.model.Exercise
import com.forge.workout.model.MuscleGroup
import com.forge.workout.model.RestTimer
import com.forge.workout.model.Workout
import com.forge.workout.repository.RoomWorkoutRepository
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class RestTimerRepositoryHostTest {
    private lateinit var database: ForgeDatabase
    private lateinit var repository: RoomWorkoutRepository

    @Before fun setUp() {
        database = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), ForgeDatabase::class.java).build()
        repository = RoomWorkoutRepository(database)
    }

    @After fun tearDown() { database.close() }

    @Test fun `claim succeeds only at or after exact deadline and can never succeed twice`() = runBlocking {
        val id = active()
        repository.setRestEndTime(id, 5_000)
        assertEquals(RestTimer(id, 5_000, "Strength"), repository.restTimer())
        assertFalse(repository.consumeRestTimer(id + 1, 5_000, 5_000))
        assertFalse(repository.consumeRestTimer(id, 5_000, 4_999))
        assertEquals(5_000L, database.workoutDao().workout(id)!!.restEndTime)
        assertTrue(repository.consumeRestTimer(id, 5_000, 5_000))
        assertFalse(repository.consumeRestTimer(id, 5_000, 10_000))
        assertNull(repository.restTimer())
        assertNull(database.workoutDao().workout(id)!!.restEndTime)
    }

    @Test fun `competing completions atomically claim a persisted deadline exactly once`() = runBlocking {
        val id = active()
        repository.setRestEndTime(id, 5_000)
        val results = (1..32).map { async(Dispatchers.Default) { repository.consumeRestTimer(id, 5_000, 6_000) } }.awaitAll()
        assertEquals(1, results.count { it })
        assertEquals(31, results.count { !it })
        assertNull(repository.restTimer())
    }

    @Test fun `old callbacks cannot consume an extended shortened or restarted timer`() = runBlocking {
        val id = active()
        repository.setRestEndTime(id, 5_000)
        repository.setRestEndTime(id, 20_000)
        assertFalse(repository.consumeRestTimer(id, 5_000, 21_000))
        assertEquals(20_000L, repository.restTimer()!!.deadline)
        assertFalse(repository.consumeRestTimer(id, 20_000, 19_999))

        repository.setRestEndTime(id, 7_000)
        assertFalse(repository.consumeRestTimer(id, 20_000, 21_000))
        assertEquals(7_000L, repository.restTimer()!!.deadline)
        repository.setRestEndTime(id, 25_000)
        assertFalse(repository.consumeRestTimer(id, 7_000, 30_000))
        assertTrue(repository.consumeRestTimer(id, 25_000, 30_000))
    }

    @Test fun `skipped and deleted workouts cannot be claimed by a pending callback`() = runBlocking {
        val id = active()
        repository.setRestEndTime(id, 5_000)
        repository.setRestEndTime(id, null)
        assertFalse(repository.consumeRestTimer(id, 5_000, 6_000))
        assertNull(repository.restTimer())
        repository.setRestEndTime(id, 10_000)
        repository.deleteWorkout(id)
        assertFalse(repository.consumeRestTimer(id, 10_000, 11_000))
        assertNull(repository.restTimer())
        val next = active("Next")
        assertTrue(next > id)
        repository.setRestEndTime(next, 10_000)
        assertFalse(repository.consumeRestTimer(id, 10_000, 11_000))
        assertEquals(RestTimer(next, 10_000, "Next"), repository.restTimer())
    }

    @Test fun `finished workouts are excluded even when an old row retains a deadline`() = runBlocking {
        val id = active()
        repository.setRestEndTime(id, 5_000)
        val dao = database.workoutDao()
        dao.updateWorkout(dao.workout(id)!!.copy(endTime = 2_000))
        assertNull(repository.restTimer())
        assertFalse(repository.consumeRestTimer(id, 5_000, 6_000))
        assertEquals(5_000L, dao.workout(id)!!.restEndTime)
    }

    @Test fun `normal finish clears timer and prevents later completion`() = runBlocking {
        val id = active()
        val exercise = repository.saveExercise(Exercise(name = "Press", primaryMuscleGroup = MuscleGroup.CHEST))
        repository.addExercise(id, exercise)
        val set = database.workoutDao().workoutDetail(id)!!.exercises.single().sets.single()
        repository.updateSet(set.copy(weight = 60.0, reps = 10, completed = true))
        repository.setRestEndTime(id, 5_000)
        repository.finishWorkout(id)
        assertNull(repository.restTimer())
        assertFalse(repository.consumeRestTimer(id, 5_000, Long.MAX_VALUE))
        assertNull(database.workoutDao().workout(id)!!.restEndTime)
    }

    @Test fun `backup replacement with reused workout ID exposes the new timer and rejects old deadline`() = runBlocking {
        val id = active("Original")
        repository.setRestEndTime(id, 5_000)
        val imported = ForgeBackup(workouts = listOf(Workout(id = id, name = "Imported", startTime = 2_000, restEndTime = 15_000)))
        RoomBackupRepository(database).restore(imported)
        assertEquals(RestTimer(id, 15_000, "Imported"), repository.restTimer())
        assertFalse(repository.consumeRestTimer(id, 5_000, 20_000))
        assertEquals(15_000L, database.workoutDao().workout(id)!!.restEndTime)
        assertTrue(repository.consumeRestTimer(id, 15_000, 20_000))
        assertNull(repository.restTimer())
    }

    @Test fun `projection flow refreshes deadline name consumption and backup replacement without re-subscribing`() = runBlocking {
        val emissions = Channel<RestTimer?>(Channel.UNLIMITED)
        val collector = launch(start = CoroutineStart.UNDISPATCHED) {
            repository.observeRestTimer().distinctUntilChanged().collect { emissions.send(it) }
        }
        suspend fun expect(expected: RestTimer?) = withTimeout(10_000) {
            assertEquals(expected, emissions.receive())
            assertEquals(expected, repository.restTimer())
        }
        try {
            expect(null)
            val id = active()
            repository.setRestEndTime(id, 5_000)
            expect(RestTimer(id, 5_000, "Strength"))
            repository.updateWorkout(id, "Renamed", "Keep notes")
            expect(RestTimer(id, 5_000, "Renamed"))
            repository.setRestEndTime(id, 10_000)
            expect(RestTimer(id, 10_000, "Renamed"))
            assertTrue(repository.consumeRestTimer(id, 10_000, 10_000))
            expect(null)
            RoomBackupRepository(database).restore(ForgeBackup(workouts = listOf(
                Workout(id = id, name = "Restored", startTime = 2_000, restEndTime = 20_000),
            )))
            expect(RestTimer(id, 20_000, "Restored"))
            repository.deleteWorkout(id)
            expect(null)
        } finally {
            collector.cancel()
            collector.join()
            emissions.close()
        }
    }

    private suspend fun active(name: String = "Strength"): Long = database.workoutDao().insertWorkout(
        Workout(name = name, startTime = 1_000),
    )
}
