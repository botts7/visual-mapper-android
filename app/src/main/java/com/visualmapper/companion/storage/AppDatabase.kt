package com.visualmapper.companion.storage

import android.content.Context
import android.util.Log
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Room Database for Visual Mapper Companion.
 *
 * Phase 1 Refactor: Centralized local storage for:
 * - Pending navigation transitions (guaranteed delivery)
 * - Execution results (offline queue for MQTT resilience)
 *
 * Phase 3 ML Stability: Added Q-learning persistence:
 * - Q-table entries (state-action pairs with Q-values)
 * - Screen visit counts (for novelty tracking)
 * - Human feedback (for Human-in-the-Loop learning)
 * - Dangerous patterns (crash/close detection)
 *
 * Thread Safety:
 * - Uses double-checked locking singleton pattern
 * - All DAO operations are suspend functions (coroutine-safe)
 * - Room handles SQLite thread safety internally
 *
 * Migration Strategy:
 * - Version 1: Initial schema with pending_transitions table
 * - Version 2: Added execution_results table for offline queue
 * - Version 3: Added Q-learning tables (q_table, screen_visits, human_feedback, dangerous_patterns)
 * - fallbackToDestructiveMigration() for development phase
 * - TODO: Add proper migrations before production release
 */
@Database(
    entities = [
        PendingTransition::class,
        ExecutionResultEntity::class,
        // Phase 3: Q-learning entities
        QTableEntity::class,
        ScreenVisitEntity::class,
        HumanFeedbackEntity::class,
        DangerousPatternEntity::class,
        // Phase 3: Offline telemetry queue
        TelemetryQueueEntity::class
    ],
    version = 4,
    exportSchema = true
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun pendingTransitionDao(): PendingTransitionDao

    abstract fun executionResultDao(): ExecutionResultDao

    /**
     * Phase 3: Q-table DAO for ML persistence.
     */
    abstract fun qTableDao(): QTableDao

    /**
     * Phase 3: Telemetry queue DAO for offline MQTT resilience.
     */
    abstract fun telemetryQueueDao(): TelemetryQueueDao

    companion object {
        private const val TAG = "AppDatabase"
        private const val DATABASE_NAME = "visual_mapper_db"

        @Volatile
        private var INSTANCE: AppDatabase? = null

        /**
         * Get the singleton database instance.
         *
         * Note: Database creation is expensive. This should be called
         * from a background thread or during app initialization.
         */
        fun getDatabase(context: Context): AppDatabase {
            // Fast path - return existing instance
            INSTANCE?.let { return it }

            // Slow path - create instance with synchronization
            return synchronized(this) {
                // Double-check after acquiring lock
                INSTANCE?.let { return@synchronized it }

                Log.i(TAG, "Creating Room database: $DATABASE_NAME")

                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    DATABASE_NAME
                )
                    // For development: destroy and recreate on schema mismatch
                    // TODO: Replace with proper migrations before production
                    .fallbackToDestructiveMigration()
                    // Log database creation/open
                    .addCallback(object : Callback() {
                        override fun onCreate(db: SupportSQLiteDatabase) {
                            super.onCreate(db)
                            Log.i(TAG, "Database created (version ${db.version})")
                        }

                        override fun onOpen(db: SupportSQLiteDatabase) {
                            super.onOpen(db)
                            Log.d(TAG, "Database opened (version ${db.version})")
                        }
                    })
                    .build()

                INSTANCE = instance
                Log.i(TAG, "Room database initialized successfully")
                instance
            }
        }

        /**
         * Close the database (for testing or shutdown).
         */
        fun closeDatabase() {
            synchronized(this) {
                INSTANCE?.let { db ->
                    if (db.isOpen) {
                        db.close()
                        Log.i(TAG, "Database closed")
                    }
                }
                INSTANCE = null
            }
        }

        // =====================================================================
        // Future Migrations (placeholder for production)
        // =====================================================================

        /**
         * Migration from version 1 to 2 (example).
         * Add when schema changes are needed.
         */
        @Suppress("unused")
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // Example: Add a new column
                // database.execSQL("ALTER TABLE pending_transitions ADD COLUMN priority INTEGER DEFAULT 0")
            }
        }
    }
}
