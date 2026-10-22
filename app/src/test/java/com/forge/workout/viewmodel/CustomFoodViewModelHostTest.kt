package com.forge.workout.viewmodel

import android.app.Application
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import androidx.room.Room
import com.forge.workout.data.database.ForgeDatabase
import com.forge.workout.nutrition.model.*
import com.forge.workout.nutrition.repository.NutritionRepository
import com.forge.workout.nutrition.repository.RoomNutritionRepository
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class CustomFoodViewModelHostTest {
    private val dispatcher = StandardTestDispatcher()
    private lateinit var database: ForgeDatabase
    private lateinit var repository: RoomNutritionRepository
    private lateinit var store: ViewModelStore

    @Before fun setUp() {
        Dispatchers.setMain(dispatcher)
        database = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), ForgeDatabase::class.java).build()
        repository = RoomNutritionRepository(database)
        store = ViewModelStore()
    }

    @After fun tearDown() {
        store.clear()
        database.close()
        Dispatchers.resetMain()
    }

    @Test fun `new draft ID all string inputs optional nutrients and explicit removals survive recreation`() = runTest(dispatcher) {
        val handle = SavedStateHandle()
        val editor = keepEditor(handle)
        observe(editor)
        val initial = editor.state.first { !it.loading }.draft
        assertTrue(initial.id.startsWith("custom:"))
        val requested = initial.copy(name = "  Lentil drink  ", brand = "Home", notes = "Fresh batch\nUse chilled",
            baseAmount = "250,5", baseUnit = FoodUnit.MILLILITER, gramsPerBase = "200",
            nutrients = initial.nutrients.map { if (it.code == NutrientCodes.PROTEIN) it.copy(value = "3,25") else it } +
                CustomNutrientDraft("laboratory:iron", "Measured iron", "mg", "2,5"),
            removedNutrientCodes = listOf("usda:291"))
        editor.updateDraft(requested)
        val draft = editor.state.first { it.draft.name == requested.name }.draft
        val recreated = keepEditor(copyHandle(handle), key = "recreated")
        observe(recreated)
        assertEquals(draft, recreated.state.first { !it.loading }.draft)
        val validated = recreated.validatedFood()!!
        assertEquals(initial.id, validated.food.id)
        assertEquals("Lentil drink", validated.food.name)
        assertEquals(250.5, validated.food.baseAmount, 0.0)
        assertEquals(250.5, validated.food.millilitersPerBase!!, 0.0)
        assertEquals(200.0, validated.food.gramsPerBase!!, 0.0)
        assertEquals(3.25, validated.nutrients.single { it.code == NutrientCodes.PROTEIN }.value!!, 0.0)
        assertNull(validated.nutrients.single { it.code == NutrientCodes.ENERGY }.value)
        assertEquals(2.5, validated.nutrients.single { it.code == "laboratory:iron" }.value!!, 0.0)
        assertTrue(repository.searchFoods().isEmpty())
    }

    @Test fun `editor reads one custom identity preserves additional fields and reports validation without writing`() = runTest(dispatcher) {
        repository.upsertFood(customFood("legacy-custom"))
        val before = repository.food("legacy-custom")!!
        val editor = keepEditor(id = "legacy-custom")
        observe(editor)
        val loaded = editor.state.first { !it.loading }.draft
        assertEquals("Measured iron", loaded.nutrients.single { it.code == "laboratory:iron" }.name)
        editor.updateDraft(loaded.copy(name = " ", baseAmount = "-1", nutrients = loaded.nutrients.map {
            if (it.code == NutrientCodes.ENERGY) it.copy(value = "NaN") else it
        }))
        assertNull(editor.validatedFood())
        val invalid = editor.state.first { it.invalidFields.isNotEmpty() }
        assertTrue(invalid.invalidFields.containsAll(listOf("name", "baseAmount", "nutrient:energy")))
        editor.updateDraft(loaded.copy(notes = "Changed notes", brand = "Updated brand"))
        val valid = editor.validatedFood()!!
        assertEquals(before.food.rowId, valid.food.rowId)
        assertEquals(before.food.id, valid.food.id)
        assertEquals(before.food.aliases, valid.food.aliases)
        assertEquals(before.food.source, valid.food.source)
        assertEquals(before.food.sourceId, valid.food.sourceId)
        assertEquals(before.food.sourceUrl, valid.food.sourceUrl)
        assertEquals(before.food.tags, valid.food.tags)
        assertEquals(before.portions, valid.portions)
        assertEquals(before.nutrients.single { it.code == "laboratory:iron" }, valid.nutrients.single { it.code == "laboratory:iron" })
        assertNull(valid.nutrients.single { it.code == "usda:291" }.value)
        assertEquals(before, repository.food("legacy-custom"))
    }

    @Test fun `custom save edit and deletion retain stable row IDs and frozen diary nutrition`() = runTest(dispatcher) {
        val draft = CustomFoodDraft("custom:history", name = "Homemade bowl", nutrients = CustomFoodDraft.coreNutrients().map {
            if (it.code == NutrientCodes.ENERGY) it.copy(value = "180") else it
        })
        val writer = keepWriter()
        writer.saveFood("create", CustomFoodFactory.validate(draft).food!!)
        writer.flush()
        assertEquals(FoodMutationResult("create", draft.id), writer.saveResult.value?.copy(operationId = 0))
        val original = repository.food(draft.id)!!
        val snapshot = original.snapshot()
        val diaryId = repository.saveDiary(DiaryEntity(day = "2026-10-12", meal = MealType.LUNCH, foodId = draft.id,
            snapshot = snapshot, quantity = 150.0, unit = FoodUnit.GRAM, factor = 1.5, loggedAt = 1))
        val diary = repository.diaryEntry(diaryId)!!
        assertEquals(270.0, diary.kcal!!, 0.0)
        assertNull(diary.protein)
        val editedDraft = CustomFoodFactory.fromFood(original).copy(name = "Updated bowl", nutrients =
            CustomFoodFactory.fromFood(original).nutrients.map { if (it.code == NutrientCodes.ENERGY) it.copy(value = "220") else it })
        writer.saveFood("edit", CustomFoodFactory.validate(editedDraft, original).food!!)
        writer.flush()
        val edited = repository.food(draft.id)!!
        assertEquals(original.food.rowId, edited.food.rowId)
        assertEquals("Updated bowl", edited.food.name)
        assertEquals(220.0, edited.nutrients.single { it.code == NutrientCodes.ENERGY }.value!!, 0.0)
        assertEquals(diary, repository.diaryEntry(diaryId))
        writer.deleteFood("delete", draft.id)
        writer.flush()
        assertEquals(FoodMutationResult("delete", draft.id, deleted = true), writer.saveResult.value?.copy(operationId = 0))
        assertNull(repository.food(draft.id))
        assertEquals(diary, repository.diaryEntry(diaryId))
        assertEquals(snapshot, repository.diaryEntry(diaryId)!!.snapshot)
    }

    @Test fun `reference recipe and missing targets are protected by transactional repository and editor guards`() = runTest(dispatcher) {
        for (type in listOf(FoodSourceType.REFERENCE, FoodSourceType.RECIPE)) {
            val id = "protected-${type.name}"
            repository.upsertFood(customFood(id).copy(food = customFood(id).food.copy(sourceType = type)))
            val before = repository.food(id)!!
            val editor = keepEditor(id = id, key = "editor-$id")
            observe(editor)
            assertTrue(editor.state.first { !it.loading }.error)
            assertNull(editor.validatedFood())
            try { repository.saveCustomFood(before.copy(food = before.food.copy(name = "Overwrite", sourceType = FoodSourceType.CUSTOM)))
                throw AssertionError("Protected food must reject forged custom edit")
            } catch (_: IllegalArgumentException) { }
            try { repository.deleteCustomFood(id); throw AssertionError("Protected food must reject deletion") }
            catch (_: IllegalArgumentException) { }
            assertEquals(before, repository.food(id))
            val writer = keepWriter(key = "writer-$id")
            writer.saveFood("blocked-save", before.copy(food = before.food.copy(sourceType = FoodSourceType.CUSTOM)))
            writer.flush()
            assertNotNull(writer.saveResult.value!!.error)
            writer.deleteFood("blocked-delete", id)
            writer.flush()
            assertNotNull(writer.saveResult.value!!.error)
            assertEquals(before, repository.food(id))
        }
        val missing = keepEditor(id = "missing", key = "missing")
        observe(missing)
        assertTrue(missing.state.first { !it.loading }.error)
        assertNull(missing.validatedFood())
        val foreign = customFood("usda:fdc:987654")
        try { repository.saveCustomFood(foreign); throw AssertionError("A new custom food cannot use a foreign ID namespace") }
        catch (_: IllegalArgumentException) { }
        assertNull(repository.food(foreign.food.id))
    }

    @Test fun `rapid saves freeze caller lists and flush waits for the activity writer through editor recreation`() = runTest(dispatcher) {
        val held = HeldRepository(repository)
        val writer = keepWriter(held)
        val handle = SavedStateHandle()
        val editor = keepEditor(handle)
        observe(editor)
        val draft = editor.state.first { !it.loading }.draft.copy(name = "Save after rotation")
        editor.updateDraft(draft)
        val detail = editor.validatedFood()!!
        val mutableNutrients = detail.nutrients.toMutableList()
        writer.saveFood("request-one", detail.copy(nutrients = mutableNutrients))
        mutableNutrients.clear()
        writer.saveFood("request-two", detail.copy(food = detail.food.copy(name = "Duplicate tap")))
        held.started.await()
        val barrier = async { writer.flush() }
        runCurrent()
        assertFalse(barrier.isCompleted)
        assertTrue(writer.busy.value)
        assertNull(writer.saveResult.value)
        val recreated = keepEditor(copyHandle(handle)) // Replaces and clears only the old destination model.
        observe(recreated)
        assertEquals(draft.id, recreated.state.first { !it.loading }.draft.id)
        held.release.complete(Unit)
        barrier.await()
        assertFalse(writer.busy.value)
        assertEquals(1, held.saves)
        assertEquals(FoodMutationResult("request-one", draft.id), writer.saveResult.value?.copy(operationId = 0))
        val persisted = repository.food(draft.id)!!
        assertEquals("Save after rotation", persisted.food.name)
        assertEquals(4, persisted.nutrients.size)
        assertTrue(persisted.nutrients.all { it.value == null })
        writer.acknowledge("another-request")
        assertNotNull(writer.saveResult.value)
        writer.acknowledge("request-one")
        assertNull(writer.saveResult.value)
    }

    @Test fun `failed requests retain useful matched results clear busy and recover without stale restore feedback`() = runTest(dispatcher) {
        var fail = true
        val failing = object : NutritionRepository by repository {
            override suspend fun saveCustomFood(detail: FoodDetail): String {
                if (fail) throw IOException("Injected storage failure")
                return repository.saveCustomFood(detail)
            }
        }
        val writer = keepWriter(failing)
        val detail = customFood("custom:retry")
        writer.saveFood("failed", detail)
        writer.flush()
        assertFalse(writer.busy.value)
        assertEquals("failed", writer.saveResult.value!!.requestId)
        assertEquals("Error: Injected storage failure", writer.saveResult.value!!.error)
        assertEquals(writer.saveResult.value!!.error, writer.error.value)
        assertNull(repository.food(detail.food.id))
        writer.acknowledge("wrong")
        assertNotNull(writer.saveResult.value)
        writer.acknowledge("failed")
        assertNull(writer.saveResult.value)
        assertNull(writer.error.value)
        fail = false
        writer.saveFood("success", detail)
        writer.flush()
        assertEquals(FoodMutationResult("success", detail.food.id), writer.saveResult.value?.copy(operationId = 0))
        assertNotNull(repository.food(detail.food.id))
        writer.resetAfterRestore()
        assertNull(writer.saveResult.value)
        assertNull(writer.error.value)
    }

    @Test fun `favorite retry feedback has fresh operation identity and success clears older errors`() = runTest(dispatcher) {
        val longName = "A".repeat(900)
        repository.upsertFood(FoodDetail(FoodEntity(id = "reference", name = longName, sourceType = FoodSourceType.REFERENCE)))
        var failFavorite = false
        val controlled = object : NutritionRepository by repository {
            override suspend fun saveFavorite(favorite: FavoriteEntity) {
                if (failFavorite) throw IOException("Favorite storage failure")
                repository.saveFavorite(favorite)
            }
        }
        val writer = keepWriter(controlled)
        writer.saveFood("pending-custom", customFood("custom:favorite"))
        writer.flush()
        assertTrue(writer.saveResult.value!!.operationId > 0)
        assertNull(repository.observeFavorite("reference").first())
        writer.favorite("reference", "  $longName  ", enabled = true)
        assertNull(writer.saveResult.value)
        writer.flush()
        val favorite = repository.observeFavorite("reference").first()!!
        assertEquals("reference", favorite.foodId)
        assertEquals(longName.take(250), favorite.name)
        assertEquals(longName, repository.food("reference")!!.food.name)
        assertNull(writer.saveResult.value)
        writer.favorite("reference", longName, enabled = false)
        writer.flush()
        assertNull(repository.observeFavorite("reference").first())
        assertNull(writer.saveResult.value)
        failFavorite = true
        writer.favorite("reference", longName, enabled = true)
        writer.flush()
        assertEquals("favorite:reference", writer.saveResult.value!!.requestId)
        assertEquals("reference", writer.saveResult.value!!.foodId)
        assertEquals("Error: Favorite storage failure", writer.saveResult.value!!.error)
        assertNull(repository.observeFavorite("reference").first())
        val firstFailure = writer.saveResult.value!!
        writer.acknowledge(firstFailure.copy())
        assertSame(firstFailure, writer.saveResult.value)
        assertEquals(firstFailure.error, writer.error.value)
        writer.favorite("reference", longName, enabled = true)
        assertNull(writer.saveResult.value)
        assertNull(writer.error.value)
        writer.flush()
        val secondFailure = writer.saveResult.value!!
        assertEquals(firstFailure.requestId, secondFailure.requestId)
        assertEquals(firstFailure.error, secondFailure.error)
        assertTrue(secondFailure.operationId > firstFailure.operationId)
        writer.acknowledge(firstFailure)
        writer.acknowledge(secondFailure.copy())
        assertSame(secondFailure, writer.saveResult.value)
        assertEquals(secondFailure.error, writer.error.value)
        failFavorite = false
        writer.favorite("reference", longName, enabled = true)
        assertNull(writer.saveResult.value)
        writer.flush()
        assertNotNull(repository.observeFavorite("reference").first())
        assertNull(writer.saveResult.value)
        assertNull(writer.error.value)
        writer.acknowledge(secondFailure)
        assertNull(writer.saveResult.value)
    }

    private fun keepEditor(handle: SavedStateHandle = SavedStateHandle(), id: String? = null, key: String = "editor") =
        CustomFoodEditorViewModel(repository, handle, id).also { store.put(key, it) }
    private fun keepWriter(source: NutritionRepository = repository, key: String = "writer") =
        FoodMutationViewModel(source) { "Error: ${it.message}" }.also { store.put(key, it) }
    private fun TestScope.observe(editor: CustomFoodEditorViewModel) =
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { editor.state.collect() }
    private fun copyHandle(handle: SavedStateHandle) = SavedStateHandle(handle.keys().associateWith { handle.get<Any?>(it) })

    private class HeldRepository(private val delegate: NutritionRepository) : NutritionRepository by delegate {
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var saves = 0
        override suspend fun saveCustomFood(detail: FoodDetail): String {
            saves++
            started.complete(Unit)
            release.await()
            return delegate.saveCustomFood(detail)
        }
    }

    private fun customFood(id: String) = FoodDetail(
        FoodEntity(id = id, name = "Custom food", aliases = "home staple", source = "User analysis", sourceId = "sample-1",
            sourceUrl = "https://example.com/analysis", tags = "home", region = "IN", gramsPerBase = 100.0,
            millilitersPerBase = 80.0, notes = "Original notes"),
        listOf(FoodNutrientEntity(id, NutrientCodes.ENERGY, "Energy", "kcal", 200.0),
            FoodNutrientEntity(id, "laboratory:iron", "Measured iron", "mg", 2.75),
            FoodNutrientEntity(id, "usda:291", "Fiber", "g", null)),
        listOf(FoodPortionEntity(id, "cup", "One cup", 150.0, grams = 150.0, milliliters = 120.0, source = "Measured")),
    )
}
