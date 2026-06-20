package com.photosync.app

import android.content.Context
import android.util.Log
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

/**
 * One sync pass: if the server is reachable on the current (unmetered)
 * network, take the photos/videos not yet in the local UploadLog, ask the
 * server which of those it's missing, and upload them oldest-first. Every
 * item confirmed on the server (uploaded now, or already there) is recorded
 * in the UploadLog — which is also what the gallery's status badges show.
 */
class SyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val prefs = SyncPrefs(applicationContext)

        if (prefs.serverUrl.isEmpty()) {
            prefs.lastSyncStatus = "No server configured"
            return@withContext Result.success()
        }

        if (prefs.username.isEmpty()) {
            prefs.lastSyncStatus = "Set your name in Settings to back up"
            return@withContext Result.success()
        }

        SyncEvents.syncing.value = true
        try {
            runPass(prefs)
        } finally {
            SyncEvents.syncing.value = false
            SyncEvents.uploadingItemId.value = null
        }
    }

    private suspend fun runPass(prefs: SyncPrefs): Result {
        val api = ServerApi(prefs.serverUrl, prefs.apiKey, prefs.username)
        if (api.health() == null) {
            // Server not reachable right now (e.g. PC is off). That's normal;
            // the next periodic run will try again.
            prefs.lastSyncStatus = "Server offline, will retry"
            return Result.success()
        }

        val log = UploadLog.get(applicationContext)
        val done = log.uploadedIds()
        val pending = MediaScanner.itemsForBackup(applicationContext, prefs)
            .filter { it.id !in done }
            .sortedBy { it.dateAddedSec } // oldest first

        if (pending.isEmpty()) {
            prefs.lastSyncTime = System.currentTimeMillis()
            prefs.lastSyncStatus = "Up to date"
            return Result.success()
        }

        val batch = pending.take(MAX_ITEMS_PER_RUN)
        var uploaded = 0
        var skipped = 0
        var errors = 0

        try {
            // Hash the batch, ask the server what it's missing, upload that.
            val hashes = HashMap<MediaItem, String>(batch.size)
            for (item in batch) {
                MediaScanner.sha256(applicationContext, item)?.let { hashes[item] = it }
            }
            val missing = api.checkMissing(hashes.values.distinct())

            for (item in batch) {
                // Items we couldn't hash (deleted between scan and now) are
                // simply not recorded; they vanish from the next scan.
                val hash = hashes[item] ?: continue

                if (hash !in missing) {
                    // Server already has this content (e.g. uploaded from
                    // another device, or a previous app install).
                    skipped++
                    log.markUploaded(item.id, hash)
                    SyncEvents.notifyChanged()
                    continue
                }
                try {
                    val stream = applicationContext.contentResolver.openInputStream(item.uri)
                        ?: continue
                    SyncEvents.uploadingItemId.value = item.id
                    stream.use {
                        api.upload(it, item.sizeBytes, item.displayName, item.takenAtMs, hash)
                    }
                    uploaded++
                    prefs.totalUploaded += 1
                    log.markUploaded(item.id, hash)
                    SyncEvents.notifyChanged()
                } catch (e: Exception) {
                    Log.w(TAG, "upload failed for ${item.displayName}", e)
                    errors++
                    // Stop the pass; this item isn't in the log, so the next
                    // run retries it first.
                    break
                } finally {
                    SyncEvents.uploadingItemId.value = null
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "sync pass failed", e)
            prefs.lastSyncStatus = "Sync error: ${e.message}"
            return Result.retry()
        }

        prefs.lastSyncTime = System.currentTimeMillis()
        val remaining = pending.size - batch.size
        prefs.lastSyncStatus = buildString {
            append("Uploaded $uploaded")
            if (skipped > 0) append(", $skipped already on server")
            if (errors > 0) append(", $errors failed")
            if (remaining > 0) append(", $remaining queued")
        }

        // Large first-time backlogs are processed in chunks; immediately
        // queue the next chunk instead of waiting for the next period.
        if (remaining > 0 && errors == 0) {
            enqueueOneTime(applicationContext)
        }

        return if (errors > 0) Result.retry() else Result.success()
    }

    companion object {
        private const val TAG = "PhotoSync"
        private const val MAX_ITEMS_PER_RUN = 100
        private const val PERIODIC_WORK = "photosync-periodic"
        private const val ONETIME_WORK = "photosync-now"

        private fun wifiConstraints() = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.UNMETERED)
            .build()

        /** Called when auto-sync is enabled: run every 15 min while on WiFi. */
        fun schedulePeriodic(context: Context) {
            val request = PeriodicWorkRequestBuilder<SyncWorker>(15, TimeUnit.MINUTES)
                .setConstraints(wifiConstraints())
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                PERIODIC_WORK, ExistingPeriodicWorkPolicy.KEEP, request
            )
        }

        fun cancelPeriodic(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(PERIODIC_WORK)
        }

        /** "Sync now" action and backlog chaining. */
        fun enqueueOneTime(context: Context) {
            val request = OneTimeWorkRequestBuilder<SyncWorker>()
                .setConstraints(wifiConstraints())
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                ONETIME_WORK, ExistingWorkPolicy.REPLACE, request
            )
        }
    }
}
