package com.kos.player

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.audiofx.Equalizer
import android.media.audiofx.LoudnessEnhancer
import android.os.Build
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.roundToInt

class PlaybackService : MediaSessionService() {
    companion object {
        const val AUDIO_PREFS = "kos_audio_effect_settings"
        const val ACTION_APPLY_AUDIO_EFFECTS = "com.kos.player.APPLY_AUDIO_EFFECTS"
    }

    private var mediaSession: MediaSession? = null
    private var equalizer: Equalizer? = null
    private var loudnessEnhancer: LoudnessEnhancer? = null
    private var activeAudioSessionId = 0

    private val audioEffectsReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            applyAudioEffects()
        }
    }

    override fun onCreate() {
        super.onCreate()
        val player = ExoPlayer.Builder(this).build().apply {
            setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .build(),
                true
            )
            setHandleAudioBecomingNoisy(true)
            addListener(object : Player.Listener {
                override fun onAudioSessionIdChanged(audioSessionId: Int) {
                    attachAudioEffects(audioSessionId)
                }
            })
        }
        mediaSession = MediaSession.Builder(this, player).build()

        val filter = IntentFilter(ACTION_APPLY_AUDIO_EFFECTS)
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(audioEffectsReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            registerReceiver(audioEffectsReceiver, filter)
        }
        if (player.audioSessionId > 0) attachAudioEffects(player.audioSessionId)
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = mediaSession

    private fun attachAudioEffects(sessionId: Int) {
        if (sessionId <= 0 || sessionId == activeAudioSessionId) {
            applyAudioEffects()
            return
        }
        releaseAudioEffects()
        activeAudioSessionId = sessionId
        try {
            equalizer = Equalizer(0, sessionId).apply { enabled = true }
        } catch (_: Exception) {
            equalizer = null
        }
        try {
            loudnessEnhancer = LoudnessEnhancer(sessionId).apply { enabled = true }
        } catch (_: Exception) {
            loudnessEnhancer = null
        }
        applyAudioEffects()
    }

    private fun applyAudioEffects() {
        val prefs = getSharedPreferences(AUDIO_PREFS, MODE_PRIVATE)
        val amp = prefs.getFloat("amp", 1f).coerceIn(1f, 4f)
        try {
            loudnessEnhancer?.setTargetGain((2000.0 * ln(amp.toDouble()) / ln(10.0)).roundToInt().coerceIn(0, 1200))
        } catch (_: Exception) { }

        val eq = equalizer ?: return
        try {
            val bands = eq.numberOfBands.toInt()
            if (bands <= 0) return
            val range = eq.bandLevelRange
            val minLevel = range[0].toInt()
            val maxLevel = range[1].toInt()
            val bass = prefs.getFloat("bass", 3f).coerceIn(0f, 8f)
            val treble = prefs.getFloat("treble", 0f).coerceIn(-12f, 15f)
            val midrange = prefs.getFloat("midrange", 0f).coerceIn(-12f, 12f)
            val sub = prefs.getFloat("sub", 0f).coerceIn(0f, 12f)
            val subwoofer = prefs.getFloat("subwoofer", 0f).coerceIn(0f, 12f)
            val superTweeter = prefs.getFloat("supertweeter", 0f).coerceIn(0f, 12f)
            val manualCenters = intArrayOf(31, 62, 125, 250, 500, 1000, 2000, 4000, 8000, 16000)

            for (band in 0 until bands) {
                val frequency = (eq.getCenterFreq(band.toShort()) / 1000.0).coerceAtLeast(20.0)
                var gain = bass * gaussian(frequency, 90.0, 0.55)
                gain += sub * gaussian(frequency, 38.0, 0.48)
                gain += subwoofer * gaussian(frequency, 82.0, 0.48)
                gain += midrange * gaussian(frequency, 1000.0, 0.95)
                gain += treble * (ln(frequency / 2500.0) / ln(2.0)).coerceIn(0.0, 1.8)
                gain += superTweeter * gaussian(frequency, 12000.0, 0.48)
                for (i in manualCenters.indices) {
                    val manual = prefs.getFloat("eq_$i", 0f).coerceIn(-12f, 12f)
                    gain += manual * gaussian(frequency, manualCenters[i].toDouble(), 0.20)
                }
                val milliBel = (gain.coerceIn(-18.0, 18.0) * 100.0)
                    .roundToInt().coerceIn(minLevel, maxLevel)
                eq.setBandLevel(band.toShort(), milliBel.toShort())
            }
        } catch (_: Exception) {
            // Some OEM audio effects expose a reduced or vendor-specific band layout.
        }
    }

    private fun gaussian(frequency: Double, center: Double, widthOctaves: Double): Double {
        val distance = ln(frequency / center) / ln(2.0)
        return exp(-(distance * distance) / (2.0 * widthOctaves * widthOctaves))
    }

    private fun releaseAudioEffects() {
        try { equalizer?.release() } catch (_: Exception) { }
        try { loudnessEnhancer?.release() } catch (_: Exception) { }
        equalizer = null
        loudnessEnhancer = null
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        val player = mediaSession?.player
        if (player == null || !player.isPlaying) stopSelf()
    }

    override fun onDestroy() {
        try { unregisterReceiver(audioEffectsReceiver) } catch (_: Exception) { }
        releaseAudioEffects()
        activeAudioSessionId = 0
        mediaSession?.let { session ->
            session.player.release()
            session.release()
        }
        mediaSession = null
        super.onDestroy()
    }
}
