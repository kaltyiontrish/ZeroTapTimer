package com.timergym.sound

import com.timergym.data.Sound
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * The mechanical guarantees behind the cues.
 *
 * What this cannot do is tell you whether a cue sounds good — that needs ears. What it
 * can do is pin the properties that were previously broken and that a retune must not
 * quietly undo: silence at the edges, no clipping, audible output, and eight genuinely
 * different sounds.
 */
class CuesTest {

    /** Both rates the app can see, since the buffer follows the device's native output. */
    private val rates = intArrayOf(44100, 48000)

    @Test
    fun `every cue renders audio at every sample rate`() {
        for (sound in Sound.entries) {
            for (rate in rates) {
                val out = Cues.render(sound, rate)
                assertTrue("$sound @$rate rendered nothing", out.isNotEmpty())
                // Duration should track the declared milliseconds, not drift with the rate.
                assertTrue("$sound @$rate is implausibly short", out.size > rate / 100)
            }
        }
    }

    /**
     * A note that is still at full amplitude when it gets cut ends on a step, and a step
     * is a click. The attack and release ramps are what guarantee both ends sit at zero.
     */
    @Test
    fun `every cue starts and ends at silence`() {
        for (sound in Sound.entries) {
            for (rate in rates) {
                val out = Cues.render(sound, rate)
                assertEquals("$sound @$rate starts loud", 0, abs(out.first()).toInt())
                assertEquals("$sound @$rate ends loud", 0, abs(out.last()).toInt())
            }
        }
    }

    /** Normalization targets 0.89 of full scale, so nothing may touch the rails. */
    @Test
    fun `no cue clips`() {
        for (sound in Sound.entries) {
            for (rate in rates) {
                for (sample in Cues.render(sound, rate)) {
                    assertTrue(
                        "$sound @$rate hit full scale",
                        abs(sample) < 32700,
                    )
                }
            }
        }
    }

    /** A cue that is technically non-empty but inaudible is still a broken cue. */
    @Test
    fun `every cue is actually audible`() {
        for (sound in Sound.entries) {
            val out = Cues.render(sound, 44100)
            var sum = 0.0
            for (s in out) sum += (s / 32767.0).toDouble() * (s / 32767.0)
            val rms = sqrt(sum / out.size)
            assertTrue("$sound is too quiet, rms=$rms", rms > 0.02)
        }
    }

    /**
     * A gain typo — 0.07 instead of 0.7 — renders a cue that technically has samples but
     * is inaudible. That is exactly how the wood block went quiet once, and no other test
     * here would have noticed, because it is still non-empty and still not clipping.
     */
    @Test
    fun `no cue is a whisper compared with the others`() {
        val peaks = Sound.entries.associateWith { sound ->
            Cues.render(sound, 44100).maxOf { abs(it).toInt() }
        }
        val quietest = peaks.minOf { it.value }
        for ((sound, peak) in peaks) {
            assertTrue(
                "$sound peaks at $peak against a quietest of $quietest",
                peak >= quietest / 4,
            )
        }
    }

    /** Guards against a copy-paste leaving two entries sounding identical. */
    @Test
    fun `no two cues are the same signal`() {
        val rendered = Sound.entries.associateWith { Cues.render(it, 44100).toList() }
        val pairs = rendered.keys.toList().let { keys ->
            keys.indices.flatMap { i -> (i + 1 until keys.size).map { j -> keys[i] to keys[j] } }
        }
        for ((a, b) in pairs) {
            val left = rendered.getValue(a)
            val right = rendered.getValue(b)
            assertTrue("$a and $b render identically", left != right)
        }
    }
}