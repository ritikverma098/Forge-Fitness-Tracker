package com.forge.workout.preferences

import android.content.Context
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStoreFile
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import java.io.IOException

enum class ThemeMode { SYSTEM, LIGHT, DARK }
enum class WeightUnit(val symbol: String) {
    KG("kg"), LB("lb");
    fun fromKg(value: Double): Double = if (this == LB) value * 2.2046226218 else value
    fun toKg(value: Double): Double = if (this == LB) value / 2.2046226218 else value
}
data class UserPreferences(
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val weightUnit: WeightUnit = WeightUnit.KG,
    val restSeconds: Int = 90,
    val confirmDeletion: Boolean = true
)
class PreferencesRepository(context: Context, name: String = "forge_preferences") {
    private val store = PreferenceDataStoreFactory.create(produceFile = { context.applicationContext.preferencesDataStoreFile(name) })
    private val theme = stringPreferencesKey("theme")
    private val unit = stringPreferencesKey("weight_unit")
    private val rest = intPreferencesKey("rest_seconds")
    private val confirmation = booleanPreferencesKey("confirm_deletion")
    val preferences: Flow<UserPreferences> = store.data.catch {
        if (it is IOException) emit(emptyPreferences()) else throw it
    }.map { values ->
        UserPreferences(
            ThemeMode.entries.firstOrNull { it.name == values[theme] } ?: ThemeMode.SYSTEM,
            WeightUnit.entries.firstOrNull { it.name == values[unit] } ?: WeightUnit.KG,
            values[rest]?.takeIf { it in REST_PRESETS } ?: 90,
            values[confirmation] ?: true
        )
    }
    suspend fun setTheme(value: ThemeMode) { store.edit { it[theme] = value.name } }
    suspend fun setUnit(value: WeightUnit) { store.edit { it[unit] = value.name } }
    suspend fun setRest(value: Int) { require(value in REST_PRESETS); store.edit { it[rest] = value } }
    suspend fun setConfirmation(value: Boolean) { store.edit { it[confirmation] = value } }
    suspend fun replace(values: UserPreferences) {
        require(values.restSeconds in REST_PRESETS)
        store.edit {
            it[theme] = values.themeMode.name
            it[unit] = values.weightUnit.name
            it[rest] = values.restSeconds
            it[confirmation] = values.confirmDeletion
        }
    }
    companion object { val REST_PRESETS = listOf(0, 30, 60, 90, 120, 180) }
}
