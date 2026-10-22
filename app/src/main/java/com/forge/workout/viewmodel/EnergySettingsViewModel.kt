package com.forge.workout.viewmodel

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.forge.workout.nutrition.model.NutritionSettingsEntity
import com.forge.workout.nutrition.model.WorkoutActivityPreset
import com.forge.workout.nutrition.repository.NutritionRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*

data class EnergySettingsDraft(val includeWorkoutCalories: Boolean = false,
    val preset: WorkoutActivityPreset = WorkoutActivityPreset.RESISTANCE, val activePercent: String = "50")
data class EnergySettingsState(val settings: NutritionSettingsEntity = NutritionSettingsEntity(),
    val draft: EnergySettingsDraft = EnergySettingsDraft(), val loading: Boolean = true, val error: Boolean = false,
    val invalidFields: Set<String> = emptySet())
private data class EnergySettingsForm(val draft: EnergySettingsDraft = EnergySettingsDraft(), val dirty: Boolean = false,
    val invalidFields: Set<String> = emptySet())
private data class SettingsSource(val settings: NutritionSettingsEntity = NutritionSettingsEntity(), val loading: Boolean = true, val error: Boolean = false)

@OptIn(ExperimentalCoroutinesApi::class)
class EnergySettingsViewModel(repository: NutritionRepository, private val savedState: SavedStateHandle) : ViewModel() {
    private val generation = MutableStateFlow(0)
    private val initial = EnergySettingsDraft(savedState[INCLUDE] ?: false,
        WorkoutActivityPreset.fromId(savedState[PRESET] ?: WorkoutActivityPreset.RESISTANCE.id), savedState[PERCENT] ?: "50")
    private val form = MutableStateFlow(EnergySettingsForm(initial, savedState.get<Boolean>(DIRTY) == true))
    private val source = generation.flatMapLatest {
        flow {
            emit(SettingsSource())
            emitAll(repository.observeSettings().map { SettingsSource(it, loading = false) })
        }.catch { failure ->
            if (failure is CancellationException) throw failure
            emit(SettingsSource(loading = false, error = true))
        }
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SettingsSource())
    val state = combine(form, source) { fields, loaded ->
        EnergySettingsState(loaded.settings, current(fields, loaded), loaded.loading, loaded.error, fields.invalidFields)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), EnergySettingsState(draft = initial))
    init { persist(initial, form.value.dirty) }
    fun updateDraft(draft: EnergySettingsDraft): Boolean {
        if (draft.activePercent.length > 250) { form.value = form.value.copy(invalidFields = setOf("activePercent")); return false }
        persist(draft, dirty = true)
        form.value = EnergySettingsForm(draft.copy(), dirty = true)
        return true
    }
    fun validatedSettings(): NutritionSettingsEntity? {
        val loaded = source.value
        if (loaded.loading || loaded.error) return null
        val draft = current(form.value, loaded)
        val percent = draft.activePercent.trim().replace(',', '.').toDoubleOrNull()?.takeIf { it.isFinite() && it in 0.0..100.0 }
        form.value = form.value.copy(invalidFields = if (percent == null) setOf("activePercent") else emptySet())
        return percent?.let { loaded.settings.copy(includeWorkoutCalories = draft.includeWorkoutCalories,
            defaultActivity = draft.preset.id, defaultActiveFraction = it / 100.0) }
    }
    fun reload() { generation.value++ }
    private fun current(fields: EnergySettingsForm, loaded: SettingsSource) = if (fields.dirty) fields.draft else
        EnergySettingsDraft(loaded.settings.includeWorkoutCalories, WorkoutActivityPreset.fromId(loaded.settings.defaultActivity), percentText(loaded.settings.defaultActiveFraction))
    private fun persist(draft: EnergySettingsDraft, dirty: Boolean) {
        savedState[INCLUDE] = draft.includeWorkoutCalories; savedState[PRESET] = draft.preset.id
        savedState[PERCENT] = draft.activePercent; savedState[DIRTY] = dirty
    }
    private companion object {
        const val INCLUDE = "energy_settings_include"; const val PRESET = "energy_settings_preset"
        const val PERCENT = "energy_settings_percent"; const val DIRTY = "energy_settings_dirty"
    }
}

internal fun percentText(fraction: Double): String = (fraction * 100).toBigDecimal().stripTrailingZeros().toPlainString()
