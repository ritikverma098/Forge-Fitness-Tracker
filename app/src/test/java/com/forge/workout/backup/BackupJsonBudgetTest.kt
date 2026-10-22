package com.forge.workout.backup

import android.app.Application
import com.forge.workout.nutrition.model.DiaryEntity
import com.forge.workout.nutrition.model.FoodSnapshot
import com.forge.workout.nutrition.model.FoodUnit
import com.forge.workout.nutrition.model.MealType
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class BackupJsonBudgetTest {
    @Test fun `nested compact budget exactly matches escaped Unicode scalar numbers nulls booleans and container delimiters`() {
        val budget = BackupJsonBudget()
        val scalar = budget.objectValue("हिन्दी 💪" to "Quotes \" slash / backslash \\\n\u0001\t", "null" to null,
            "false" to false, "true" to true, "maximum" to Long.MAX_VALUE, "minimum" to Long.MIN_VALUE,
            "wholeDouble" to 1.0, "fraction" to 1.25, "large" to 1e20, "small" to 1e-20, "negativeZero" to -0.0)
        val values = budget.arrayValue(listOf(scalar, "🍎", 17, JSONObject.NULL, budget.arrayValue(emptyList())))
        val result = budget.objectValue("values" to values, "empty" to budget.objectValue())
        assertEquals(result.toString().toByteArray(Charsets.UTF_8).size.toLong(), budget.reservedBytes)
    }

    @Test fun `exact byte limit succeeds and one byte less rejects before creating oversized local object`() {
        val text = "हिन्दी 💪 \"\u0001"
        val expected = JSONObject().put("text", text).put("value", JSONObject.NULL).toString().toByteArray(Charsets.UTF_8).size.toLong()
        val exact = BackupJsonBudget(expected)
        val result = exact.objectValue("text" to text, "value" to null)
        assertEquals(expected, exact.reservedBytes)
        assertEquals(expected, result.toString().toByteArray(Charsets.UTF_8).size.toLong())
        val small = BackupJsonBudget(expected - 1)
        rejectSize { small.objectValue("text" to text, "value" to null) }
        assertEquals(0L, small.reservedBytes)
    }

    @Test fun `snapshot text is reserved before parsing and canonical snapshot bytes share the nutrition and core budget`() {
        val small = BackupJsonBudget(2)
        rejectSize { small.snapshotObject("not valid JSON but larger than the remaining budget") }
        assertEquals(0L, small.reservedBytes)
        val canonical = BackupJsonBudget()
        val parsed = canonical.snapshotObject(" { \"unicode\": \"\\u0939\", \"number\": 1e0, \"zero\": -0 } ")
        val canonicalRoot = canonical.objectValue("snapshot" to parsed)
        assertEquals(canonicalRoot.toString().toByteArray(Charsets.UTF_8).size.toLong(), canonical.reservedBytes)
        val shared = BackupJsonBudget()
        val nutrition = NutritionBackup(diary = listOf(DiaryEntity(id = 1, day = "2026-10-14", meal = MealType.LUNCH,
            snapshot = FoodSnapshot(name = "Saved food 🍎"), quantity = 100.0, unit = FoodUnit.GRAM, factor = 1.0)))
        val section = NutritionBackupCodec.encode(nutrition, shared)
        val complete = shared.objectValue("format" to BackupCodec.FORMAT, "nutrition" to section, "metadata" to shared.arrayValue(emptyList()))
        assertEquals(complete.toString().toByteArray(Charsets.UTF_8).size.toLong(), shared.reservedBytes)
    }

    @Test fun `dense note rows exhaust a small budget before later row builders or full array allocation`() {
        val limit = 4096L
        val budget = BackupJsonBudget(limit)
        var constructed = 0
        val notes = "\u0001".repeat(100) + "💪".repeat(100)
        rejectSize {
            val rows = (1..1000).map { id ->
                constructed++
                budget.objectValue("id" to id, "notes" to notes)
            }
            budget.arrayValue(rows)
        }
        assertTrue(constructed > 1)
        assertTrue(constructed < 1000)
        assertTrue(budget.reservedBytes in 1L..limit)
    }

    private fun rejectSize(action: () -> Unit) {
        try { action(); fail("Expected cumulative backup size rejection") }
        catch (failure: IllegalArgumentException) { assertTrue(failure.message.orEmpty().contains("size limit")) }
    }
}
