package com.photosync.app

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.bumptech.glide.load.model.GlideUrl
import com.bumptech.glide.load.model.LazyHeaders

/**
 * Grid adapter for the server gallery. Shows thumbnails loaded directly from
 * the server via Glide (with auth headers); no status badges. Headers and
 * [GalleryRow] rows are built by [GallerySections] exactly as in the local
 * gallery, so the layout and date-pill behaviour are identical.
 */
class ServerGalleryAdapter(
    private val apiKey: String,
    private val username: String,
    private val onItemClick: (mediaIndex: Int) -> Unit,
) : ListAdapter<GalleryRow, RecyclerView.ViewHolder>(DIFF) {

    init { setHasStableIds(true) }

    override fun getItemId(position: Int): Long = getItem(position).id

    override fun getItemViewType(position: Int): Int =
        if (getItem(position) is GalleryRow.Header) TYPE_HEADER else TYPE_PHOTO

    fun isHeader(position: Int): Boolean = getItem(position) is GalleryRow.Header

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return if (viewType == TYPE_HEADER)
            HeaderHolder(inflater.inflate(R.layout.item_date_header, parent, false))
        else
            PhotoHolder(inflater.inflate(R.layout.item_photo, parent, false), onItemClick)
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val row = getItem(position)) {
            is GalleryRow.Header -> (holder as HeaderHolder).bind(row)
            is GalleryRow.Photo -> (holder as PhotoHolder).bind(row, apiKey, username)
        }
    }

    class HeaderHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val label: TextView = view.findViewById(R.id.dateLabel)
        fun bind(row: GalleryRow.Header) { label.text = row.label }
    }

    class PhotoHolder(
        view: View,
        private val onItemClick: (Int) -> Unit,
    ) : RecyclerView.ViewHolder(view) {
        private val thumb: ImageView = view.findViewById(R.id.thumb)
        private val badge: ImageView = view.findViewById(R.id.badge)

        fun bind(row: GalleryRow.Photo, apiKey: String, username: String) {
            badge.visibility = View.GONE
            itemView.setOnClickListener { onItemClick(row.mediaIndex) }

            val uri = row.entry.item.uri.toString()
            val glideUrl = if (apiKey.isNotEmpty() || username.isNotEmpty()) {
                val headers = LazyHeaders.Builder().apply {
                    if (apiKey.isNotEmpty()) addHeader("x-api-key", apiKey)
                    if (username.isNotEmpty()) addHeader(
                        "x-user", java.net.URLEncoder.encode(username, "UTF-8")
                    )
                }.build()
                GlideUrl(uri, headers)
            } else {
                GlideUrl(uri)
            }
            Glide.with(thumb).load(glideUrl).centerCrop().into(thumb)
        }
    }

    companion object {
        private const val TYPE_HEADER = 0
        private const val TYPE_PHOTO = 1

        private val DIFF = object : DiffUtil.ItemCallback<GalleryRow>() {
            override fun areItemsTheSame(oldItem: GalleryRow, newItem: GalleryRow) =
                oldItem.id == newItem.id
            override fun areContentsTheSame(oldItem: GalleryRow, newItem: GalleryRow) =
                oldItem == newItem
        }
    }
}
