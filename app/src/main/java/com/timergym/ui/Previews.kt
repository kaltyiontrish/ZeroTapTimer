package com.timergym.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import com.timergym.data.AppSettings
import com.timergym.data.Sound
import com.timergym.data.Step
import com.timergym.timer.RunState
import com.timergym.timer.SessionState
import com.timergym.timer.Stage

/**
 * Android Studio previews: the fastest way to see the UI without a device or emulator.
 * Select a `@Preview` and open the Split or Design pane.
 */

private val sampleSteps = listOf(
    Step(1, 45),
    Step(2, 40),
    Step(3, 90),
    Step(4, 30),
)

private val sampleSettings = AppSettings(
    steps = sampleSteps,
    sound = Sound.BELL,
)

@Composable
private fun Frame(dark: Boolean = true, content: @Composable () -> Unit) =
    TimerGymTheme(dark = dark) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            content()
        }
    }

@Composable
private fun Sample(state: SessionState) = TimerScreen(
    state = state,
    settings = sampleSettings,
    onToggle = {},
    onSelect = {},
    onRestart = {},
    onToggleMic = {},
    micListening = false,
)

@Preview(name = "1 - Ready", showBackground = true, widthDp = 400, heightDp = 860)
@Composable
private fun Ready() = Frame {
    Sample(SessionState(exerciseDurationMs = 30_000, exerciseRemainingMs = 30_000))
}

@Preview(name = "2 - Running", showBackground = true, widthDp = 400, heightDp = 860)
@Composable
private fun Running() = Frame {
    Sample(
        SessionState(
            runState = RunState.RUNNING,
            exerciseIndex = 1,
            exerciseDurationMs = 45_000,
            exerciseRemainingMs = 27_400,
        )
    )
}

@Preview(name = "3 - Resting", showBackground = true, widthDp = 400, heightDp = 860)
@Composable
private fun Resting() = Frame {
    Sample(
        SessionState(
            runState = RunState.RUNNING,
            stage = Stage.REST,
            exerciseIndex = 1,
            exerciseDurationMs = 45_000,
            exerciseRemainingMs = 0,
            restElapsedMs = 43_000,
        )
    )
}

@Preview(name = "4 - Paused", showBackground = true, widthDp = 400, heightDp = 860)
@Composable
private fun Paused() = Frame {
    Sample(
        SessionState(
            runState = RunState.PAUSED,
            exerciseIndex = 2,
            exerciseDurationMs = 60_000,
            exerciseRemainingMs = 41_000,
        )
    )
}

@Preview(name = "5 - Light", showBackground = true, widthDp = 400, heightDp = 860)
@Composable
private fun Light() = Frame(dark = false) {
    Sample(
        SessionState(
            runState = RunState.RUNNING,
            stage = Stage.REST,
            exerciseIndex = 2,
            exerciseDurationMs = 60_000,
            exerciseRemainingMs = 0,
            restElapsedMs = 12_000,
        )
    )
}

@Preview(name = "6 - Exercises", showBackground = true, widthDp = 400, heightDp = 860)
@Composable
private fun Exercises() = Frame {
    EditTimers(settings = sampleSettings, onChange = {}, onAdd = {})
}

@Preview(name = "7 - Settings", showBackground = true, widthDp = 400, heightDp = 860)
@Composable
private fun Settings() = Frame {
    SettingsSheet(
        settings = sampleSettings,
        micListening = true,
        debugTools = true,
        timeScale = 1f,
        onSound = {},
        onVolume = {},
        onPreview = {},
        onHaptics = {},
        onRestBeepSeconds = {},
        onVoice = {},
        onVoiceDebug = {},
        onVoiceTestPanel = {},
        voiceStatus = "off",
        voiceDebug = "",
        voiceLevel = 0,
        onTimeScale = {},
        onDebugCommand = {},
    )
}
