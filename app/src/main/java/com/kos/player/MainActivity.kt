package com.kos.player

import android.Manifest
import android.content.ComponentName
import android.content.pm.PackageManager
import android.database.Cursor
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.view.Gravity
import android.view.View
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.MoreExecutors
import android.media.audiofx.Equalizer
import android.media.audiofx.BassBoost
import android.media.audiofx.PresetReverb
import java.util.concurrent.Future

data class Song(val id: Long, val title: String, val artist: String, val uri: Uri)

class MainActivity : AppCompatActivity() {
    private val cyan = Color.rgb(0,240,255)
    private val bg = Color.rgb(3,7,18)
    private val songs = mutableListOf<Song>()
    private val favorites = mutableSetOf<Long>()
    private lateinit var list: LinearLayout
    private lateinit var title: TextView
    private lateinit var artist: TextView
    private lateinit var play: Button
    private lateinit var seek: SeekBar
    private var controller: MediaController? = null
    private var controllerFuture: Future<MediaController>? = null
    private var current = -1
    private var equalizer: Equalizer? = null
    private var bass: BassBoost? = null
    private var reverb: PresetReverb? = null
    private var favoritesOnly = false
    private var shuffle = false
    private var repeat = false

    private val permission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        if (it) scanSongs() else Toast.makeText(this, "برای نمایش آهنگ‌ها مجوز دسترسی لازم است", Toast.LENGTH_LONG).show()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = bg
        window.navigationBarColor = bg
        buildUi()
        val token = SessionToken(this, ComponentName(this, PlaybackService::class.java))
        controllerFuture = MediaController.Builder(this, token).buildAsync()
        controllerFuture?.addListener({ controller = controllerFuture?.get(); controller?.addListener(object: androidx.media3.common.Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) { play.text = if (isPlaying) "Ⅱ" else "▶" }
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) { current = controller?.currentMediaItemIndex ?: -1; updateTrack() }
        }) }, MoreExecutors.directExecutor())
        requestAudioPermission()
    }

    private fun requestAudioPermission() {
        val p = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_AUDIO else Manifest.permission.READ_EXTERNAL_STORAGE
        if (ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED) scanSongs() else permission.launch(p)
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(bg); setPadding(18,18,18,12) }
        val header = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; orientation = LinearLayout.HORIZONTAL }
        header.addView(label("〰 KOS", 23, cyan, true), LinearLayout.LayoutParams(0, -2, 1f))
        header.addView(button("⚙") { showEqualizer() })
        root.addView(header)
        root.addView(label("پخش‌کننده موسیقی", 12, Color.LTGRAY, false).apply { setPadding(0,8,0,16) })
        val art = TextView(this).apply { text = "♫"; textSize = 72f; gravity = Gravity.CENTER; setTextColor(cyan); background = rounded(Color.rgb(13,22,35), 100); elevation = 12f }
        root.addView(art, LinearLayout.LayoutParams(170,170).apply { gravity = Gravity.CENTER; topMargin = 12; bottomMargin = 16 })
        title = label("فایلی انتخاب نشده", 19, Color.WHITE, true).apply { gravity = Gravity.CENTER }
        artist = label("یک آهنگ انتخاب کنید", 13, Color.LTGRAY, false).apply { gravity = Gravity.CENTER; setPadding(0,6,0,18) }
        root.addView(title); root.addView(artist)
        seek = SeekBar(this).apply { progressTintList = android.content.res.ColorStateList.valueOf(cyan); thumbTintList = android.content.res.ColorStateList.valueOf(cyan) }
        root.addView(seek)
        val controls = LinearLayout(this).apply { gravity = Gravity.CENTER; orientation = LinearLayout.HORIZONTAL }
        controls.addView(button("⏮") { previous() }); play = button("▶", 22) { toggle() }; controls.addView(play); controls.addView(button("⏭") { next() })
        root.addView(controls)
        val modes = LinearLayout(this).apply { gravity = Gravity.CENTER; orientation = LinearLayout.HORIZONTAL }
        modes.addView(button("🔀") { shuffle = !shuffle; toast(if(shuffle) "پخش تصادفی روشن" else "پخش تصادفی خاموش") })
        modes.addView(button("🔁") { repeat = !repeat; toast(if(repeat) "تکرار روشن" else "تکرار خاموش") })
        modes.addView(button("♥ علاقه‌مندی‌ها") { favoritesOnly = !favoritesOnly; renderList() })
        modes.addView(button("🚗") { showCarMode() })
        root.addView(modes)
        root.addView(label("فهرست آهنگ‌ها", 16, cyan, true).apply { setPadding(0,16,0,8) })
        val scroll = ScrollView(this)
        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scroll.addView(list)
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
        seek.setOnSeekBarChangeListener(object: SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, user: Boolean) { if(user) controller?.seekTo(p.toLong()) }
            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) {}
        })
    }

    private fun label(text: String, size: Int, color: Int, bold: Boolean) = TextView(this).apply {
        this.text = text; textSize = size.toFloat(); setTextColor(color)
        if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
    }
    private fun button(text: String, size: Int = 16, action: () -> Unit) = Button(this).apply {
        this.text = text; textSize = size.toFloat(); setTextColor(cyan)
        background = rounded(Color.rgb(15,25,39), 24)
        setOnClickListener { action() }
    }
    private fun rounded(color: Int, radius: Int) = android.graphics.drawable.GradientDrawable().apply {
        setColor(color); cornerRadius = radius.toFloat(); setStroke(1, Color.argb(90,0,240,255))
    }
    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

    private fun scanSongs() {
        songs.clear()
        val collection = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        val projection = arrayOf(MediaStore.Audio.Media._ID, MediaStore.Audio.Media.TITLE, MediaStore.Audio.Media.ARTIST)
        val selection = "${MediaStore.Audio.Media.IS_MUSIC} != 0"
        contentResolver.query(collection, projection, selection, null, "${MediaStore.Audio.Media.TITLE} COLLATE NOCASE ASC")?.use { c ->
            val id = c.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
            val t = c.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
            val a = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
            while(c.moveToNext()) songs.add(Song(c.getLong(id), c.getString(t) ?: "بدون نام", c.getString(a) ?: "ناشناس", Uri.withAppendedPath(collection, c.getLong(id).toString())))
        }
        renderList()
        if (songs.isEmpty()) toast("آهنگی در حافظه پیدا نشد")
    }

    private fun renderList() {
        list.removeAllViews()
        val shown = songs.filter { !favoritesOnly || favorites.contains(it.id) }
        if (shown.isEmpty()) { list.addView(label("آهنگی یافت نشد", 14, Color.LTGRAY, false).apply { gravity = Gravity.CENTER; setPadding(0,30,0,30) }); return }
        shown.forEach { song ->
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(12,10,12,10); background = rounded(Color.rgb(12,19,31), 18) }
            val info = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            info.addView(label(song.title, 15, Color.WHITE, true))
            info.addView(label(song.artist, 12, Color.LTGRAY, false).apply { setPadding(0,4,0,0) })
            row.addView(info, LinearLayout.LayoutParams(0,-2,1f))
            row.addView(button(if(favorites.contains(song.id)) "♥" else "♡") { if(!favorites.add(song.id)) favorites.remove(song.id); renderList() })
            row.setOnClickListener { playSong(song) }
            val lp = LinearLayout.LayoutParams(-1,-2); lp.bottomMargin = 8; list.addView(row, lp)
        }
    }

    private fun playSong(song: Song) {
        val c = controller ?: run { toast("پخش‌کننده در حال آماده‌سازی است"); return }
        current = songs.indexOf(song)
        val item = MediaItem.Builder().setUri(song.uri).setMediaMetadata(MediaMetadata.Builder().setTitle(song.title).setArtist(song.artist).build()).build()
        c.setMediaItem(item); c.prepare(); c.play()
        title.text = song.title; artist.text = song.artist
        val sessionId = c.audioSessionId
        if (sessionId != androidx.media3.common.C.AUDIO_SESSION_ID_UNSET) setupEffects(sessionId)
    }

    private fun setupEffects(sessionId: Int) {
        try {
            equalizer?.release(); bass?.release()
            equalizer = Equalizer(0, sessionId).apply { enabled = true }
            bass = BassBoost(0, sessionId).apply { setStrength(350); enabled = true }
        } catch (_: Exception) {}
    }

    private fun toggle() { controller?.let { if(it.isPlaying) it.pause() else it.play() } }
    private fun next() {
        if(songs.isEmpty()) return
        val idx = if(shuffle) (songs.indices).random() else (current + 1) % songs.size
        playSong(songs[idx])
    }
    private fun previous() { if(songs.isNotEmpty()) playSong(songs[if(current <= 0) songs.lastIndex else current - 1]) }

    private fun showEqualizer() {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(24,12,24,12) }
        box.addView(label("تنظیمات صدا و اکولایزر", 18, cyan, true))
        val bands = equalizer?.numberOfBands?.toInt() ?: 5
        if(equalizer == null) box.addView(label("ابتدا یک آهنگ پخش کنید تا تنظیمات صوتی فعال شود.", 13, Color.LTGRAY, false))
        else for(i in 0 until bands) {
            val range = equalizer!!.bandLevelRange
            val slider = SeekBar(this).apply { max = (range[1]-range[0]).toInt(); progress = (equalizer!!.getBandLevel(i.toShort()).toInt() - range[0]).coerceIn(0,max) }
            box.addView(label("باند ${i+1}", 12, Color.WHITE, false)); box.addView(slider)
            slider.setOnSeekBarChangeListener(object: SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, p: Int, user: Boolean) { if(user) try { equalizer?.setBandLevel(i.toShort(), (p + range[0]).toShort()) } catch (_: Exception) {} }
                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) {}
            })
        }
        box.addView(button("بازنشانی اکولایزر") { try { equalizer?.let { for(i in 0 until it.numberOfBands) it.setBandLevel(i.toShort(),0) } } catch (_:Exception) {} })
        box.addView(button("بستن") {})
        android.app.AlertDialog.Builder(this).setView(box).setPositiveButton("تمام") { d, _ -> d.dismiss() }.show()
    }

    private fun showCarMode() {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER; setPadding(24,24,24,24) }
        box.addView(label("حالت رانندگی", 24, cyan, true))
        box.addView(button("⏮ آهنگ قبلی", 22) { previous() })
        box.addView(button("▶ / Ⅱ پخش و توقف", 22) { toggle() })
        box.addView(button("⏭ آهنگ بعدی", 22) { next() })
        android.app.AlertDialog.Builder(this).setView(box).setPositiveButton("خروج") { d, _ -> d.dismiss() }.show()
    }

    private fun updateTrack() { controller?.currentMediaItem?.mediaMetadata?.let { title.text = it.title ?: "بدون نام"; artist.text = it.artist ?: "" } }
    override fun onDestroy() {
        controllerFuture?.let { MediaController.releaseFuture(it) }
        equalizer?.release(); bass?.release(); reverb?.release()
        super.onDestroy()
    }
}
