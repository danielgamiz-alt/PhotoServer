package com.photosync.app

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem as ExoMediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide

/**
 * One full-screen page per media item for the [PhotoViewerActivity] pager.
 * Photos load full-resolution into a [ZoomableImageView]; videos show a frame
 * with a play button and play in-app (with sound + controls) via the shared
 * [player] when tapped.
 */
@OptIn(UnstableApi::class)
class ViewerPagerAdapter(
    private val items: List<MediaItem>,
    private val player: ExoPlayer,
    private val onTap: () -> Unit,
) : RecyclerView.Adapter<ViewerPagerAdapter.PageHolder>() {

    private var playingHolder: PageHolder? = null

    override fun getItemCount(): Int = items.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PageHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_viewer_page, parent, false)
        return PageHolder(view)
    }

    override fun onBindViewHolder(holder: PageHolder, position: Int) {
        holder.bind(items[position])
    }

    override fun onViewRecycled(holder: PageHolder) {
        if (holder == playingHolder) stopPlayback()
    }

    /** Stops any in-progress playback and restores the frame + play button. */
    fun stopPlayback() {
        val holder = playingHolder ?: return
        player.stop()
        player.clearMediaItems()
        holder.videoPlayer.player = null
        holder.videoPlayer.visibility = View.GONE
        if (holder.isVideo) {
            holder.playButton.visibility = View.VISIBLE
            holder.fullImage.visibility = View.VISIBLE
        }
        playingHolder = null
    }

    private fun startPlayback(holder: PageHolder, item: MediaItem) {
        stopPlayback()
        playingHolder = holder
        holder.videoPlayer.player = player
        holder.videoPlayer.useController = true
        holder.videoPlayer.visibility = View.VISIBLE
        holder.playButton.visibility = View.GONE
        holder.fullImage.visibility = View.GONE
        player.setMediaItem(ExoMediaItem.fromUri(item.uri))
        player.prepare()
        player.playWhenReady = true
    }

    inner class PageHolder(view: View) : RecyclerView.ViewHolder(view) {
        val fullImage: ZoomableImageView = view.findViewById(R.id.fullImage)
        val videoPlayer: PlayerView = view.findViewById(R.id.videoPlayer)
        val playButton: ImageView = view.findViewById(R.id.playButton)
        var isVideo: Boolean = false

        fun bind(item: MediaItem) {
            isVideo = item.isVideo
            // Reset reused page to the frame/photo state.
            videoPlayer.player = null
            videoPlayer.visibility = View.GONE
            fullImage.visibility = View.VISIBLE

            Glide.with(fullImage).load(item.uri).fitCenter().into(fullImage)
            fullImage.setOnClickListener { onTap() }

            if (item.isVideo) {
                playButton.visibility = View.VISIBLE
                playButton.setOnClickListener { startPlayback(this, item) }
            } else {
                playButton.visibility = View.GONE
                playButton.setOnClickListener(null)
            }
        }
    }
}
