package com.photosync.app

import kotlinx.coroutines.flow.MutableStateFlow

/**
 * In-process signals from the sync worker to the gallery UI.
 * (WorkManager runs the worker in the app's own process, so plain
 * StateFlows are enough — no IPC needed.)
 */
object SyncEvents {

    /** True while a sync pass is running. */
    val syncing = MutableStateFlow(false)

    /** MediaStore id of the item being uploaded right now, or null. */
    val uploadingItemId = MutableStateFlow<Long?>(null)

    /** Bumped whenever the upload log changes, so the gallery re-reads it. */
    val revision = MutableStateFlow(0L)

    fun notifyChanged() {
        revision.value = revision.value + 1
    }
}
