package com.visualmapper.companion.service

import android.content.Context
import android.util.Log
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.visualmapper.companion.VisualMapperApp
import com.visualmapper.companion.mqtt.MqttManager
import com.visualmapper.companion.storage.AppDatabase
import java.util.concurrent.TimeUnit

/**
 * Background Sync Worker for Phase 2 Stability.
 *
 * Periodically flushes pending navigation transitions from the Room database
 * to MQTT when network is available. This ensures data queued during offline
 * periods is eventually delivered.
 *
 * Features:
 * - Runs every 15 minutes when network is available
 * - Respects MQTT connection state
 * - Handles retry logic with exponential backoff
 * - Cleans up expired transitions
 */
class SyncWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    companion object {
        private const val TAG = "SyncWorker"
        private const val WORK_NAME = "transition_sync"
        private const val MAX_ITEMS_PER_BATCH = 20
        private const val MAX_RETRY_COUNT = 10
        private const val RETRY_BACKOFF_MS = 60_000L  // 1 minute

        /**
         * Schedule periodic sync work.
         * Call this from VisualMapperApp.onCreate()
         */
        fun schedule(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            val syncRequest = PeriodicWorkRequestBuilder<SyncWorker>(
                15, TimeUnit.MINUTES,  // Repeat interval
                5, TimeUnit.MINUTES    // Flex interval
            )
                .setConstraints(constraints)
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,  // Don't replace existing
                syncRequest
            )

            Log.i(TAG, "Scheduled periodic sync worker (every 15 min with network)")
        }

        /**
         * Cancel scheduled sync work.
         */
        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
            Log.i(TAG, "Cancelled periodic sync worker")
        }
    }

    override suspend fun doWork(): Result {
        Log.d(TAG, "SyncWorker starting...")

        val app = try {
            applicationContext as VisualMapperApp
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get VisualMapperApp", e)
            return Result.failure()
        }

        val mqttManager = app.mqttManager
        val database = AppDatabase.getDatabase(applicationContext)
        val dao = database.pendingTransitionDao()

        // Check MQTT connection state
        if (mqttManager.connectionState.value != MqttManager.ConnectionState.CONNECTED) {
            Log.d(TAG, "MQTT not connected, will retry later")
            return Result.retry()
        }

        return try {
            // Get pending transitions that are eligible for retry
            val cutoffTime = System.currentTimeMillis() - RETRY_BACKOFF_MS
            val pendingItems = dao.getRetryableTransitions(cutoffTime, MAX_ITEMS_PER_BATCH)

            if (pendingItems.isEmpty()) {
                Log.d(TAG, "No pending transitions to sync")
                return Result.success()
            }

            Log.i(TAG, "Processing ${pendingItems.size} pending transitions...")

            var successCount = 0
            var failCount = 0

            for (item in pendingItems) {
                try {
                    // Publish to MQTT
                    mqttManager.publishNavigationTransition(item.payload)

                    // Delete on success
                    dao.delete(item)
                    successCount++
                    Log.d(TAG, "Synced transition id=${item.id}")

                } catch (e: Exception) {
                    Log.w(TAG, "Failed to sync transition id=${item.id}: ${e.message}")

                    // Update retry count
                    val updatedItem = item.copy(
                        retryCount = item.retryCount + 1,
                        lastRetryTime = System.currentTimeMillis()
                    )

                    if (updatedItem.retryCount >= MAX_RETRY_COUNT) {
                        // Too many retries - delete it
                        dao.delete(item)
                        Log.w(TAG, "Dropped transition id=${item.id} after $MAX_RETRY_COUNT retries")
                    } else {
                        // Update for next retry
                        dao.update(updatedItem)
                    }
                    failCount++
                }
            }

            // Clean up any very old expired transitions
            dao.deleteExpiredTransitions(MAX_RETRY_COUNT)

            val totalPending = dao.getCount()
            Log.i(TAG, "Sync complete: $successCount synced, $failCount failed, $totalPending remaining")

            Result.success()

        } catch (e: Exception) {
            Log.e(TAG, "SyncWorker failed", e)
            Result.retry()
        }
    }
}
