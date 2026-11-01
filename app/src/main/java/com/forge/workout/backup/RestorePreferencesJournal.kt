package com.forge.workout.backup

import com.forge.workout.preferences.PreferencesRepository
import com.forge.workout.preferences.ThemeMode
import com.forge.workout.preferences.UserPreferences
import com.forge.workout.preferences.WeightUnit
import org.json.JSONObject

/** Local recovery instructions are never part of a portable backup. */
internal object RestorePreferencesJournal {
    private const val PREFIX = "forge_internal_restore_"
    const val KEY = "${PREFIX}preferences_v1"

    fun isInternal(key: String): Boolean = key.startsWith(PREFIX)

    fun encode(preferences: UserPreferences): String {
        require(preferences.restSeconds in PreferencesRepository.REST_PRESETS)
        return JSONObject().put("version", 1)
            .put("theme", preferences.themeMode.name)
            .put("unit", preferences.weightUnit.name)
            .put("rest", preferences.restSeconds)
            .put("confirmation", preferences.confirmDeletion)
            .toString()
    }

    fun decode(value: String): UserPreferences = try {
        check(value.length <= 512)
        val json = JSONObject(value)
        check(json.length() == 5 && json.get("version") == 1)
        val rest = json.get("rest")
        val confirmation = json.get("confirmation")
        check(rest is Int && rest in PreferencesRepository.REST_PRESETS && confirmation is Boolean)
        UserPreferences(ThemeMode.valueOf(json.getString("theme")), WeightUnit.valueOf(json.getString("unit")),
            rest, confirmation)
    } catch (failure: Exception) {
        throw IllegalStateException("The interrupted restore journal is invalid.", failure)
    }
}
