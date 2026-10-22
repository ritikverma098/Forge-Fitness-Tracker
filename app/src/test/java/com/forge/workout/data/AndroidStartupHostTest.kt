package com.forge.workout.data

import android.content.Context
import android.content.ContextWrapper
import android.os.Bundle
import android.os.Looper
import android.view.ViewGroup
import androidx.room.Room
import com.forge.workout.ForgeApplication
import com.forge.workout.MainActivity
import com.forge.workout.data.database.ForgeDatabase
import com.forge.workout.model.Exercise
import com.forge.workout.model.MuscleGroup
import com.forge.workout.preferences.PreferencesRepository
import com.forge.workout.repository.RoomWorkoutRepository
import com.forge.workout.repository.WorkoutRepository
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = StartupApplication::class)
class AndroidStartupHostTest {
    @After fun closeDatabase() {
        (RuntimeEnvironment.getApplication() as StartupApplication).database.close()
    }

    @Test fun `activity installs Compose content and recovers persisted active workout after saved state recreation`() = runBlocking {
        val application = RuntimeEnvironment.getApplication() as StartupApplication
        val repository = application.repository
        val exerciseId = repository.saveExercise(Exercise(name = "Startup press", primaryMuscleGroup = MuscleGroup.CHEST))
        val workoutId = repository.startWorkout("Recovered session")
        repository.updateMuscleGroups(workoutId, setOf(MuscleGroup.BICEPS, MuscleGroup.TRICEPS))
        repository.addExercise(workoutId, exerciseId)
        val original = application.database.workoutDao().workoutDetail(workoutId)!!.exercises.single().sets.single()
        repository.updateSet(original.copy(weight = 72.5, reps = 9, completed = true))

        val first = Robolectric.buildActivity(MainActivity::class.java).setup().visible()
        shadowOf(Looper.getMainLooper()).idle()
        assertFalse(first.get().isFinishing)
        assertTrue(first.get().findViewById<ViewGroup>(android.R.id.content).childCount > 0)
        val savedState = Bundle()
        first.pause().saveInstanceState(savedState).stop().destroy()

        val restored = Robolectric.buildActivity(MainActivity::class.java)
            .create(savedState).start().resume().visible()
        try {
            shadowOf(Looper.getMainLooper()).idle()
            assertFalse(restored.get().isFinishing)
            assertTrue(restored.get().findViewById<ViewGroup>(android.R.id.content).childCount > 0)
            val recovered = application.database.workoutDao().workoutDetail(workoutId)!!
            assertNull(recovered.workout.endTime)
            assertEquals(listOf(MuscleGroup.BICEPS, MuscleGroup.TRICEPS), recovered.workout.muscleGroups)
            assertEquals(72.5, recovered.exercises.single().sets.single().weight, 0.001)
            assertEquals(9, recovered.exercises.single().sets.single().reps)
        } finally {
            restored.pause().stop().destroy()
        }
    }
}

class StartupApplication : ForgeApplication() {
    public override val database: ForgeDatabase by lazy {
        Room.inMemoryDatabaseBuilder(this, ForgeDatabase::class.java).build()
    }
    override val repository: WorkoutRepository by lazy { RoomWorkoutRepository(database) }
    override val preferences: PreferencesRepository by lazy {
        PreferencesRepository(object : ContextWrapper(this) {
            override fun getApplicationContext(): Context = this
            override fun getFilesDir(): File = File(baseContext.filesDir, "host-startup-preferences").apply { mkdirs() }
        })
    }
}
