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

private val FILE_HASH_RE = Regex("""/api/file/([a-f0-9]{64})$""")

/**
 * Grid adapter for the server gallery. Shows thumbnails loaded directly from
 * the server via Glide (with auth headers). Items already present on the
 * device show a phone-icon badge so the user knows a download isn't needed.
 * Date headers and row structure are built by [GallerySections], identical to
 * the local gallery.
 */
class ServerGalleryAdapter(
    private val apiKey: String,
    private val username: String,
    private val onItemClick: (mediaIndex: Int) -> Unit,
) : ListAdapter<GalleryRow, RecyclerView.ViewHolder>(DIFF) {

    /** SHA-256 hashes of files the user already has on this device. */
    private var localHashes: Set<String> = emptySet()

    init { setHasStableIds(true) }

    fun setLocalHashes(hashes: Set<String>) {
        localHashes = hashes
        notifyItemRangeChanged(0, itemCount)
    }

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
            is GalleryRow.Photo -> (holder as PhotoHolder).bind(row, apiKey, username, localHashes)
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

        fun bind(row: GalleryRow.Photo, apiKey: String, username: String, localHashes: Set<String>) {
            itemView.setOnClickListener { onItemClick(row.mediaIndex) }

            val uriString = row.entry.item.uri.toString()
            val hash = FILE_HASH_RE.find(uriString)?.groupValues?.get(1)
            val onDevice = hash != null && hash in localHashes
            if (onDevice) {
                badge.setImageResource(R.drawable.ic_on_device)
                badge.visibility = View.VISIBLE
            } else {
                badge.visibility = View.GONE
            }

            val glideUrl = buildGlideUrl(uriString, apiKey, username)
            Glide.with(thumb).load(glideUrl).centerCrop().into(thumb)
        }
    }

    companion object {
        private const val TYPE_HEADER = 0
        private const val TYPE_PHOTO = 1

        fun buildGlideUrl(url: String, apiKey: String, username: String): GlideUrl {
            return if (apiKey.isNotEmpty() || username.isNotEmpty()) {
                val headers = LazyHeaders.Builder().apply {
                    if (apiKey.isNotEmpty()) addHeader("x-api-key", apiKey)
                    if (username.isNotEmpty()) addHeader(
                        "x-user", java.net.URLEncoder.encode(username, "UTF-8")
                    )
                }.build()
                GlideUrl(url, headers)
            } else {
                GlideUrl(url)
            }
        }

        private val DIFF = object : DiffUtil.ItemCallback<GalleryRow>() {
            override fun areItemsTheSame(oldItem: GalleryRow, newItem: GalleryRow) =
                oldItem.id == newItem.id
            override fun areContentsTheSame(oldItem: GalleryRow, newItem: GalleryRow) =
                oldItem == newItem
        }
    }
}
