package com.timergym.voice

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Process
import android.util.Log
import androidx.core.content.ContextCompat
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import java.io.File
import kotlin.math.abs

/**
 * Always-on wake word: a permanent AudioRecord stream feeding a Vosk recogniser that is
 * restricted to a grammar of a handful of words.
 *
 * The microphone never closes, so there is nothing to re-arm and therefore no platform
 * start/stop tone, and no gap in which a word goes unheard. The platform
 * SpeechRecognizer is gone.
 *
 * Every Vosk call lives in this file on purpose. The library is a native AAR and its exact
 * signatures are the least verified part of this project, so isolating them means a
 * mismatch is a one-file fix instead of a sweep through the app.
 *
 * The model is unpacked from assets on first run because Vosk needs a real filesystem
 * path, not a compressed asset.
 */
class VoiceController(
    context: Context,
    private val onPhrase: (String) -> Unit,
    private val onError: (String) -> Unit,
    private val onListeningChange: (Boolean) -> Unit,
    /** Human-readable pipeline stage, surfaced in Settings so failures are nameable. */
    private val onStatus: (String) -> Unit,
    /** Live numbers, kept separate so they do not overwrite the stage. */
    private val onDebug: (String) -> Unit,
    /** Current audio peak, for a level meter. */
    private val onLevel: (Int) -> Unit,
) {

    private val appContext = context.applicationContext

    @Volatile
    private var running = false
    private var worker: Thread? = null
    private var model: Model? = null

    fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(appContext, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    fun isRunning() = running

    /** True once the model has been unpacked, so Settings can report it up front. */
    fun isModelReady(): Boolean = File(appContext.filesDir, MODEL_DIR).isDirectory

    fun start() {
        if (running) return
        if (!hasPermission()) {
            onError("Microphone permission is needed for voice commands")
            onStatus("blocked: no microphone permission")
            return
        }
        running = true
        step("starting")
        worker = Thread({ listen() }, "vosk-listen").also {
            it.priority = Thread.NORM_PRIORITY
            it.start()
        }
    }

    fun stop() {
        running = false
        worker?.join(400)
        worker = null
        onListeningChange(false)
        step("stopped")
    }

    private fun step(message: String) {
        Log.i(TAG, message)
        onStatus(message)
    }

    private fun debug(message: String) {
        Log.i(TAG, message)
        onDebug(message)
    }

    private fun listen() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)

        val modelDir = unpackModel()
        if (modelDir == null) return fail("model missing from assets")
        step("model unpacked at $modelDir")

        val loaded = model ?: runCatching { Model(modelDir) }.getOrNull().also { model = it }
        if (loaded == null) return fail("model failed to load")
        step("model loaded")

        step("microphone opening")
        val recorder = runCatching { buildRecorder() }.getOrNull()
        if (recorder == null) return fail("microphone unavailable")
        step("microphone open at $SAMPLE_RATE Hz")

        val recognizer = runCatching {
            Recognizer(loaded, SAMPLE_RATE.toFloat(), grammarJson()).apply {
                // Vosk does its own endpointing from the audio it is given, in seconds of
                // silence, so the timings belong here rather than in a hand-rolled gate.
                setEndpointerDelays(ENDPOINT_START, ENDPOINT_END, ENDPOINT_MAX)
            }
        }.getOrNull()
        if (recognizer == null) {
            recorder.release()
            return fail("recogniser failed to start")
        }
        step("listening, grammar = ${grammarJson()}")

        onListeningChange(true)
        val buffer = ShortArray(BUFFER_SAMPLES)
        // Proof of life: if this never moves, no audio is arriving at all. Without it a
        // silent microphone and a failed grammar look identical from the outside.
        var frames = 0
        var peak = 0
        var utterances = 0
        var last = ""

        try {
            // AutoCloseable, so the native recogniser is freed even if this throws.
            recognizer.use { rec ->
                var readErrors = 0
                while (running) {
                    val read = recorder.read(buffer, 0, buffer.size)
                    if (read < 0) {
                        // A negative value is an error code, not "no data yet". Swallowing
                        // it is what made a dead microphone look like silence.
                        if (++readErrors == 1 || readErrors % 50 == 0) {
                            debug("read error $read, $readErrors times so far")
                        }
                        Thread.sleep(20)
                        continue
                    }
                    readErrors = 0
                    frames++

                    var sum = 0L
                    for (i in 0 until read) {
                        val v = abs(buffer[i].toInt())
                        sum += v
                        if (v > peak) peak = v
                    }
                    onLevel(peak)
                    if (frames % AUDIO_REPORT_FRAMES == 0) {
                        debug(
                            "frames $frames · peak $peak · mean ${sum / read} · " +
                                "partial \"${partial(rec)}\" · utterances $utterances · last \"$last\""
                        )
                    }

                    // acceptWaveForm returns true when an utterance has finished. The
                    // result itself comes from getResult(), which is JSON, not a boolean.
                    if (!rec.acceptWaveForm(buffer, read)) continue

                    val text = runCatching {
                        JSONObject(rec.getResult()).optString("text").trim()
                    }.getOrDefault("")
                    utterances++
                    last = text
                    Log.i(TAG, "utterance: \"$text\"")
                    if (text.isNotEmpty()) onPhrase(text)
                }
            }
        } catch (t: Throwable) {
            Log.e(TAG, "listen loop failed", t)
            fail("stopped: ${t.message ?: "unknown error"}")
        } finally {
            running = false
            runCatching { recorder.stop() }
            recorder.release()
            onListeningChange(false)
            onLevel(0)
            step("microphone closed after $frames frames")
        }
    }

    /** The in-progress transcript. If this fills in, the model is hearing you. */
    private fun partial(rec: Recognizer): String = runCatching {
        JSONObject(rec.getPartialResult()).optString("partial").trim()
    }.getOrDefault("")

    private fun fail(message: String) {
        running = false
        Log.e(TAG, "FAILED: $message")
        onStatus("FAILED: $message")
        onError("Voice: $message")
    }

    private fun buildRecorder(): AudioRecord {
        val min = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_IN, ENCODING)
        if (min <= 0) throw IllegalStateException("AudioRecord is not supported here")
        val recorder = AudioRecord(
            // VOICE_RECOGNITION skips the voice-call processing that mangles speech.
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            SAMPLE_RATE, CHANNEL_IN, ENCODING,
            maxOf(min * 2, BUFFER_SAMPLES * 4),
        )
        if (recorder.state != AudioRecord.STATE_INITIALIZED) {
            recorder.release()
            throw IllegalStateException("AudioRecord did not initialise")
        }
        // Required. Without it read() returns ERROR_INVALID_OPERATION forever, and a
        // "if (read <= 0) continue" guard then turns a dead microphone into silence
        // rather than an error.
        recorder.startRecording()
        if (recorder.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
            recorder.release()
            throw IllegalStateException("AudioRecord refused to start recording")
        }
        return recorder
    }

    /**
     * Vosk reads from a real path, so the model is copied out of assets once. The marker
     * file is written only after a complete copy, so a half-finished copy retries rather
     * than loading a truncated model.
     */
    private fun unpackModel(): String? {
        val dest = File(appContext.filesDir, MODEL_DIR)
        if (File(dest, READY_MARKER).exists()) return dest.absolutePath
        copyAssetTree(MODEL_ASSET, dest)
        return if (File(dest, READY_MARKER).exists()) dest.absolutePath else null
    }

    private fun copyAssetTree(assetPath: String, dest: File) {
        val children = appContext.assets.list(assetPath) ?: return
        dest.mkdirs()
        for (name in children) {
            val childAsset = "$assetPath/$name"
            val childFile = File(dest, name)
            // assets.list() on a file returns an empty array, which is how directories
            // are told apart from files without a manifest.
            if (appContext.assets.list(childAsset)?.isEmpty() == false) {
                copyAssetTree(childAsset, childFile)
            } else {
                childFile.outputStream().use { out ->
                    appContext.assets.open(childAsset).use { it.copyTo(out) }
                }
            }
        }
        File(dest, READY_MARKER).writeText("ok")
    }

    private companion object {
        const val TAG = "VoiceController"
        const val SAMPLE_RATE = 16000
        const val CHANNEL_IN = AudioFormat.CHANNEL_IN_MONO
        const val ENCODING = AudioFormat.ENCODING_PCM_16BIT

        /** 100ms of 16kHz audio. */
        const val BUFFER_SAMPLES = 1600

        /**
         * Endpointing, in seconds, straight into Vosk. [ENDPOINT_END] is how much silence
         * after speech ends an utterance: lower reacts faster, higher tolerates pauses
         * mid-word. [ENDPOINT_START] gives up if nobody has spoken at all, and
         * [ENDPOINT_MAX] caps one utterance.
         */
        const val ENDPOINT_START = 5f
        const val ENDPOINT_END = 0.7f
        const val ENDPOINT_MAX = 15f

        /** How often the audio-level proof of life is reported, in 100ms frames. */
        const val AUDIO_REPORT_FRAMES = 20

        const val MODEL_ASSET = "vosk-model-en-us"
        const val MODEL_DIR = "vosk-model-en-us"
        const val READY_MARKER = ".unpacked"
    }
}

