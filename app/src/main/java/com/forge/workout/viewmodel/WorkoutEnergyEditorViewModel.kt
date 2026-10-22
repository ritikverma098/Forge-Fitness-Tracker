package com.forge.workout.viewmodel

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.forge.workout.nutrition.model.*
import com.forge.workout.nutrition.repository.NutritionRepository
import java.time.ZoneId
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*

data class WorkoutEnergyDraft(val preset: WorkoutActivityPreset = WorkoutActivityPreset.RESISTANCE,
    val activePercent: String = "50", val manualKcal: String = "")
data class WorkoutEnergyCommand(val workoutId: Long, val preset: WorkoutActivityPreset, val activeFraction: Double, val manualKcal: Double?)
data class WorkoutEnergyEditorState(val draft: WorkoutEnergyDraft = WorkoutEnergyDraft(), val workout: EnergyWorkout? = null,
    val energy: WorkoutEnergyEntity? = null, val loading: Boolean = true, val error: Boolean = false,
    val workoutMissing: Boolean = false, val missingWeight: Boolean = false, val invalidFields: Set<String> = emptySet())
private data class EnergyEditorForm(val draft: WorkoutEnergyDraft, val dirty: Boolean, val invalidFields: Set<String> = emptySet())

@OptIn(ExperimentalCoroutinesApi::class)
class WorkoutEnergyEditorViewModel(repository: NutritionRepository, private val id: Long,
    private val savedState: SavedStateHandle, zone: ZoneId = ZoneId.systemDefault()) : ViewModel() {
    private val restored = savedState.get<Long>(ID) == id
    private val initial = if (restored) WorkoutEnergyDraft(
        WorkoutActivityPreset.fromId(savedState[PRESET] ?: WorkoutActivityPreset.RESISTANCE.id), savedState[PERCENT] ?: "50", savedState[MANUAL] ?: "")
        else WorkoutEnergyDraft()
    private val form = MutableStateFlow(EnergyEditorForm(initial, restored && savedState.get<Boolean>(DIRTY) == true))
    private val generation = MutableStateFlow(0)
    private val source = generation.flatMapLatest { observeEnergySource(repository, id, zone) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), EnergySource())
    val state = combine(form, source) { fields, loaded ->
        WorkoutEnergyEditorState(current(fields, loaded), loaded.workout, loaded.energy, loaded.loading, loaded.error,
            loaded.workoutMissing, loaded.missingWeight, fields.invalidFields)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), WorkoutEnergyEditorState(initial))
    init { persist(initial, form.value.dirty) }
    fun updateDraft(draft: WorkoutEnergyDraft): Boolean {
        val invalid = linkedSetOf<String>()
        if (draft.activePercent.length > 250) invalid += "activePercent"
        if (draft.manualKcal.length > 250) invalid += "manualKcal"
        if (invalid.isNotEmpty()) { form.value = form.value.copy(invalidFields = invalid); return false }
        persist(draft, dirty = true); form.value = EnergyEditorForm(draft.copy(), dirty = true)
        return true
    }
    fun validatedEstimate(): WorkoutEnergyCommand? {
        val loaded = source.value
        if (loaded.loading || loaded.error || loaded.workoutMissing || loaded.missingWeight) return null
        val draft = current(form.value, loaded)
        val invalid = linkedSetOf<String>()
        val percent = draft.activePercent.trim().replace(',', '.').toDoubleOrNull()?.takeIf { it.isFinite() && it in 0.0..100.0 }
        if (percent == null) invalid += "activePercent"
        val manual = if (draft.manualKcal.isBlank()) null else draft.manualKcal.trim().replace(',', '.').toDoubleOrNull()?.takeIf {
            it.isFinite() && it in 0.0..1_000_000_000.0
        }.also { if (it == null) invalid += "manualKcal" }
        form.value = form.value.copy(invalidFields = invalid)
        return if (invalid.isEmpty()) WorkoutEnergyCommand(id, draft.preset, percent!! / 100.0, manual) else null
    }
    fun reload() { generation.value++ }
    private fun current(fields: EnergyEditorForm, loaded: EnergySource): WorkoutEnergyDraft {
        if (fields.dirty) return fields.draft
        val energy = loaded.energy
        return WorkoutEnergyDraft(WorkoutActivityPreset.fromId(energy?.activity ?: loaded.settings.defaultActivity),
            percentText(energy?.activeFraction ?: loaded.settings.defaultActiveFraction),
            energy?.manualKcal?.toBigDecimal()?.stripTrailingZeros()?.toPlainString().orEmpty())
    }
    private fun persist(draft: WorkoutEnergyDraft, dirty: Boolean) {
        savedState[ID] = id; savedState[PRESET] = draft.preset.id; savedState[PERCENT] = draft.activePercent
        savedState[MANUAL] = draft.manualKcal; savedState[DIRTY] = dirty
    }
    private companion object {
        const val ID = "energy_editor_id"; const val PRESET = "energy_editor_preset"; const val PERCENT = "energy_editor_percent"
        const val MANUAL = "energy_editor_manual"; const val DIRTY = "energy_editor_dirty"
    }
}
