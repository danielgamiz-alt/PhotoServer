package com.photosync.app

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.URLEncoder

/**
 * Shows all photos and videos stored on the PhotoSync server for the current
 * user. Thumbnails stream directly from the server via Glide (authenticated).
 * Tapping a photo opens the full-screen viewer; the viewer's download button
 * saves the item back to the device's Pictures folder.
 */
class ServerGalleryActivity : AppCompatActivity() {

    private val SPAN_COUNT = 3

    private lateinit var prefs: SyncPrefs
    private lateinit var adapter: ServerGalleryAdapter
    private lateinit var grid: RecyclerView
    private lateinit var layoutManager: GridLayoutManager
    private lateinit var swipe: SwipeRefreshLayout
    private lateinit var emptyText: TextView
    private lateinit var datePill: TextView

    private var items: List<MediaItem> = emptyList()
    private var rows: List<GalleryRow> = emptyList()

    private val pillHandler = Handler(Looper.getMainLooper())
    private val hidePill = Runnable {
        datePill.animate().alpha(0f).setDuration(400).start()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_server_gallery)
        prefs = SyncPrefs(this)

        val toolbar = findViewById<com.google.android.material.appbar.MaterialToolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)
        toolbar.setNavigationOnClickListener { finish() }

        emptyText = findViewById(R.id.emptyText)
        swipe = findViewById(R.id.swipeRefresh)
        datePill = findViewById(R.id.datePill)
        grid = findViewById(R.id.photoGrid)

        adapter = ServerGalleryAdapter(
            apiKey = prefs.apiKey,
            username = prefs.username,
            onItemClick = ::openViewer,
        )
        layoutManager = GridLayoutManager(this, SPAN_COUNT).apply {
            spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
                override fun getSpanSize(position: Int): Int =
                    if (adapter.isHeader(position)) SPAN_COUNT else 1
            }
        }
        grid.layoutManager = layoutManager
        grid.adapter = adapter
        grid.addOnScrollListener(scrollListener)

        swipe.setOnRefreshListener { load() }

        load()
    }

    private fun load() {
        if (prefs.serverUrl.isEmpty()) {
            Toast.makeText(this, R.string.server_not_configured, Toast.LENGTH_LONG).show()
            finish()
            return
        }
        swipe.isRefreshing = true
        lifecycleScope.launch {
            try {
                val api = ServerApi(prefs.serverUrl, prefs.apiKey, prefs.username)
                val serverItems = withContext(Dispatchers.IO) { api.listGallery() }
                val mediaItems = serverItems.map { item ->
                    MediaItem(
                        id = item.hash.hashCode().toLong(),
                        uri = Uri.parse(api.fileUrl(item.hash)),
                        displayName = item.name,
                        sizeBytes = item.size,
                        dateAddedSec = item.takenAt / 1000,
                        takenAtMs = item.takenAt,
                        isVideo = item.type == "video",
                    )
                }
                items = mediaItems
                val entries = mediaItems.map { GalleryEntry(it, SyncStatus.DONE) }
                rows = withContext(Dispatchers.Default) { GallerySections.build(entries) }
                adapter.submitList(rows)
                emptyText.visibility = if (mediaItems.isEmpty()) View.VISIBLE else View.GONE
                rows.firstOrNull()?.sectionLabel?.let { flashDatePill(it) }
            } catch (e: Exception) {
                val msg = getString(R.string.server_gallery_error, e.message ?: "unknown error")
                Toast.makeText(this@ServerGalleryActivity, msg, Toast.LENGTH_LONG).show()
            } finally {
                swipe.isRefreshing = false
            }
        }
    }

    private fun openViewer(mediaIndex: Int) {
        GalleryData.items = items
        startActivity(Intent(this, PhotoViewerActivity::class.java).apply {
            putExtra(PhotoViewerActivity.EXTRA_INDEX, mediaIndex)
            putExtra(PhotoViewerActivity.EXTRA_DOWNLOADABLE, true)
        })
    }

    private fun flashDatePill(label: String) {
        if (datePill.text != label) datePill.text = label
        datePill.animate().alpha(1f).setDuration(120).start()
        pillHandler.removeCallbacks(hidePill)
        pillHandler.postDelayed(hidePill, 1200)
    }

    private val scrollListener = object : RecyclerView.OnScrollListener() {
        override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
            val pos = layoutManager.findFirstVisibleItemPosition()
            val label = rows.getOrNull(pos)?.sectionLabel ?: return
            flashDatePill(label)
        }
    }
}
