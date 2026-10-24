package com.smartstudent.planner.data.local

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** How the app looks: follow the phone, always light, or always dark. */
enum class ThemeMode(val label: String) {
    SYSTEM("Match my phone"),
    LIGHT("Light"),
    DARK("Dark"),
}

private val Context.appearanceDataStore by preferencesDataStore(name = "appearance")

/** This device only: not synced. */
class AppearanceStore(private val context: Context) {
    private val key = stringPreferencesKey("theme_mode")

    val mode: Flow<ThemeMode> = context.appearanceDataStore.data.map { prefs ->
        prefs[key]?.let { name -> ThemeMode.values().firstOrNull { it.name == name } } ?: ThemeMode.SYSTEM
    }

    suspend fun set(mode: ThemeMode) {
        context.appearanceDataStore.edit { it[key] = mode.name }
    }
}
