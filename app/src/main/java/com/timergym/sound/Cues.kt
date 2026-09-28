package com.timergym.sound

import com.timergym.data.Sound
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.min
import kotlin.math.sin

/**
 * Renders the eight end-of-timer cues to 16-bit mono PCM.
 *
 * Pure arithmetic with no Android imports, so it unit-tests on the JVM the same way
 * [com.timergym.timer.TimerEngine] does. [SoundPlayer] owns the AudioTrack; this owns
 * the waveform, which means a cue can be rendered and inspected without a device.
 *
 * Three things separate a struck object from a beep, and all three were wrong before:
 *
 *  - **Nothing may start or stop abruptly.** Every note is cut at its [Note.durMs], and
 *    a wave cut mid-cycle is a step, which is a click. Each note therefore gets a short
 *    attack ramp and a release ramp, so it always begins and ends at zero.
 *  - **Decay is absolute.** The envelope is `exp(-t / tau)` against real milliseconds.
 *    Scaling it by the note length instead made the same `tau` mean something different
 *    for a 130ms blip and a 2s gong.
 *  - **Higher partials die faster.** That is what a struck bar does, and it is why a bell
 *    goes from bright to hollow instead of just getting quieter.
 *
 * There is no way to check pleasantness in a unit test. See `CuesTest` for the properties
 * that are mechanical.
 */
object Cues {

    /** Attack ramp. A waveform jumping straight to full amplitude on its first sample clicks. */
    private const val ATTACK_MS = 3.0

    /**
     * Release ramp, 18ms. This is what kills the truncation click: the note is already
     * fading here, so where it gets cut no longer matters.
     */
    private const val RELEASE_MS = 18.0

    /** Ceiling after normalization. Below 1.0 so the limiter never has to act. */
    private const val PEAK = 0.89

    private enum class Wave { SINE, REED }

    private data class Note(
        val freq: Double,
        val startMs: Int,
        val durMs: Int,
        val wave: Wave,
        val amp: Double = 0.55,
        /** Decay time constant, in real milliseconds. */
        val tauMs: Double = 300.0,
    )

    private data class Tone(
        val durationMs: Int,
        val notes: List<Note>,
        /**
         * Relative loudness. A wood block is a background nudge and a horn is meant to
         * carry across a room, so normalizing every cue to the same peak is wrong.
         */
        val gain: Double = 1.0,
    )

    /**
     * A struck partial. Ratios above 1 are overtones of [fund]; 2.76 and 5.40 are the
     * inharmonic partials a real bell has, which is what stops it sounding like an organ.
     */
    private fun strike(
        fund: Double,
        startMs: Int,
        durMs: Int,
        tauMs: Double,
        partials: List<Pair<Double, Double>>,
    ): List<Note> = partials.map { (ratio, amp) ->
        Note(fund * ratio, startMs, durMs, Wave.SINE, amp, tauMs / (0.6 + 0.4 * ratio))
    }

    private fun tone(sound: Sound): Tone = when (sound) {

        // Chime: a bright tubular bell. Long-ish tail, bright attack.
        Sound.BELL -> Tone(1400, strike(
            fund = 783.99, startMs = 0, durMs = 1300, tauMs = 400.0,
            partials = listOf(
                1.0 to 0.50, 2.0 to 0.26, 2.76 to 0.22, 4.07 to 0.10, 5.40 to 0.07, 8.2 to 0.03,
            ),
        ), gain = 0.95)

        // Gong: low, and long enough to feel like it. Two seconds, not three.
        Sound.GONG -> Tone(2100, strike(
            fund = 155.56, startMs = 0, durMs = 2000, tauMs = 1100.0,
            partials = listOf(
                1.0 to 0.55, 1.48 to 0.22, 2.0 to 0.20, 2.76 to 0.12, 3.42 to 0.07, 4.90 to 0.04,
            ),
        ), gain = 1.0)

        // Bleep: two short clean blips. The second harmonic is what makes it an electronic
        // tone rather than a whistle.
        Sound.BEEP -> Tone(
            800,
            strike(1046.5, 0, 130, 55.0, listOf(1.0 to 0.60, 2.0 to 0.18, 3.0 to 0.07)) +
                strike(1046.5, 240, 130, 55.0, listOf(1.0 to 0.60, 2.0 to 0.18, 3.0 to 0.07)),
            gain = 0.85,
        )

        // Double ping: the same voice twice, the second hit longer so the pair resolves
        // rather than sounding like one hit and its echo.
        Sound.DOUBLE_PING -> Tone(
            1000,
            strike(1567.98, 0, 620, 260.0, listOf(1.0 to 0.55, 2.0 to 0.12, 3.5 to 0.05)) +
                strike(1567.98, 300, 700, 300.0, listOf(1.0 to 0.55, 2.0 to 0.12, 3.5 to 0.05)),
            gain = 0.90,
        )

        // Rise: a marimba run up a major arpeggio. Marimba has a strong third and fourth
        // partial sitting on top of a plain fundamental, which is what the list below is.
        Sound.ARPEGGIO -> Tone(1150, listOf(
            Note(523.25, 0, 420, Wave.SINE, 0.42, 230.0),
            Note(659.25, 140, 420, Wave.SINE, 0.42, 230.0),
            Note(783.99, 280, 420, Wave.SINE, 0.42, 230.0),
            Note(1046.5, 420, 700, Wave.SINE, 0.46, 330.0),
        ), gain = 0.90)

        // Fall: the same shape in reverse, ending low.
        Sound.DESCENDING -> Tone(1250, listOf(
            Note(1046.5, 0, 330, Wave.SINE, 0.44, 230.0),
            Note(783.99, 170, 330, Wave.SINE, 0.44, 230.0),
            Note(659.25, 340, 330, Wave.SINE, 0.44, 230.0),
            Note(392.0, 510, 700, Wave.SINE, 0.50, 340.0),
        ), gain = 0.90)

        // Wood block: dry and short, which is the character. The pitch is deliberately
        // kept where a phone speaker can actually reproduce it — small speakers roll off
        // above roughly 3kHz, and the brighter partials this used to carry were inaudible
        // on one no matter how loud the gain. The decay is a real ring-out rather than a
        // tick, or 200ms of near-silence registers as "no sound at all".
        Sound.WOOD_BLOCK -> Tone(340, strike(
            fund = 880.0, startMs = 0, durMs = 260, tauMs = 130.0,
            partials = listOf(1.0 to 0.60, 2.4 to 0.22, 3.4 to 0.12),
        ), gain = 0.95)

        // Horn: alternating two-tone, the classic "time" call, and the loudest thing here.
        // On REED rather than a raw square â€” a square built from `sin(x) >= 0` keeps every
        // harmonic up to Nyquist and folds them all back down the spectrum, and that
        // aliasing is most of why it sounds like a fire alarm instead of a horn.
        Sound.BUZZER -> Tone(2000, listOf(
            Note(233.08, 0, 400, Wave.REED, 0.34, 1600.0),
            Note(277.18, 400, 400, Wave.REED, 0.34, 1600.0),
            Note(233.08, 800, 400, Wave.REED, 0.34, 1600.0),
            Note(277.18, 1200, 480, Wave.REED, 0.36, 1600.0),
        ), gain = 0.95)
    }

    /**
     * Render [sound] at [sampleRate]. The buffer is normalized to the cue's own loudness,
     * so the wood block sits below the horn and neither is in danger of clipping.
     */
    fun render(sound: Sound, sampleRate: Int): ShortArray {
        if (sampleRate <= 0) return ShortArray(0)
        val tone = tone(sound)
        val n = tone.durationMs * sampleRate / 1000
        if (n <= 0) return ShortArray(0)
        val buf = FloatArray(n)
        for (note in tone.notes) mix(buf, note, sampleRate)

        var peak = 0f
        for (s in buf) peak = maxOf(peak, abs(s))
        if (peak <= 0f) return ShortArray(0)

        val norm = (PEAK * tone.gain / peak).toFloat()
        val out = ShortArray(n)
        for (i in 0 until n) {
            out[i] = (buf[i] * norm * 32767f).toInt().coerceIn(-32768, 32767).toShort()
        }
        return out
    }

    private fun mix(buf: FloatArray, note: Note, sampleRate: Int) {
        val start = note.startMs * sampleRate / 1000
        val len = note.durMs * sampleRate / 1000
        if (len <= 0) return
        val attack = min(ATTACK_MS * sampleRate / 1000.0, len / 2.0).toInt()
        val release = min(RELEASE_MS * sampleRate / 1000.0, len / 2.0).toInt()
        for (i in 0 until len) {
            val idx = start + i
            if (idx >= buf.size) break
            val tMs = i * 1000.0 / sampleRate
            var env = exp(-tMs / note.tauMs)
            if (attack > 0 && i < attack) env *= i.toDouble() / attack
            // Force the tail to zero, so cutting the note here cannot produce a step.
            val fromEnd = len - 1 - i
            if (release > 0 && fromEnd < release) env *= fromEnd.toDouble() / release
            val cycles = note.freq * tMs / 1000.0
            buf[idx] += (osc(note.wave, note.freq, cycles, sampleRate) * note.amp * env).toFloat()
        }
    }

    /**
     * REED is a sawtooth with its harmonics summed explicitly and capped, rather than the
     * `sin(x) >= 0` square it replaces. The cap is the point: an uncapped square aliases.
     */
    private fun osc(wave: Wave, freq: Double, cycles: Double, sampleRate: Int): Double =
        when (wave) {
            Wave.SINE -> sin(2 * PI * cycles)
            Wave.REED -> {
                val partials = min(12, (sampleRate * 0.42 / freq).toInt())
                var v = 0.0
                for (k in 1..partials) v += sin(2 * PI * k * cycles) / k
                v * 0.6
            }
        }
}
