package com.timergym.timer

import android.app.Application
import android.media.AudioAttributes
import android.os.Build
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.timergym.BuildConfig
import com.timergym.data.AppSettings
import com.timergym.data.Repo
import com.timergym.data.Sound
import com.timergym.data.Step
import com.timergym.sound.SoundPlayer
import com.timergym.voice.VoiceCommand
import com.timergym.voice.VoiceController
import com.timergym.voice.VoiceMatch
import com.timergym.voice.NUMBER_WORDS
import com.timergym.voice.match
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Drives [TimerEngine] with the real clock and turns its events into sound, speech and
 * haptics. The engine itself stays pure and clock-injected.
 */
class TimerViewModel(app: Application) : AndroidViewModel(app) {

    private val repo = Repo(app)
    val settings: StateFlow<AppSettings> = repo.settings

    // Seeded from the engine rather than a default: a blank SessionState has no
    // duration in it, and this has to be right before the first frame.
    private var engine = newEngine(repo.settings.value)
    private val _state = MutableStateFlow(engine.state)
    val state: StateFlow<SessionState> = _state.asStateFlow()

    private val _micListening = MutableStateFlow(false)
    val micListening: StateFlow<Boolean> = _micListening.asStateFlow()

    /** Which stage of the voice pipeline we are at, shown in Settings. */
    private val _voiceStatus = MutableStateFlow("off")
    val voiceStatus: StateFlow<String> = _voiceStatus.asStateFlow()

    /** Live recognition numbers, and the current microphone level. */
    private val _voiceDebug = MutableStateFlow("")
    val voiceDebug: StateFlow<String> = _voiceDebug.asStateFlow()

    private val _voiceLevel = MutableStateFlow(0)
    val voiceLevel: StateFlow<Int> = _voiceLevel.asStateFlow()

    /** A heard phrase or a problem, for the snackbar. Transient by design. */
    private val _notice = MutableStateFlow<String?>(null)
    val notice: StateFlow<String?> = _notice.asStateFlow()

    /**
     * Bumped on every recognized word, to flash the mic button. A counter rather than a
     * boolean: a boolean would already be true when a second word arrived, and the second
     * flash would be lost.
     */
    private val _commandPulse = MutableStateFlow(0)
    val commandPulse: StateFlow<Int> = _commandPulse.asStateFlow()

    private val sounds = SoundPlayer()

    // Debug-only clock multiplier, see [clock].
    private val _timeScale = MutableStateFlow(1f)
    val timeScale: StateFlow<Float> = _timeScale.asStateFlow()
    val debugTools = BuildConfig.DEBUG

    private var virtualNow = 0L
    private var realMark = 0L

    private val vibrator: Vibrator? = run {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (app.getSystemService(Application.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)
                ?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            app.getSystemService(Application.VIBRATOR_SERVICE) as? Vibrator
        }
    }

    private val voice = VoiceController(
        context = app,
        // The recogniser runs on its own thread, so hop back to the main thread before
        // touching state that Compose is reading.
        onPhrase = { phrase ->
            getApplication<Application>().mainExecutor.execute {
                match(phrase)?.let { m ->
                    applyVoice(m)
                    // The bar and the mic flash are two faces of the same "we heard you"
                    // signal, and exactly one fires. Switching the bar off therefore moves
                    // the feedback to the button rather than doubling it up.
                    if (repo.settings.value.voiceTestPanel) {
                        _notice.value = "Heard \"${m.spoken}\" → ${m.command}"
                    } else {
                        _commandPulse.value++
                    }
                }
            }
        },
        onListeningChange = { listening -> _micListening.value = listening },
        onStatus = { stage -> _voiceStatus.value = stage },
        onDebug = { line -> _voiceDebug.value = line },
        onLevel = { level -> _voiceLevel.value = level },
        onError = { message -> _notice.value = message },
    )

    private fun newEngine(s: AppSettings) = TimerEngine(s.steps, s.restBeepSeconds)

    init {
        // Only the exercise list changes the engine, so toggling the sound or the
        // haptics mid-set does not interrupt what is running.
        viewModelScope.launch {
            settings
                .map { it.steps }
                .distinctUntilChanged()
                .collect { steps ->
                    engine = TimerEngine(steps)
                    _state.value = engine.state
                }
        }

        viewModelScope.launch {
            while (isActive) {
                delay(TICK_MS)
                onTick()
            }
        }
    }

    // ---- controls -----------------------------------------------------------------

    fun toggle() = consume(engine.toggle(clock()))

    fun play() = consume(engine.start(clock()))

    fun pause() = consume(engine.pause(clock()))

    fun reset() {
        engine.reset(clock())
        _state.value = engine.state
    }

    /** Pick an exercise from the row of buttons under the play control. */
    fun select(index: Int) {
        engine.select(index, clock())
        _state.value = engine.state
    }

    fun setVoiceEnabled(on: Boolean) {
        repo.setVoice(on)
        if (on) voice.start() else voice.stop()
    }

    fun onForeground() {
        if (repo.settings.value.voice) voice.start()
    }

    fun onBackground() = voice.stop()

    /** Debug hook: run a command as if it had been heard, to test the wiring. */
    fun fireCommand(command: VoiceCommand) =
        applyVoice(VoiceMatch(command, command.name.lowercase(), command.name.lowercase()))

    /** Preview honours the volume slider too, otherwise you cannot hear what you are setting. */
    fun previewSound(sound: Sound) = sounds.play(sound, repo.settings.value.volume)

    // ---- editing (writes flow back through settings, which the UI observes) --------

    fun setSteps(steps: List<Step>) {
        // The menu must keep at least one exercise: the engine requires a non-empty list.
        if (steps.isEmpty()) return
        repo.setSteps(steps)
    }

    fun setSound(sound: Sound) = repo.setSound(sound)
    fun setVolume(volume: Float) = repo.setVolume(volume)
    fun setHaptics(on: Boolean) = repo.setHaptics(on)
    fun setVoiceDebug(on: Boolean) = repo.setVoiceDebug(on)
    fun setVoiceTestPanel(on: Boolean) = repo.setVoiceTestPanel(on)
    fun setRestBeepSeconds(seconds: Int) {
        repo.setRestBeepSeconds(seconds)
        // Set on the live engine rather than rebuilding it: a rebuild would reset a timer
        // that is already running, which is a nasty surprise mid-exercise.
        engine.setRestBeep(seconds)
    }

    fun setTimeScale(scale: Float) {
        virtualNow = clock() // settle virtual time under the old scale first
        realMark = SystemClock.elapsedRealtime()
        _timeScale.value = scale
    }

    // ---- timer plumbing ------------------------------------------------------------

    private fun onTick() {
        val events = engine.tick(clock())
        _state.value = engine.state
        consume(events)
    }

    /** Routes a manual command's events through the same path the timer loop uses. */
    private fun consume(events: List<SessionEvent>) {
        _state.value = engine.state
        for (e in events) {
            when (e) {
                is SessionEvent.ExerciseEnded -> exerciseEnded()
                is SessionEvent.ExerciseStarted -> Unit
                is SessionEvent.RestReminder -> restReminder()
            }
        }
    }

    private fun applyVoice(m: VoiceMatch) {
        when (m.command) {
            VoiceCommand.START -> if (engine.state.runState != RunState.RUNNING) play()
            VoiceCommand.RESTART -> restart()
            VoiceCommand.PAUSE -> pause()
            VoiceCommand.CONTINUE -> if (engine.state.runState == RunState.PAUSED) play()
            VoiceCommand.STOP -> reset()
            // "one".."twenty" load that exercise. The position comes from which number
            // word matched, so there is no need for twenty separate commands.
            VoiceCommand.SELECT -> {
                val index = NUMBER_WORDS.indexOf(m.keyword)
                if (index >= 0) select(index)
            }
        }
    }

    /** Reload the current exercise from its full length and start it. */
    fun restart() {
        engine.reset(clock())
        play()
    }

    /**
     * The clock the engine sees. Normally just elapsedRealtime; the debug multiplier
     * routes through a virtual clock so a 30s set finishes in 3 seconds. The 1x path
     * returns the raw clock so normal timing stays exact.
     */
    private fun clock(): Long {
        val real = SystemClock.elapsedRealtime()
        if (_timeScale.value == 1f) return real
        virtualNow += ((real - realMark) * _timeScale.value).toLong()
        realMark = real
        return virtualNow
    }

    // ---- cues ----------------------------------------------------------------------

    private fun exerciseEnded() {
        cue()
        pulse(millis = 60L, amplitude = 180)
    }

    /** A rest reminder uses the same cue at the same level: one sound to learn, not two. */
    private fun restReminder() = cue()

    private fun cue() {
        val s = repo.settings.value
        // Audio must never be able to stop the clock: one throw here would cancel the
        // whole tick coroutine and freeze the timer mid-workout.
        runCatching { sounds.play(s.sound, s.volume) }
            .onFailure { Log.e("TimerViewModel", "end-of-timer sound failed", it) }
    }

    private fun pulse(millis: Long, amplitude: Int) {
        val v = vibrator ?: return
        if (!v.hasVibrator() || !repo.settings.value.haptics) return
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                v.vibrate(VibrationEffect.createOneShot(millis, amplitude), PULSE_ATTRS)
            } else {
                @Suppress("DEPRECATION")
                v.vibrate(millis)
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        voice.stop()
        sounds.release()
    }

    private companion object {
        const val TICK_MS = 50L

        val PULSE_ATTRS: AudioAttributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_NOTIFICATION_EVENT)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
    }
}

