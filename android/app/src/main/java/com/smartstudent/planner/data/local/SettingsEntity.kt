package com.smartstudent.planner.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey
import java.util.TimeZone

/** The user's daily routine. Always a single row with id = 1. */
@Entity(tableName = "settings")
data class SettingsEntity(
    @PrimaryKey val id: Int = 1,
    val wakeTime: String,
    val sleepTime: String,
    val morningRoutineMinutes: Int,
    val breakfastStart: String,
    val breakfastMinutes: Int,
    val lunchStart: String,
    val lunchMinutes: Int,
    val dinnerStart: String,
    val dinnerMinutes: Int,
    val timezone: String,
    /** Travel on days with classes; 0 = off. The way there and the way back can differ. */
    val commuteToMinutes: Int,
    val commuteFromMinutes: Int,
    val arriveEarlyMinutes: Int,
    /** A protected rest block in the middle of the day; restMinutes = 0 turns it off. */
    val restStart: String,
    val restMinutes: Int,
    /** Breaks inside study time. longBreakAfterMinutes = 0 turns long breaks off. */
    val breakMinutes: Int,
    val longBreakAfterMinutes: Int,
    val longBreakMinutes: Int,
    val dirty: Boolean,
    val updatedAtLocal: Long,
) {
    companion object {
        /** Shown until the user saves their own settings. Not stored until then. */
        fun defaults() = SettingsEntity(
            wakeTime = "06:30",
            sleepTime = "23:00",
            morningRoutineMinutes = 45,
            breakfastStart = "08:00",
            breakfastMinutes = 20,
            lunchStart = "13:00",
            lunchMinutes = 40,
            dinnerStart = "20:00",
            dinnerMinutes = 40,
            timezone = TimeZone.getDefault().id,
            commuteToMinutes = 0,
            commuteFromMinutes = 0,
            arriveEarlyMinutes = 10,
            restStart = "16:00",
            restMinutes = 0,
            breakMinutes = 10,
            longBreakAfterMinutes = 120,
            longBreakMinutes = 20,
            dirty = false,
            updatedAtLocal = 0L,
        )
    }
}
