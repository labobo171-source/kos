package com.kos.player

import android.Manifest
import android.content.ComponentName
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {
    private lateinit var webView: WebView
    private var controller: MediaController? = null
    private var controllerFuture: ListenableFuture<MediaController>? = null
    private val handler = Handler(Looper.getMainLooper())

    @Volatile private var cachedPosition = 0L
    @Volatile private var cachedDuration = 0L
    @Volatile private var cachedPlaying = false

    private val ticker = object : Runnable {
        override fun run() {
            controller?.let { player ->
                cachedPosition = player.currentPosition.coerceAtLeast(0L)
                cachedDuration = player.duration.takeIf { it >= 0L } ?: 0L
                cachedPlaying = player.isPlaying
            }
            if (::webView.isInitialized && !isFinishing && !isDestroyed) {
                handler.postDelayed(this, 250L)
            }
        }
    }

    private val playerListener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            cachedPlaying = isPlaying
            notifyWeb("onNativePlaybackState($isPlaying)")
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            controller?.let { cachedDuration = it.duration.takeIf { d -> d >= 0L } ?: 0L }
            if (playbackState == Player.STATE_READY) notifyWeb("onNativeDuration($cachedDuration)")
            if (playbackState == Player.STATE_ENDED) notifyWeb("onNativeTrackEnded()")
        }

        override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
            notifyWeb("onNativePlaybackError()")
        }
    }

    private val audioPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (!granted) Toast.makeText(this, "برای نمایش آهنگ‌ها مجوز دسترسی لازم است", Toast.LENGTH_LONG).show()
        loadPlayer()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = android.graphics.Color.rgb(3, 7, 18)
        window.navigationBarColor = android.graphics.Color.rgb(3, 7, 18)

        webView = WebView(this)
        webView.setBackgroundColor(android.graphics.Color.rgb(3, 7, 18))
        webView.webChromeClient = WebChromeClient()
        webView.webViewClient = WebViewClient()
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = false
            allowFileAccess = true
            allowContentAccess = true
            mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
        }
        webView.addJavascriptInterface(AndroidBridge(), "AndroidBridge")
        setContentView(webView)

        connectPlaybackService()
        handler.post(ticker)

        val permission = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_AUDIO else Manifest.permission.READ_EXTERNAL_STORAGE
        if (ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED) loadPlayer()
        else audioPermission.launch(permission)
    }

    private fun connectPlaybackService() {
        val token = SessionToken(this, ComponentName(this, PlaybackService::class.java))
        controllerFuture = MediaController.Builder(this, token).buildAsync()
        controllerFuture?.addListener({
            try {
                val connected = controllerFuture?.get() ?: return@addListener
                if (isFinishing || isDestroyed) {
                    connected.release()
                    return@addListener
                }
                controller = connected
                connected.addListener(playerListener)
                cachedPosition = connected.currentPosition.coerceAtLeast(0L)
                cachedDuration = connected.duration.takeIf { it >= 0L } ?: 0L
                cachedPlaying = connected.isPlaying
            } catch (error: Exception) {
                notifyWeb("onNativePlaybackError()")
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun notifyWeb(script: String) {
        if (::webView.isInitialized) runOnUiThread {
            if (!isFinishing && !isDestroyed) webView.evaluateJavascript(script, null)
        }
    }

    private fun loadPlayer() {
        webView.loadUrl("file:///android_asset/index.html")
    }

    inner class AndroidBridge {
        @JavascriptInterface
        fun getSongsJson(): String {
            val result = JSONArray()
            val collection = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
            val projection = arrayOf(MediaStore.Audio.Media._ID, MediaStore.Audio.Media.TITLE, MediaStore.Audio.Media.ARTIST, MediaStore.Audio.Media.SIZE, MediaStore.Audio.Media.DATE_ADDED)
            val selection = "${MediaStore.Audio.Media.IS_MUSIC} != 0"
            contentResolver.query(collection, projection, selection, null, "${MediaStore.Audio.Media.TITLE} COLLATE NOCASE ASC")?.use { cursor ->
                val idCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
                val titleCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
                val artistCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
                val sizeCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.SIZE)
                val dateCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DATE_ADDED)
                while (cursor.moveToNext()) {
                    val id = cursor.getLong(idCol)
                    val sizeBytes = cursor.getLong(sizeCol)
                    val dateMs = cursor.getLong(dateCol) * 1000L
                    result.put(JSONObject().apply {
                        put("id", id)
                        put("nativeId", id)
                        put("title", cursor.getString(titleCol) ?: "بدون نام")
                        put("artist", cursor.getString(artistCol) ?: "ناشناس")
                        put("sizeBytes", sizeBytes)
                        put("size", String.format(Locale.US, "%.1f MB", sizeBytes / 1048576.0))
                        put("dateMs", dateMs)
                        put("date", SimpleDateFormat("yyyy/MM/dd", Locale.getDefault()).format(Date(dateMs)))
                        put("url", "$collection/$id")
                    })
                }
            }
            return result.toString()
        }

        @JavascriptInterface
        fun playTrack(uri: String, title: String, artist: String, queueJson: String, startIndex: Int) {
            runOnUiThread {
                try {
                    val queue = JSONArray(queueJson)
                    val items = ArrayList<MediaItem>(queue.length())
                    for (index in 0 until queue.length()) {
                        val entry = queue.getJSONObject(index)
                        val metadata = MediaMetadata.Builder()
                            .setTitle(entry.optString("title"))
                            .setArtist(entry.optString("artist"))
                            .build()
                        items.add(
                            MediaItem.Builder()
                                .setMediaId(entry.optString("id"))
                                .setUri(entry.getString("url"))
                                .setMediaMetadata(metadata)
                                .build()
                        )
                    }
                    controller?.apply {
                        setMediaItems(items, startIndex.coerceIn(0, (items.size - 1).coerceAtLeast(0)), 0L)
                        prepare()
                        play()
                    } ?: notifyWeb("onNativePlaybackError()")
                } catch (error: Exception) {
                    notifyWeb("onNativePlaybackError()")
                }
            }
        }

        @JavascriptInterface fun pause() { runOnUiThread { controller?.pause() } }
        @JavascriptInterface fun resume() { runOnUiThread { controller?.play() } }
        @JavascriptInterface fun seekTo(positionMs: Long) { runOnUiThread { controller?.seekTo(positionMs.coerceAtLeast(0L)) } }
        @JavascriptInterface fun setVolume(value: Float) { runOnUiThread { controller?.volume = value.coerceIn(0f, 1f) } }

        // Return cached values: never block the WebView bridge thread waiting for the UI thread.
        @JavascriptInterface fun getPosition(): Long = cachedPosition
        @JavascriptInterface fun getDuration(): Long = cachedDuration
        @JavascriptInterface fun isPlaying(): Boolean = cachedPlaying
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        webView.evaluateJavascript("document.getElementById('car-mode-overlay').classList.remove('active');document.getElementById('audio-sheet').classList.remove('open');", null)
        super.onBackPressed()
    }

    override fun onDestroy() {
        handler.removeCallbacks(ticker)
        controller?.removeListener(playerListener)
        controller?.release()
        controller = null
        controllerFuture = null
        if (::webView.isInitialized) {
            webView.removeJavascriptInterface("AndroidBridge")
            webView.destroy()
        }
        super.onDestroy()
    }
}
