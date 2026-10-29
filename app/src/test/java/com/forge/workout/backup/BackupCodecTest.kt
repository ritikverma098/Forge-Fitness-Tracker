package com.forge.workout.backup

import android.app.Application
import com.forge.workout.data.database.AppMetadata
import com.forge.workout.model.MuscleGroup
import com.forge.workout.model.SetType
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class BackupCodecTest {
    @Test fun `complete JSON round trip preserves all tracking and set types unicode relationships and preferences`() {
        val backup = completeBackup()
        assertEquals(SetType.entries.toSet(), backup.sets.map { it.setType }.toSet())
        val encoded = BackupCodec.encode(backup)
        val json = JSONObject(encoded)
        assertEquals(3, json.getInt("formatVersion"))
        assertEquals(4, json.getInt("databaseVersion"))
        for (index in backup.sets.indices) {
            assertEquals(backup.sets[index].setType.name, json.getJSONArray("sets").getJSONObject(index).getString("setType"))
        }
        assertEquals(backup, BackupCodec.decode(encoded))
    }

    @Test fun `legacy format1 database2 backup defaults all sets to working and exports current format`() {
        val original = completeBackup()
        val json = JSONObject(BackupCodec.encode(original)).put("formatVersion", 1).put("databaseVersion", 2)
        json.remove("nutrition")
        val sets = json.getJSONArray("sets")
        for (index in 0 until sets.length()) sets.getJSONObject(index).remove("setType")
        val restored = BackupCodec.decode(json.toString())
        assertEquals(original.copy(sets = original.sets.map { it.copy(setType = SetType.WORKING) }, nutrition = null), restored)
        val exported = JSONObject(BackupCodec.encode(restored))
        assertEquals(3, exported.getInt("formatVersion"))
        assertEquals(4, exported.getInt("databaseVersion"))
        assertEquals(restored, BackupCodec.decode(exported.toString()))
    }

    @Test fun `only matching supported format and database version pairs are accepted`() {
        for ((format, database) in listOf(1 to 3, 2 to 2, 1 to 1, 2 to 1, 3 to 3, 1 to 4, 2 to 4, 4 to 4)) {
            val json = JSONObject(BackupCodec.encode(completeBackup())).put("formatVersion", format).put("databaseVersion", database)
            expectIssue(BackupIssue.UNSUPPORTED_VERSION) { BackupCodec.decode(json.toString()) }
        }
    }

    @Test fun `current sets require a valid strict enum and legacy shape excludes set type`() {
        val source = BackupCodec.encode(completeBackup())
        for (invalid in listOf("UNKNOWN", "warm_up", 0, JSONObject.NULL)) {
            val json = JSONObject(source)
            json.getJSONArray("sets").getJSONObject(0).put("setType", invalid)
            expectIssue { BackupCodec.decode(json.toString()) }
        }
        val missing = JSONObject(source)
        missing.getJSONArray("sets").getJSONObject(0).remove("setType")
        expectIssue { BackupCodec.decode(missing.toString()) }
        val legacyWithNewFields = JSONObject(source).put("formatVersion", 1).put("databaseVersion", 2)
        legacyWithNewFields.remove("nutrition")
        expectIssue { BackupCodec.decode(legacyWithNewFields.toString()) }
    }

    @Test fun `legacy format2 database3 preserves set types and explicitly has workout only restore scope`() {
        val original = completeBackup()
        val legacy = JSONObject(BackupCodec.encode(original)).put("formatVersion", 2).put("databaseVersion", 3)
        legacy.remove("nutrition")
        assertEquals(original.copy(nutrition = null), BackupCodec.decode(legacy.toString()))
        legacy.put("nutrition", JSONObject.NULL)
        expectIssue { BackupCodec.decode(legacy.toString()) }
        val current = JSONObject(BackupCodec.encode(original))
        current.remove("nutrition")
        expectIssue { BackupCodec.decode(current.toString()) }
    }

    @Test fun `empty library and default preferences are valid`() {
        val backup = ForgeBackup(createdAt = 0)
        assertEquals(backup, BackupCodec.decode(BackupCodec.encode(backup)))
    }

    @Test fun `unsupported format and database versions are identified separately`() {
        for (field in listOf("formatVersion", "databaseVersion")) {
            val json = JSONObject(BackupCodec.encode(completeBackup())).put(field, 999)
            expectIssue(BackupIssue.UNSUPPORTED_VERSION) { BackupCodec.decode(json.toString()) }
        }
    }

    @Test fun `missing structure unknown format and strict field types are rejected`() {
        val source = BackupCodec.encode(completeBackup())
        val mutations: List<(JSONObject) -> Unit> = listOf(
            { it.remove("sets") },
            { it.put("format", "some-other-app") },
            { it.put("createdAt", "10000") },
            { it.put("formatVersion", 1.5) },
            { it.put("workouts", JSONObject()) },
            { it.getJSONArray("exercises").put(0, "not an object") },
            { it.getJSONArray("exercises").getJSONObject(0).put("isCustom", "false") },
            { it.getJSONArray("workouts").getJSONObject(0).put("endTime", "2000") },
            { it.getJSONArray("sets").getJSONObject(0).put("reps", 1.5) },
            { it.getJSONArray("sets").getJSONObject(0).put("weight", "82.5") },
            { it.getJSONArray("sets").getJSONObject(0).put("weight", -1) },
            { it.getJSONArray("exercises").getJSONObject(1).put("id", 101) },
            { it.getJSONObject("preferences").put("confirmDeletion", 1) },
            { it.getJSONObject("preferences").put("themeMode", "FUTURE_THEME") },
            { it.getJSONArray("exercises").getJSONObject(0).put("exerciseType", "UNKNOWN") },
            { it.getJSONArray("workouts").getJSONObject(0).getJSONArray("muscleGroups").put(0, "UNKNOWN") },
        )
        mutations.forEach { mutate ->
            val json = JSONObject(source)
            mutate(json)
            expectIssue { BackupCodec.decode(json.toString()) }
        }
    }

    @Test fun `truncated trailing duplicated and permissive non JSON syntax are rejected`() {
        val source = BackupCodec.encode(completeBackup())
        val invalid = listOf(
            source.dropLast(1), source + " garbage", source + " {}", "{\"a\":1,\"a\":2}",
            "{\"id\":1,\"\\u0069d\":2}", "{'format':'forge-workout-backup'}", "{format:1}",
            "{\"a\":01}", "{\"a\":+1}", "{\"a\":NaN}", "{\"a\":true,}", "[1,]",
            "{\"a\":\"bad\nstring\"}", "{\"a\":\"\\q\"}", "{\"a\":\"\\uZZZZ\"}",
        )
        invalid.forEach { expectIssue { BackupCodec.decode(it) } }
        assertEquals(completeBackup(), BackupCodec.decode(" \n\t$source\r "))
    }

    @Test fun `excessive nesting is rejected before recursive JSON parsing`() {
        val deep = "[".repeat(10000) + "0" + "]".repeat(10000)
        expectIssue { BackupCodec.decode(deep) }
        val nested = "{\"extra\":" + "[".repeat(BackupCodec.MAX_DEPTH) + "0" + "]".repeat(BackupCodec.MAX_DEPTH) + "}"
        expectIssue { BackupCodec.decode(nested) }
    }

    @Test fun `decode checks UTF8 byte size and encode cannot create oversized backup`() {
        expectIssue { BackupCodec.decode(" ".repeat(BackupCodec.MAX_BYTES + 1)) }
        expectIssue { BackupCodec.decode("💪".repeat(BackupCodec.MAX_BYTES / 4 + 1)) }
        val note = "\u0001".repeat(5000)
        val source = completeBackup().exercises.first()
        val exercises = List(1200) { index -> source.copy(id = index + 1L, name = "Exercise $index", notes = note) }
        expectIssue { BackupCodec.encode(ForgeBackup(exercises = exercises)) }
    }

    @Test fun `orphan references duplicate IDs names and malformed ordering are rejected`() {
        val base = completeBackup()
        // SQLite NOCASE folds ASCII; non-ASCII case variants can be distinct stored names.
        val duplicateName = base.exercises[0].name.map { if (it in 'A'..'Z') it + ('a' - 'A') else it }.joinToString("")
        val invalid = listOf(
            base.copy(exercises = base.exercises + base.exercises.first()),
            base.copy(workouts = base.workouts + base.workouts.first()),
            base.copy(workoutExercises = base.workoutExercises + base.workoutExercises.first()),
            base.copy(sets = base.sets + base.sets.first()),
            base.copy(routines = base.routines + base.routines.first()),
            base.copy(routineExercises = base.routineExercises + base.routineExercises.first()),
            base.copy(exercises = base.exercises.mapIndexed { index, value -> if (index == 0) value.copy(id = 0) else value }),
            base.copy(exercises = base.exercises.mapIndexed { index, value -> if (index == 1) value.copy(name = duplicateName) else value }),
            base.copy(workoutExercises = base.workoutExercises.mapIndexed { index, value -> if (index == 0) value.copy(workoutId = 999) else value }),
            base.copy(workoutExercises = base.workoutExercises.mapIndexed { index, value -> if (index == 0) value.copy(exerciseId = 999) else value }),
            base.copy(routineExercises = base.routineExercises.mapIndexed { index, value -> if (index == 0) value.copy(routineId = 999) else value }),
            base.copy(sets = base.sets.mapIndexed { index, value -> if (index == 0) value.copy(workoutExerciseId = 999) else value }),
            base.copy(workoutExercises = base.workoutExercises.mapIndexed { index, value -> if (index == 0) value.copy(orderIndex = 1) else value }),
            base.copy(routineExercises = base.routineExercises.mapIndexed { index, value -> if (index == 0) value.copy(orderIndex = -1) else value }),
            base.copy(sets = base.sets.mapIndexed { index, value -> if (index == 0) value.copy(setNumber = 2) else value }),
            base.copy(sets = base.sets + base.sets.first().copy(id = 999)),
        )
        invalid.forEach { expectIssue { BackupCodec.encode(it) } }
        val orphan = JSONObject(BackupCodec.encode(base))
        orphan.getJSONArray("sets").getJSONObject(0).put("workoutExerciseId", 999)
        expectIssue { BackupCodec.decode(orphan.toString()) }
    }

    @Test fun `negative nonfinite and invalid completed values are rejected across tracking types`() {
        val base = completeBackup()
        val malformed = listOf(
            base.sets[0].copy(weight = -1.0), base.sets[0].copy(weight = Double.NaN),
            base.sets[0].copy(weight = Double.POSITIVE_INFINITY), base.sets[0].copy(reps = -1),
            base.sets[0].copy(reps = 0), base.sets[1].copy(reps = 0), base.sets[2].copy(reps = 0),
            base.sets[3].copy(duration = -1), base.sets[3].copy(duration = 0),
            base.sets[4].copy(distance = -1.0), base.sets[4].copy(distance = Double.NEGATIVE_INFINITY),
            base.sets[4].copy(distance = 0.0),
        )
        malformed.forEach { bad ->
            expectIssue { BackupCodec.encode(base.copy(sets = base.sets.map { if (it.id == bad.id) bad else it })) }
        }
        val nonfinite = BackupCodec.encode(base).replace("82.5", "1e9999")
        expectIssue { BackupCodec.decode(nonfinite) }
    }

    @Test fun `invalid preferences metadata groups and multiple active workouts are rejected`() {
        val base = completeBackup()
        val invalid = listOf(
            base.copy(preferences = base.preferences.copy(restSeconds = 45)),
            base.copy(metadata = base.metadata + base.metadata.first()),
            base.copy(metadata = listOf(AppMetadata("default_exercises_v1", "wrong"))),
            base.copy(workouts = base.workouts.map { it.copy(endTime = null) }),
            base.copy(workouts = base.workouts.mapIndexed { index, value -> if (index == 0) value.copy(endTime = 999) else value }),
            base.copy(workouts = base.workouts.mapIndexed { index, value -> if (index == 0) value.copy(muscleGroups = listOf(MuscleGroup.CHEST, MuscleGroup.CHEST)) else value }),
        )
        invalid.forEach { expectIssue { BackupCodec.encode(it) } }
    }

    private fun expectIssue(issue: BackupIssue = BackupIssue.INVALID_DATA, action: () -> Unit) {
        try { action(); fail("Expected backup to be rejected") }
        catch (error: BackupValidationException) { assertEquals(issue, error.issue) }
    }
}
