package com.photosync.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.PlayerView
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Main screen: the device's photos as a Google-Photos-style date-sectioned
 * grid. A floating pill shows the date of the photos currently in view, video
 * tiles show their duration and preview while scrolling, each tile carries a
 * backup-status badge, and tapping opens the full-screen viewer.
 */
class MainActivity : AppCompatActivity() {

    private val SPAN_COUNT = 3

    private lateinit var prefs: SyncPrefs
    private lateinit var adapter: GalleryAdapter
    private lateinit var grid: RecyclerView
    private lateinit var layoutManager: GridLayoutManager
    private lateinit var swipe: SwipeRefreshLayout
    private lateinit var summaryText: TextView
    private lateinit var emptyText: TextView
    private lateinit var datePill: TextView
    private lateinit var previewController: VideoPreviewController

    private var items: List<MediaItem> = emptyList()
    private var rows: List<GalleryRow> = emptyList()

    private val pillHandler = Handler(Looper.getMainLooper())
    private val hidePill = Runnable {
        datePill.animate().alpha(0f).setDuration(400).start()
    }

    /** Briefly shows the floating date pill with [label], then fades it out. */
    private fun flashDatePill(label: String) {
        if (datePill.text != label) datePill.text = label
        datePill.animate().alpha(1f).setDuration(120).start()
        pillHandler.removeCallbacks(hidePill)
        pillHandler.postDelayed(hidePill, 1200)
    }

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
            if (grants.values.any { it }) {
                refresh()
            } else {
                emptyText.setText(R.string.permission_needed)
                emptyText.visibility = View.VISIBLE
            }
        }

    @OptIn(FlowPreview::class, UnstableApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        prefs = SyncPrefs(this)

        summaryText = findViewById(R.id.summaryText)
        emptyText = findViewById(R.id.emptyText)
        swipe = findViewById(R.id.swipeRefresh)
        datePill = findViewById(R.id.datePill)
        grid = findViewById(R.id.photoGrid)

        val previewPlayer: PlayerView = findViewById(R.id.previewPlayer)
        val previewChipContainer: View = findViewById(R.id.previewChipContainer)
        val previewDurationText: TextView = findViewById(R.id.previewDurationText)
        previewController = VideoPreviewController(
            previewPlayer, previewChipContainer, previewDurationText,
        ) { rows }
        previewController.onPreviewClick = ::openViewer

        adapter = GalleryAdapter(onPhotoClick = ::openViewer)
        layoutManager = GridLayoutManager(this, SPAN_COUNT).apply {
            spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
                override fun getSpanSize(position: Int): Int =
                    if (adapter.isHeader(position)) SPAN_COUNT else 1
            }
        }
        grid.layoutManager = layoutManager
        grid.adapter = adapter
        grid.addOnScrollListener(scrollListener)

        swipe.setOnRefreshListener {
            refresh()
            if (prefs.serverUrl.isNotEmpty()) SyncWorker.enqueueOneTime(this)
        }

        // Live updates while the worker runs: re-read statuses when the
        // upload log changes or the currently-uploading item moves on.
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                combine(SyncEvents.revision, SyncEvents.uploadingItemId) { _, uploading -> uploading }
                    .debounce(150)
                    .collect { applyStatuses() }
            }
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                SyncEvents.syncing.collect { syncing ->
                    if (!syncing) swipe.isRefreshing = false
                }
            }
        }

        ensureMediaPermission()
    }

    override fun onResume() {
        super.onResume()
        if (hasMediaPermission()) refresh()
    }

    override fun onStop() {
        super.onStop()
        previewController.onStop()
    }

    override fun onDestroy() {
        super.onDestroy()
        previewController.release()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_main, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_sync_now -> {
                when {
                    prefs.serverUrl.isEmpty() -> {
                        Toast.makeText(this, R.string.configure_server_first, Toast.LENGTH_LONG).show()
                        startActivity(Intent(this, SettingsActivity::class.java))
                    }
                    prefs.username.isEmpty() -> {
                        Toast.makeText(this, R.string.set_name_first, Toast.LENGTH_LONG).show()
                        startActivity(Intent(this, SettingsActivity::class.java))
                    }
                    else -> {
                        SyncWorker.enqueueOneTime(this)
                        Toast.makeText(this, R.string.sync_queued, Toast.LENGTH_SHORT).show()
                    }
                }
                true
            }
            R.id.action_settings -> {
                startActivity(Intent(this, SettingsActivity::class.java))
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    /** Hands the ordered media list to the viewer and opens it at [mediaIndex]. */
    private fun openViewer(mediaIndex: Int) {
        GalleryData.items = items
        startActivity(Intent(this, PhotoViewerActivity::class.java).apply {
            putExtra(PhotoViewerActivity.EXTRA_INDEX, mediaIndex)
        })
    }

    /** Re-scans MediaStore and the upload log, then rebuilds the grid. */
    private fun refresh() {
        lifecycleScope.launch {
            val scanned = withContext(Dispatchers.IO) {
                MediaScanner.itemsForBackup(this@MainActivity, prefs)
                    .sortedByDescending { it.takenAtMs }
            }
            items = scanned
            emptyText.visibility = if (scanned.isEmpty()) View.VISIBLE else View.GONE
            if (scanned.isEmpty()) emptyText.setText(R.string.gallery_empty)
            applyStatuses()
        }
    }

    /** Recomputes each item's badge, then groups into date sections. */
    private fun applyStatuses() {
        if (items.isEmpty() && !hasMediaPermission()) return
        lifecycleScope.launch {
            val uploaded = withContext(Dispatchers.IO) {
                UploadLog.get(this@MainActivity).uploadedIds()
            }
            val uploadingId = SyncEvents.uploadingItemId.value
            val entries = items.map { item ->
                val status = when {
                    item.id == uploadingId -> SyncStatus.UPLOADING
                    item.id in uploaded -> SyncStatus.DONE
                    else -> SyncStatus.PENDING
                }
                GalleryEntry(item, status)
            }
            rows = withContext(Dispatchers.Default) { GallerySections.build(entries) }
            adapter.submitList(rows)
            // Surface the most-recent date immediately (the inline headers are
            // gone, so without this the pill wouldn't show until the user scrolls).
            rows.firstOrNull()?.sectionLabel?.let { flashDatePill(it) }

            val doneCount = entries.count { it.status == SyncStatus.DONE }
            summaryText.text = getString(R.string.backed_up_summary, doneCount, entries.size)
        }
    }

    /** Drives the date pill and the video preview together. */
    private val scrollListener = object : RecyclerView.OnScrollListener() {
        override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
            val pos = layoutManager.findFirstVisibleItemPosition()
            val label = rows.getOrNull(pos)?.sectionLabel ?: return
            flashDatePill(label)
        }

        override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
            if (newState == RecyclerView.SCROLL_STATE_IDLE) {
                previewController.update(recyclerView)
            } else {
                previewController.pause()
            }
        }
    }

    private fun hasMediaPermission(): Boolean {
        val perm = if (Build.VERSION.SDK_INT >= 33) {
            Manifest.permission.READ_MEDIA_IMAGES
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }
        return ContextCompat.checkSelfPermission(this, perm) == PackageManager.PERMISSION_GRANTED
    }

    private fun ensureMediaPermission() {
        val needed = if (Build.VERSION.SDK_INT >= 33) {
            arrayOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO)
        } else {
            arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
        val missing = needed.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) {
            permissionLauncher.launch(missing.toTypedArray())
        }
    }
}
