package com.timergym.sound

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.timergym.data.Sound
import java.util.concurrent.Executors

/**
 * Plays the end-of-timer cues, which are MP3s in `assets/sounds`.
 *
 * The platform decodes them ([MediaPlayer]) rather than this class synthesizing them, so
 * there is no waveform code to maintain and nothing to hand-tune.
 *
 * Playback happens on [audio], never the main thread. `MediaPlayer.prepare()` blocks while
 * it reads and decodes the file, and the tick loop that triggers a cue runs on
 * Dispatchers.Main; doing that inline froze the whole UI, countdown included, for as long
 * as the decode took.
 *
 * Cues are tagged USAGE_ALARM so the phone's Alarm volume bar governs them. An app can
 * never exceed the system volume, so that bar is the ceiling.
 */
class SoundPlayer(context: Context) {

    private val assets = context.applicationContext.assets

    /** Guards [live], which the audio thread and the main thread both touch. */
    private val lock = Any()
    private var live: MediaPlayer? = null

    private val main = Handler(Looper.getMainLooper())

    private val audio = Executors.newSingleThreadExecutor { r ->
        Thread(r, "cue-audio").apply { isDaemon = true; priority = Thread.NORM_PRIORITY - 1 }
    }

    /** Fire and forget: returns immediately and the cue plays on [audio]. */
    fun play(sound: Sound, volume: Float) {
        audio.execute { playNow(sound, volume) }
    }

    fun release() {
        main.removeCallbacksAndMessages(null)
        audio.shutdownNow()
        stopLive()
    }

    private fun playNow(sound: Sound, volume: Float) {
        // A new cue replaces the old one, so a long tail is cut cleanly.
        stopLive()
        val player = MediaPlayer()
        try {
            // openFd needs the asset stored uncompressed. MP3 is already compressed, and
            // aapt will not compress it a second time.
            assets.openFd(sound.asset).use { afd ->
                // Must be set before prepare(): this is what routes the cue to the alarm
                // stream rather than the media one.
                player.setAudioAttributes(AUDIO_ATTRS)
                player.setDataSource(afd.fileDescriptor, afd.startOffset, afd.length)
            }
            player.prepare()
            // Recorded as live before anything else can throw, so the catch below can
            // always release it.
            synchronized(lock) { live = player }
            val gain = volume.coerceIn(0f, 1f)
            player.setVolume(gain, gain)
            player.setOnCompletionListener { finish(player) }
            player.start()
            // Safety net. Normally completion fires and this is a no-op; without it, a cue
            // that never reported completion would leak a native player.
            main.postDelayed({ finish(player) }, RELEASE_AFTER_MS)
        } catch (e: Exception) {
            Log.e(TAG, "could not play ${sound.name} from ${sound.asset}", e)
            finish(player)
        }
    }

    /**
     * Release and forget. Safe to call repeatedly and from either thread: only the first
     * caller to find [live] pointing at this player does the release, so the completion
     * listener and the safety net cannot double-free.
     */
    private fun finish(player: MediaPlayer) {
        synchronized(lock) {
            if (live !== player) return
            live = null
        }
        runCatching { player.release() }
    }

    private fun stopLive() {
        val current = synchronized(lock) { live }
        if (current != null) finish(current)
    }

    private companion object {
        const val TAG = "SoundPlayer"

        /** Ceiling for the safety net. Every cue here is a couple of seconds at most. */
        const val RELEASE_AFTER_MS = 15_000L

        val AUDIO_ATTRS: AudioAttributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ALARM)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
    }
}