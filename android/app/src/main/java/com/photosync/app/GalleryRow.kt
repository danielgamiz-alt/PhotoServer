package com.photosync.app

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * A row in the gallery list: either a full-width date header or a single
 * photo tile. The grid is built from these so dates group like Google Photos.
 */
sealed class GalleryRow {
    abstract val id: Long
    /** Date label of the section this row belongs to (drives the scroll pill). */
    abstract val sectionLabel: String

    data class Header(val dayStartMillis: Long, val label: String) : GalleryRow() {
        // Negative so it never collides with a positive MediaStore id.
        override val id get() = -dayStartMillis - 1
        override val sectionLabel get() = label
    }

    data class Photo(
        val entry: GalleryEntry,
        /** Index into the ordered media list, used to open the full-screen viewer. */
        val mediaIndex: Int,
        override val sectionLabel: String,
    ) : GalleryRow() {
        override val id get() = entry.item.id
    }
}

/**
 * Groups gallery entries (already ordered newest-first by capture date) into
 * day sections with human date headers ("Today", "Yesterday", "Mon, Jun 16").
 */
object GallerySections {

    fun build(entries: List<GalleryEntry>): List<GalleryRow> {
        // Photo-only rows for a dense, uninterrupted grid. Dates are surfaced
        // by the floating scroll pill (see MainActivity), not by inline
        // full-width headers, so each photo just carries its section label.
        val rows = ArrayList<GalleryRow>(entries.size)
        entries.forEachIndexed { index, entry ->
            val label = labelFor(startOfDay(entry.item.takenAtMs))
            rows += GalleryRow.Photo(entry, index, label)
        }
        return rows
    }

    private val sameYearFormat = SimpleDateFormat("EEE, MMM d", Locale.getDefault())
    private val otherYearFormat = SimpleDateFormat("MMM d, yyyy", Locale.getDefault())

    fun labelFor(dayStartMillis: Long): String {
        val today = startOfDay(System.currentTimeMillis())
        val oneDay = 24L * 60 * 60 * 1000
        return when {
            dayStartMillis == today -> "Today"
            dayStartMillis == today - oneDay -> "Yesterday"
            sameYear(dayStartMillis, today) -> sameYearFormat.format(Date(dayStartMillis))
            else -> otherYearFormat.format(Date(dayStartMillis))
        }
    }

    private fun startOfDay(millis: Long): Long {
        val cal = Calendar.getInstance()
        cal.timeInMillis = millis
        cal.set(Calendar.HOUR_OF_DAY, 0)
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        return cal.timeInMillis
    }

    private fun sameYear(a: Long, b: Long): Boolean {
        val ca = Calendar.getInstance().apply { timeInMillis = a }
        val cb = Calendar.getInstance().apply { timeInMillis = b }
        return ca.get(Calendar.YEAR) == cb.get(Calendar.YEAR)
    }
}

/**
 * In-process hand-off of the current photo list to the full-screen viewer.
 * Both activities share one process, so this avoids serializing a large list
 * through an Intent (which can overflow the Binder transaction limit).
 */
object GalleryData {
    @Volatile
    var items: List<MediaItem> = emptyList()
}
