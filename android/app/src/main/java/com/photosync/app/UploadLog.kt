package com.photosync.app

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/**
 * Local record of which MediaStore items are safely on the server.
 * This is what drives the per-photo status badges in the gallery and
 * tells the sync worker what is still pending.
 */
class UploadLog private constructor(context: Context) :
    SQLiteOpenHelper(context.applicationContext, "uploadlog.db", null, 1) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE uploaded (
                media_id INTEGER PRIMARY KEY,
                hash TEXT NOT NULL,
                uploaded_at INTEGER NOT NULL
            )"""
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

    /** All MediaStore ids that are confirmed on the server. */
    fun uploadedIds(): Set<Long> {
        val ids = HashSet<Long>()
        readableDatabase.rawQuery("SELECT media_id FROM uploaded", null).use { cursor ->
            while (cursor.moveToNext()) ids.add(cursor.getLong(0))
        }
        return ids
    }

    fun markUploaded(mediaId: Long, hash: String) {
        val values = ContentValues().apply {
            put("media_id", mediaId)
            put("hash", hash)
            put("uploaded_at", System.currentTimeMillis())
        }
        writableDatabase.insertWithOnConflict(
            "uploaded", null, values, SQLiteDatabase.CONFLICT_REPLACE
        )
    }

    fun uploadedCount(): Long {
        readableDatabase.rawQuery("SELECT COUNT(*) FROM uploaded", null).use { cursor ->
            return if (cursor.moveToFirst()) cursor.getLong(0) else 0
        }
    }

    /**
     * Forgets all backed-up records. Used when the account changes, since
     * "uploaded" is tracked per server folder — a new user starts empty.
     */
    fun clear() {
        writableDatabase.delete("uploaded", null, null)
    }

    companion object {
        @Volatile
        private var instance: UploadLog? = null

        fun get(context: Context): UploadLog =
            instance ?: synchronized(this) {
                instance ?: UploadLog(context).also { instance = it }
            }
    }
}
