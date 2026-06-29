package com.photosync.app

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.AnimationUtils
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide

enum class SyncStatus { PENDING, UPLOADING, DONE }

data class GalleryEntry(val item: MediaItem, val status: SyncStatus)

/**
 * Renders the gallery as a date-sectioned grid. Supports a selection mode
 * entered via long-press: selected tiles show a checked overlay, tapping
 * them toggles selection. Call [exitSelectionMode] to clear the selection.
 */
class GalleryAdapter(
    private val onPhotoClick: (mediaIndex: Int) -> Unit,
    private val onSelectionChanged: (selected: Set<Long>) -> Unit,
) : ListAdapter<GalleryRow, RecyclerView.ViewHolder>(DIFF) {

    /** MediaStore IDs currently selected (non-null only while in selection mode). */
    private val selectedIds = mutableSetOf<Long>()
    var selectionMode = false
        private set

    init {
        setHasStableIds(true)
    }

    override fun getItemId(position: Int): Long = getItem(position).id

    override fun getItemViewType(position: Int): Int = when (val row = getItem(position)) {
        is GalleryRow.Header -> TYPE_HEADER
        is GalleryRow.Photo -> if (row.entry.item.isVideo) TYPE_VIDEO else TYPE_PHOTO
    }

    fun isHeader(position: Int): Boolean = getItem(position) is GalleryRow.Header

    fun exitSelectionMode() {
        if (!selectionMode) return
        selectionMode = false
        selectedIds.clear()
        notifyDataSetChanged()
        onSelectionChanged(emptySet())
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return when (viewType) {
            TYPE_HEADER -> HeaderHolder(inflater.inflate(R.layout.item_date_header, parent, false))
            TYPE_VIDEO -> VideoHolder(inflater.inflate(R.layout.item_video, parent, false))
            else -> PhotoHolder(inflater.inflate(R.layout.item_photo, parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val row = getItem(position)) {
            is GalleryRow.Header -> (holder as HeaderHolder).bind(row)
            is GalleryRow.Photo ->
                if (holder is VideoHolder) holder.bind(row) else (holder as PhotoHolder).bind(row)
        }
    }

    override fun onViewRecycled(holder: RecyclerView.ViewHolder) {
        when (holder) {
            is PhotoHolder -> holder.badge.clearAnimation()
            is VideoHolder -> holder.badge.clearAnimation()
        }
    }

    private fun onTileClick(row: GalleryRow.Photo) {
        if (selectionMode) {
            val id = row.entry.item.id
            if (id in selectedIds) selectedIds.remove(id) else selectedIds.add(id)
            if (selectedIds.isEmpty()) exitSelectionMode()
            else {
                notifyDataSetChanged()
                onSelectionChanged(selectedIds.toSet())
            }
        } else {
            onPhotoClick(row.mediaIndex)
        }
    }

    private fun onTileLongClick(row: GalleryRow.Photo) {
        if (!selectionMode) {
            selectionMode = true
            selectedIds.clear()
        }
        selectedIds.add(row.entry.item.id)
        notifyDataSetChanged()
        onSelectionChanged(selectedIds.toSet())
    }

    class HeaderHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val label: TextView = view.findViewById(R.id.dateLabel)
        fun bind(row: GalleryRow.Header) {
            label.text = row.label
        }
    }

    inner class PhotoHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val thumb: ImageView = view.findViewById(R.id.thumb)
        val badge: ImageView = view.findViewById(R.id.badge)
        private val selectionCircle: ImageView? = view.findViewById(R.id.selectionCircle)

        fun bind(row: GalleryRow.Photo) {
            itemView.setOnClickListener { onTileClick(row) }
            itemView.setOnLongClickListener { onTileLongClick(row); true }
            Glide.with(thumb).load(row.entry.item.uri).centerCrop().into(thumb)
            applyStatusBadge(badge, row.entry.status)
            applySelectionCircle(selectionCircle, row.entry.item.id)
        }
    }

    inner class VideoHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val thumb: ImageView = view.findViewById(R.id.thumb)
        val badge: ImageView = view.findViewById(R.id.badge)
        private val durationText: TextView = view.findViewById(R.id.durationText)
        private val selectionCircle: ImageView? = view.findViewById(R.id.selectionCircle)

        fun bind(row: GalleryRow.Photo) {
            itemView.setOnClickListener { onTileClick(row) }
            itemView.setOnLongClickListener { onTileLongClick(row); true }
            Glide.with(thumb).load(row.entry.item.uri).centerCrop().into(thumb)
            durationText.text = formatVideoDuration(row.entry.item.durationMs)
            applyStatusBadge(badge, row.entry.status)
            applySelectionCircle(selectionCircle, row.entry.item.id)
        }
    }

    private fun applySelectionCircle(circle: ImageView?, itemId: Long) {
        circle ?: return
        if (!selectionMode) {
            circle.visibility = View.GONE
            return
        }
        circle.visibility = View.VISIBLE
        if (itemId in selectedIds) {
            circle.setImageResource(R.drawable.ic_sel_checked)
        } else {
            circle.setImageResource(R.drawable.ic_sel_unchecked)
        }
    }

    companion object {
        private const val TYPE_HEADER = 0
        private const val TYPE_PHOTO = 1
        private const val TYPE_VIDEO = 2

        private fun applyStatusBadge(badge: ImageView, status: SyncStatus) {
            badge.clearAnimation()
            when (status) {
                SyncStatus.DONE -> {
                    badge.setImageResource(R.drawable.ic_status_done)
                    badge.contentDescription = badge.context.getString(R.string.status_done)
                }
                SyncStatus.UPLOADING -> {
                    badge.setImageResource(R.drawable.ic_status_uploading)
                    badge.contentDescription = badge.context.getString(R.string.status_uploading)
                    badge.startAnimation(
                        AnimationUtils.loadAnimation(badge.context, R.anim.rotate_forever)
                    )
                }
                SyncStatus.PENDING -> {
                    badge.setImageResource(R.drawable.ic_status_pending)
                    badge.contentDescription = badge.context.getString(R.string.status_pending)
                }
            }
        }

        private val DIFF = object : DiffUtil.ItemCallback<GalleryRow>() {
            override fun areItemsTheSame(oldItem: GalleryRow, newItem: GalleryRow) =
                oldItem.id == newItem.id

            override fun areContentsTheSame(oldItem: GalleryRow, newItem: GalleryRow): Boolean =
                when {
                    oldItem is GalleryRow.Header && newItem is GalleryRow.Header ->
                        oldItem.label == newItem.label
                    oldItem is GalleryRow.Photo && newItem is GalleryRow.Photo ->
                        oldItem.entry.status == newItem.entry.status &&
                            oldItem.entry.item.uri == newItem.entry.item.uri &&
                            oldItem.mediaIndex == newItem.mediaIndex
                    else -> false
                }
        }
    }
}
