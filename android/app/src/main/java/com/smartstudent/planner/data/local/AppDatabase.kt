package com.smartstudent.planner.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        TaskEntity::class,
        TimetableEntryEntity::class,
        SettingsEntity::class,
        HabitEntity::class,
        HabitCompletionEntity::class,
    ],
    version = 4,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun taskDao(): TaskDao
    abstract fun timetableDao(): TimetableDao
    abstract fun settingsDao(): SettingsDao
    abstract fun habitDao(): HabitDao
    abstract fun habitCompletionDao(): HabitCompletionDao
}

/** Phase 1 -> phase 2: adds the timetable and settings tables. Keeps existing tasks. */
val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `timetable_entries` (" +
                "`id` TEXT NOT NULL, " +
                "`dayOfWeek` INTEGER NOT NULL, " +
                "`title` TEXT NOT NULL, " +
                "`startTime` TEXT NOT NULL, " +
                "`endTime` TEXT NOT NULL, " +
                "`deleted` INTEGER NOT NULL, " +
                "`dirty` INTEGER NOT NULL, " +
                "`updatedAtLocal` INTEGER NOT NULL, " +
                "PRIMARY KEY(`id`))"
        )
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `settings` (" +
                "`id` INTEGER NOT NULL, " +
                "`wakeTime` TEXT NOT NULL, " +
                "`sleepTime` TEXT NOT NULL, " +
                "`morningRoutineMinutes` INTEGER NOT NULL, " +
                "`breakfastStart` TEXT NOT NULL, " +
                "`breakfastMinutes` INTEGER NOT NULL, " +
                "`lunchStart` TEXT NOT NULL, " +
                "`lunchMinutes` INTEGER NOT NULL, " +
                "`dinnerStart` TEXT NOT NULL, " +
                "`dinnerMinutes` INTEGER NOT NULL, " +
                "`timezone` TEXT NOT NULL, " +
                "`dirty` INTEGER NOT NULL, " +
                "`updatedAtLocal` INTEGER NOT NULL, " +
                "PRIMARY KEY(`id`))"
        )
    }
}

/** Phase 2 -> phase 3: task progress, habits and habit completions. Keeps existing data. */
val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `tasks` ADD COLUMN `spentMinutes` INTEGER NOT NULL DEFAULT 0")
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `habits` (" +
                "`id` TEXT NOT NULL, " +
                "`title` TEXT NOT NULL, " +
                "`minutes` INTEGER NOT NULL, " +
                "`priority` TEXT NOT NULL, " +
                "`kind` TEXT NOT NULL, " +
                "`days` TEXT NOT NULL, " +
                "`timesPerWeek` INTEGER NOT NULL, " +
                "`deleted` INTEGER NOT NULL, " +
                "`dirty` INTEGER NOT NULL, " +
                "`updatedAtLocal` INTEGER NOT NULL, " +
                "PRIMARY KEY(`id`))"
        )
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `habit_completions` (" +
                "`id` TEXT NOT NULL, " +
                "`habitId` TEXT NOT NULL, " +
                "`date` TEXT NOT NULL, " +
                "`deleted` INTEGER NOT NULL, " +
                "`dirty` INTEGER NOT NULL, " +
                "`updatedAtLocal` INTEGER NOT NULL, " +
                "PRIMARY KEY(`id`))"
        )
    }
}

/** Phase 3 -> phase 3c: travel, rest and break options on the settings row. Keeps existing data. */
val MIGRATION_3_4 = object : Migration(3, 4) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `settings` ADD COLUMN `commuteToMinutes` INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE `settings` ADD COLUMN `commuteFromMinutes` INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE `settings` ADD COLUMN `arriveEarlyMinutes` INTEGER NOT NULL DEFAULT 10")
        db.execSQL("ALTER TABLE `settings` ADD COLUMN `restStart` TEXT NOT NULL DEFAULT '16:00'")
        db.execSQL("ALTER TABLE `settings` ADD COLUMN `restMinutes` INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE `settings` ADD COLUMN `breakMinutes` INTEGER NOT NULL DEFAULT 10")
        db.execSQL("ALTER TABLE `settings` ADD COLUMN `longBreakAfterMinutes` INTEGER NOT NULL DEFAULT 120")
        db.execSQL("ALTER TABLE `settings` ADD COLUMN `longBreakMinutes` INTEGER NOT NULL DEFAULT 20")
    }
}
