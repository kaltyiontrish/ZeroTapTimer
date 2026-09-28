package com.timergym.sound

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.timergym.data.Sound

/**
 * Plays the end-of-timer cues through AudioTrack. The waveform itself is [Cues]; this
 * class owns the hardware, the streaming buffer and the sample rate.
 *
 * Why not audio files: a handful of short cues are a few dozen lines of arithmetic each,
 * and generating them keeps the repository free of binary assets while staying tweakable.
 * A file-based implementation can replace this behind the same [play] signature later.
 *
 * The cues go out on the ALARM stream, so the phone's Alarm volume bar is the one that
 * governs them, and the in-app slider is a multiplier on top of that. The buffer is
 * rendered at the device's *native* output rate because asking for a rate the output
 * cannot take can stop AudioTrack initializing, which is a silent failure.
 */
class SoundPlayer {

    /**
     * Match the hardware. The cues go to the media stream, which AudioManager calls
     * STREAM_MUSIC. 44100 is only assumed when the device will not say, because asking
     * for a rate the output cannot take can stop AudioTrack initializing.
     */
    private val sampleRate: Int =
        runCatching { AudioTrack.getNativeOutputSampleRate(AudioManager.STREAM_MUSIC) }
            .getOrNull()
            ?.takeIf { it > 0 }
            ?: 44100

    private val main = Handler(Looper.getMainLooper())
    private val cache = HashMap<Sound, ShortArray>()
    private val live = ArrayList<AudioTrack>()

    fun play(sound: Sound, volume: Float) = playCue(sound, volume)

    fun release() {
        main.removeCallbacksAndMessages(null)
        live.toList().forEach { stop(it) }
        cache.clear()
    }

    private fun playCue(sound: Sound, volume: Float) {
        val samples = cache.getOrPut(sound) { Cues.render(sound, sampleRate) }
        if (samples.isEmpty()) {
            Log.w(TAG, "cue $sound rendered no samples")
            return
        }
        // A new cue replaces the old one, so a long tail is cut cleanly.
        live.toList().forEach { stop(it) }

        val bytes = ByteArray(samples.size * 2)
        for (i in samples.indices) {
            val v = samples[i].toInt()
            bytes[i * 2] = (v and 0xFF).toByte()
            bytes[i * 2 + 1] = ((v shr 8) and 0xFF).toByte()
        }

        val track = runCatching { buildTrack() }.getOrElse {
            Log.e(TAG, "could not build AudioTrack for $sound", it)
            return
        }
        if (track.state != AudioTrack.STATE_INITIALIZED) {
            Log.e(TAG, "AudioTrack not initialized for $sound (state=${track.state})")
            track.release()
            return
        }
        live += track
        // The platform's own per-track gain, so the slider costs no re-render and no copy
        // of the cached buffer. Fails quietly: a missing volume is far better than a
        // missing cue, and a silent failure here is invisible either way.
        runCatching { track.setVolume(volume.coerceIn(0f, 1f)) }
            .onFailure { Log.w(TAG, "setVolume ignored for $sound", it) }
        // MODE_STREAM: play() first, then push the buffer. MODE_STATIC looked tidier
        // but silently failed to play on some devices, which is unfixable without logs.
        runCatching {
            track.play()
            val written = track.write(bytes, 0, bytes.size)
            if (written < bytes.size) Log.w(TAG, "$sound short write $written/${bytes.size}")
        }.onFailure {
            Log.e(TAG, "playback failed for $sound", it)
            stop(track)
            return
        }
        val durationMs = samples.size * 1000L / sampleRate
        main.postDelayed({ stop(track) }, durationMs + 200L)
        Log.i(TAG, "playing $sound for ${durationMs}ms at ${sampleRate}Hz")
    }

    private fun buildTrack(): AudioTrack =
        AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    // USAGE_ALARM is what decides which of the phone's volume bars applies,
                    // and it is the biggest single lever on how loud the cue can get. The
                    // alarm stream is also the one least disturbed by media ducking and by
                    // notification interruption, and it is usually the bar left at maximum.
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(sampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setBufferSizeInBytes(STREAM_BUFFER_BYTES)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()

    private fun stop(track: AudioTrack?) {
        if (track == null) return
        live -= track
        runCatching { track.stop() }
        runCatching { track.release() }
    }

    private companion object {
        const val TAG = "SoundPlayer"
        /** Bigger than any cue we render, so one write never blocks. */
        const val STREAM_BUFFER_BYTES = 64 * 1024
    }
}
