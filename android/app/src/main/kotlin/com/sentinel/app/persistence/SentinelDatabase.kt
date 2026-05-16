package com.sentinel.app.persistence

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

/**
 * Single Room database for every persisted Sentinel artefact.
 *
 * Sprint Quality (v1): aggregated daily stats + bounded event logs.
 * Schema is exported to `android/app/schemas/` (configured in
 * `build.gradle.kts`) so future migrations can diff against the
 * frozen contract instead of relying on the runtime hash.
 *
 * `fallbackToDestructiveMigration` is explicitly disabled: if a
 * migration is missing we want the build to fail loudly during
 * development rather than silently wiping the user's history in
 * production.
 */
@Database(
    entities = [
        DailyStatsEntity::class,
        BlockEventEntity::class,
        AnalysisEventEntity::class,
    ],
    version = 1,
    exportSchema = true,
)
abstract class SentinelDatabase : RoomDatabase() {

    abstract fun statsDao(): StatsDao
    abstract fun eventsDao(): EventsDao

    companion object {
        private const val DB_NAME = "sentinel.db"

        fun create(context: Context): SentinelDatabase {
            return Room.databaseBuilder(
                context.applicationContext,
                SentinelDatabase::class.java,
                DB_NAME,
            ).build()
        }
    }
}
