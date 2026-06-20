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
 * Renders the gallery as a date-sectioned grid: full-width [GalleryRow.Header]
 * rows interleaved with square photo / video tiles. Video tiles show a
 * duration chip. Tapping a tile calls [onPhotoClick] with its index into the
 * ordered media list.
 */
class GalleryAdapter(
    private val onPhotoClick: (mediaIndex: Int) -> Unit,
) : ListAdapter<GalleryRow, RecyclerView.ViewHolder>(DIFF) {

    init {
        setHasStableIds(true)
    }

    override fun getItemId(position: Int): Long = getItem(position).id

    override fun getItemViewType(position: Int): Int = when (val row = getItem(position)) {
        is GalleryRow.Header -> TYPE_HEADER
        is GalleryRow.Photo -> if (row.entry.item.isVideo) TYPE_VIDEO else TYPE_PHOTO
    }

    fun isHeader(position: Int): Boolean = getItem(position) is GalleryRow.Header

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return when (viewType) {
            TYPE_HEADER -> HeaderHolder(inflater.inflate(R.layout.item_date_header, parent, false))
            TYPE_VIDEO -> VideoHolder(inflater.inflate(R.layout.item_video, parent, false), onPhotoClick)
            else -> PhotoHolder(inflater.inflate(R.layout.item_photo, parent, false), onPhotoClick)
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

    class HeaderHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val label: TextView = view.findViewById(R.id.dateLabel)
        fun bind(row: GalleryRow.Header) {
            label.text = row.label
        }
    }

    class PhotoHolder(
        view: View,
        private val onPhotoClick: (Int) -> Unit,
    ) : RecyclerView.ViewHolder(view) {
        private val thumb: ImageView = view.findViewById(R.id.thumb)
        val badge: ImageView = view.findViewById(R.id.badge)

        fun bind(row: GalleryRow.Photo) {
            itemView.setOnClickListener { onPhotoClick(row.mediaIndex) }
            Glide.with(thumb).load(row.entry.item.uri).centerCrop().into(thumb)
            applyStatusBadge(badge, row.entry.status)
        }
    }

    class VideoHolder(
        view: View,
        private val onPhotoClick: (Int) -> Unit,
    ) : RecyclerView.ViewHolder(view) {
        private val thumb: ImageView = view.findViewById(R.id.thumb)
        val badge: ImageView = view.findViewById(R.id.badge)
        private val durationText: TextView = view.findViewById(R.id.durationText)

        fun bind(row: GalleryRow.Photo) {
            itemView.setOnClickListener { onPhotoClick(row.mediaIndex) }
            Glide.with(thumb).load(row.entry.item.uri).centerCrop().into(thumb)
            durationText.text = formatVideoDuration(row.entry.item.durationMs)
            applyStatusBadge(badge, row.entry.status)
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
