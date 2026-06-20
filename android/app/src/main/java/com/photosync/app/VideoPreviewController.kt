package com.photosync.app

import android.view.View
import android.widget.TextView
import androidx.media3.common.MediaItem as ExoMediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView

/**
 * Plays a muted, looping preview of the video tile that is most in view —
 * the way Google Photos previews videos while you browse. Uses ONE shared
 * ExoPlayer and ONE [PlayerView] overlay that gets moved/sized over the
 * active cell, rather than a player per cell.
 */
@OptIn(UnstableApi::class)
class VideoPreviewController(
    private val overlay: PlayerView,
    private val chipContainer: View,
    private val chipDurationText: TextView,
    private val rowsProvider: () -> List<GalleryRow>,
) {
    private var player: ExoPlayer? = null
    private var activePosition = RecyclerView.NO_POSITION
    private var activeMediaIndex = -1

    /** Invoked with the media index when the user taps the live preview. */
    var onPreviewClick: ((mediaIndex: Int) -> Unit)? = null

    private fun ensurePlayer(): ExoPlayer {
        return player ?: ExoPlayer.Builder(overlay.context).build().also {
            it.volume = 0f
            it.repeatMode = Player.REPEAT_MODE_ONE
            overlay.player = it
            overlay.useController = false
            overlay.resizeMode = AspectRatioFrameLayout.RESIZE_MODE_ZOOM
            overlay.setOnClickListener {
                if (activeMediaIndex >= 0) onPreviewClick?.invoke(activeMediaIndex)
            }
            player = it
        }
    }

    /** Hides the preview (called while actively scrolling). */
    fun pause() {
        player?.playWhenReady = false
        overlay.visibility = View.GONE
        chipContainer.visibility = View.GONE
        activePosition = RecyclerView.NO_POSITION
    }

    /** Picks the most-visible video cell and previews it. Call when scrolling settles. */
    fun update(recyclerView: RecyclerView) {
        val lm = recyclerView.layoutManager as? GridLayoutManager ?: return
        val rows = rowsProvider()
        val first = lm.findFirstVisibleItemPosition()
        val last = lm.findLastVisibleItemPosition()
        if (first == RecyclerView.NO_POSITION) {
            pause()
            return
        }

        val centerY = recyclerView.height / 2f
        var bestPos = RecyclerView.NO_POSITION
        var bestView: View? = null
        var bestDistance = Float.MAX_VALUE

        for (pos in first..last) {
            val row = rows.getOrNull(pos) as? GalleryRow.Photo ?: continue
            if (!row.entry.item.isVideo) continue
            val holder = recyclerView.findViewHolderForAdapterPosition(pos) ?: continue
            val view = holder.itemView
            // Require the cell to be mostly on screen before previewing it.
            val top = view.y
            val bottom = top + view.height
            val visible = minOf(bottom, recyclerView.height.toFloat()) - maxOf(top, 0f)
            if (visible < view.height * 0.6f) continue
            val cellCenter = top + view.height / 2f
            val distance = kotlin.math.abs(cellCenter - centerY)
            if (distance < bestDistance) {
                bestDistance = distance
                bestPos = pos
                bestView = view
            }
        }

        if (bestPos == RecyclerView.NO_POSITION || bestView == null) {
            pause()
            return
        }

        val view = bestView
        val activeRow = rows[bestPos] as GalleryRow.Photo
        val item = activeRow.entry.item
        activeMediaIndex = activeRow.mediaIndex

        // Position/size the overlay exactly over the chosen cell.
        val params = overlay.layoutParams
        params.width = view.width
        params.height = view.height
        overlay.layoutParams = params
        overlay.translationX = view.x
        overlay.translationY = view.y
        overlay.visibility = View.VISIBLE

        // Duration chip on top of the preview, matching the cell's own chip.
        val chipParams = chipContainer.layoutParams
        chipParams.width = view.width
        chipParams.height = view.height
        chipContainer.layoutParams = chipParams
        chipContainer.translationX = view.x
        chipContainer.translationY = view.y
        chipDurationText.text = formatVideoDuration(item.durationMs)
        chipContainer.visibility = View.VISIBLE

        val exo = ensurePlayer()
        if (bestPos != activePosition) {
            activePosition = bestPos
            exo.setMediaItem(ExoMediaItem.fromUri(item.uri))
            exo.prepare()
        }
        exo.playWhenReady = true
    }

    fun onStop() {
        player?.playWhenReady = false
    }

    fun release() {
        overlay.player = null
        player?.release()
        player = null
        activePosition = RecyclerView.NO_POSITION
    }
}
