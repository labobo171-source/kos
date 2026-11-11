package com.kos.player

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
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
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class MainActivity : AppCompatActivity() {
    private lateinit var webView: WebView
    private lateinit var player: ExoPlayer

    private val audioPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (!granted) Toast.makeText(this, "برای نمایش آهنگ‌ها مجوز دسترسی لازم است", Toast.LENGTH_LONG).show()
        loadPlayer()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = android.graphics.Color.rgb(3, 7, 18)
        window.navigationBarColor = android.graphics.Color.rgb(3, 7, 18)

        player = ExoPlayer.Builder(this).build().apply {
            setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .build(),
                true
            )
            addListener(object : Player.Listener {
                override fun onIsPlayingChanged(isPlaying: Boolean) {
                    notifyWeb("onNativePlaybackState($isPlaying)")
                }

                override fun onPlaybackStateChanged(playbackState: Int) {
                    if (playbackState == Player.STATE_READY) {
                        notifyWeb("onNativeDuration(${player.duration.coerceAtLeast(0)})")
                    } else if (playbackState == Player.STATE_ENDED) {
                        notifyWeb("onNativeTrackEnded()")
                    }
                }

                override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                    notifyWeb("onNativePlaybackError()")
                }
            })
        }

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

        val permission = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_AUDIO else Manifest.permission.READ_EXTERNAL_STORAGE
        if (ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED) loadPlayer()
        else audioPermission.launch(permission)
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
        fun playTrack(uri: String) {
            runOnUiThread {
                try {
                    player.setMediaItem(MediaItem.fromUri(Uri.parse(uri)))
                    player.prepare()
                    player.play()
                } catch (e: Exception) {
                    notifyWeb("onNativePlaybackError()")
                }
            }
        }

        @JavascriptInterface
        fun pause() { runOnUiThread { player.pause() } }

        @JavascriptInterface
        fun resume() { runOnUiThread { player.play() } }

        @JavascriptInterface
        fun seekTo(positionMs: Long) { runOnUiThread { player.seekTo(positionMs.coerceAtLeast(0)) } }

        @JavascriptInterface
        fun setVolume(value: Float) { runOnUiThread { player.volume = value.coerceIn(0f, 1f) } }

        @JavascriptInterface
        fun getPosition(): Long {
            var value = 0L
            val latch = CountDownLatch(1)
            runOnUiThread { value = player.currentPosition.coerceAtLeast(0); latch.countDown() }
            latch.await(2, TimeUnit.SECONDS)
            return value
        }

        @JavascriptInterface
        fun getDuration(): Long {
            var value = 0L
            val latch = CountDownLatch(1)
            runOnUiThread { value = player.duration.coerceAtLeast(0); latch.countDown() }
            latch.await(2, TimeUnit.SECONDS)
            return value
        }

        @JavascriptInterface
        fun isPlaying(): Boolean {
            var value = false
            val latch = CountDownLatch(1)
            runOnUiThread { value = player.isPlaying; latch.countDown() }
            latch.await(2, TimeUnit.SECONDS)
            return value
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        webView.evaluateJavascript("document.getElementById('car-mode-overlay').classList.remove('active');document.getElementById('audio-sheet').classList.remove('open');", null)
        super.onBackPressed()
    }

    override fun onDestroy() {
        if (::webView.isInitialized) {
            webView.removeJavascriptInterface("AndroidBridge")
            webView.destroy()
        }
        if (::player.isInitialized) player.release()
        super.onDestroy()
    }
}
