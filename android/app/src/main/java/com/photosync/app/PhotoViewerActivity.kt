package com.photosync.app

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.view.View
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.viewpager2.widget.ViewPager2
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Full-screen, swipeable photo/video viewer (Google-Photos-style "open" view).
 * Videos play in-app with sound and controls; swiping to another page stops
 * playback. The media list is handed over via [GalleryData] to avoid
 * serializing it through the Intent. No sharing or editing — view only.
 */
@OptIn(UnstableApi::class)
class PhotoViewerActivity : AppCompatActivity() {

    private lateinit var pager: ViewPager2
    private lateinit var topBar: View
    private lateinit var dateText: TextView
    private lateinit var nameText: TextView
    private lateinit var downloadButton: ImageButton
    private lateinit var adapter: ViewerPagerAdapter
    private var player: ExoPlayer? = null

    private var items: List<MediaItem> = emptyList()
    private var chromeVisible = true
    private var downloadable = false

    private val headerFormat = SimpleDateFormat("EEE, MMM d, yyyy", Locale.getDefault())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_photo_viewer)

        items = GalleryData.items
        if (items.isEmpty()) {
            finish()
            return
        }
        val startIndex = intent.getIntExtra(EXTRA_INDEX, 0).coerceIn(0, items.size - 1)
        downloadable = intent.getBooleanExtra(EXTRA_DOWNLOADABLE, false)

        pager = findViewById(R.id.viewerPager)
        topBar = findViewById(R.id.viewerTopBar)
        dateText = findViewById(R.id.viewerDate)
        nameText = findViewById(R.id.viewerName)
        downloadButton = findViewById(R.id.viewerDownload)

        findViewById<View>(R.id.viewerBack).setOnClickListener { finish() }
        downloadButton.visibility = if (downloadable) View.VISIBLE else View.GONE
        downloadButton.setOnClickListener { downloadCurrent() }

        // When items are server URLs (http://), use a data source factory that
        // adds auth headers; local content (content://) is handled transparently.
        val prefs = SyncPrefs(this)
        val httpFactory = DefaultHttpDataSource.Factory().apply {
            val props = buildMap {
                if (prefs.apiKey.isNotEmpty()) put("x-api-key", prefs.apiKey)
                if (prefs.username.isNotEmpty()) put("x-user", URLEncoder.encode(prefs.username, "UTF-8"))
            }
            if (props.isNotEmpty()) setDefaultRequestProperties(props)
        }
        player = ExoPlayer.Builder(this)
            .setMediaSourceFactory(
                DefaultMediaSourceFactory(DefaultDataSource.Factory(this, httpFactory))
            )
            .build()
        adapter = ViewerPagerAdapter(items = items, player = player!!, onTap = ::toggleChrome)
        pager.adapter = adapter
        pager.setCurrentItem(startIndex, false)
        pager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                adapter.stopPlayback() // leaving a video stops it
                updateChrome(position)
            }
        })
        updateChrome(startIndex)
    }

    private fun updateChrome(position: Int) {
        val item = items.getOrNull(position) ?: return
        dateText.text = headerFormat.format(Date(item.takenAtMs))
        nameText.text = item.displayName
    }

    private fun toggleChrome() {
        chromeVisible = !chromeVisible
        topBar.animate().alpha(if (chromeVisible) 1f else 0f).setDuration(150).start()
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        if (chromeVisible) {
            controller.show(WindowInsetsCompat.Type.statusBars())
        } else {
            controller.hide(WindowInsetsCompat.Type.statusBars())
        }
    }

    private fun downloadCurrent() {
        val item = items.getOrNull(pager.currentItem) ?: return
        val uriString = item.uri.toString()
        if (!uriString.startsWith("http")) return
        val prefs = SyncPrefs(this)
        val request = DownloadManager.Request(Uri.parse(uriString)).apply {
            setTitle(item.displayName)
            setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            setDestinationInExternalPublicDir(
                Environment.DIRECTORY_PICTURES,
                "PhotoSync/${item.displayName}",
            )
            if (prefs.apiKey.isNotEmpty()) addRequestHeader("x-api-key", prefs.apiKey)
            if (prefs.username.isNotEmpty()) addRequestHeader(
                "x-user", URLEncoder.encode(prefs.username, "UTF-8")
            )
        }
        val dm = getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        dm.enqueue(request)
        Toast.makeText(this, R.string.downloading, Toast.LENGTH_SHORT).show()
    }

    override fun onStop() {
        super.onStop()
        if (::adapter.isInitialized) adapter.stopPlayback()
    }

    override fun onDestroy() {
        super.onDestroy()
        player?.release()
        player = null
    }

    companion object {
        const val EXTRA_INDEX = "index"
        /** Pass true when viewing server photos to show the download button. */
        const val EXTRA_DOWNLOADABLE = "downloadable"
    }
}
