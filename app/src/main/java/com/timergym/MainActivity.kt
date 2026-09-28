package com.timergym

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.timergym.timer.RunState
import com.timergym.timer.TimerViewModel
import com.timergym.ui.EditTimers
import com.timergym.ui.SettingsSheet
import com.timergym.ui.TimerGymTheme
import com.timergym.ui.TimerScreen

class MainActivity : ComponentActivity() {

    private lateinit var vm: TimerViewModel

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // Resolve the ViewModel here rather than inside setContent: onStart needs it, and
        // the first composition is not guaranteed to have run by then.
        vm = ViewModelProvider(this)[TimerViewModel::class.java]
        setContent {
            TimerGymTheme {
                App(vm)
            }
        }
    }

    override fun onStart() {
        super.onStart()
        // Re-arm the mic: the recognizer is torn down when we leave the foreground.
        if (::vm.isInitialized) vm.onForeground()
    }

    override fun onStop() {
        if (::vm.isInitialized) vm.onBackground()
        super.onStop()
    }
}

private enum class Screen { TIMER, EDIT }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun App(vm: TimerViewModel) {
    val state by vm.state.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val micListening by vm.micListening.collectAsStateWithLifecycle()
    val voiceStatus by vm.voiceStatus.collectAsStateWithLifecycle()
    val voiceDebug by vm.voiceDebug.collectAsStateWithLifecycle()
    val voiceLevel by vm.voiceLevel.collectAsStateWithLifecycle()
    val commandPulse by vm.commandPulse.collectAsStateWithLifecycle()
    val notice by vm.notice.collectAsStateWithLifecycle()
    val timeScale by vm.timeScale.collectAsStateWithLifecycle()

    var screen by rememberSaveable { mutableStateOf(Screen.TIMER) }
    var settingsOpen by rememberSaveable { mutableStateOf(false) }
    val snackbar = remember { SnackbarHostState() }

    // Keep the screen alive during a set, and hide the bars so the dial is all you see.
    val running = state.runState == RunState.RUNNING
    val view = LocalView.current
    DisposableEffect(running) {
        val window = view.context.findActivity()?.window
        val controller = window?.let { WindowCompat.getInsetsController(it, view) }
        if (window != null) {
            if (running) {
                window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                controller?.apply {
                    systemBarsBehavior =
                        WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                    hide(WindowInsetsCompat.Type.systemBars())
                }
            } else {
                window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                controller?.show(WindowInsetsCompat.Type.systemBars())
            }
        }
        onDispose {
            view.context.findActivity()?.window
                ?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    // A heard phrase, or a mic problem, gets a snackbar. The listening state itself is
    // shown on the mic icon, so it must not raise one.
    LaunchedEffect(notice) {
        if (notice != null) snackbar.showSnackbar(notice!!)
    }

    val micPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> vm.setVoiceEnabled(granted) }


    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            if (screen == Screen.TIMER) {
                // No title. The app name belongs on the launcher, and a bar reading it back
                // over a countdown is just chrome. Edit moves to the leading edge because
                // that is where a thumb goes on a phone held in one hand.
                TopAppBar(
                    title = {},
                    navigationIcon = {
                        TextButton(onClick = { screen = Screen.EDIT }) {
                            Text("Edit")
                            Spacer(Modifier.width(6.dp))
                            // Null, because the Text beside it is already the label;
                            // otherwise a screen reader announces "Edit, edit".
                            Icon(Icons.Filled.Edit, contentDescription = null)
                        }
                    },
                    actions = {
                        IconButton(onClick = { settingsOpen = true }) {
                            Icon(Icons.Filled.Settings, contentDescription = "Settings")
                        }
                    },
                )
            } else {
                TopAppBar(
                    title = { Text("Timers", style = MaterialTheme.typography.titleLarge) },
                    navigationIcon = {
                        IconButton(onClick = { screen = Screen.TIMER }) {
                            Icon(Icons.Filled.ArrowBack, contentDescription = "Back")
                        }
                    },
                    actions = {
                        IconButton(onClick = { settingsOpen = true }) {
                            Icon(Icons.Filled.Settings, contentDescription = "Settings")
                        }
                    },
                )
            }
        },
    ) { padding ->
        when (screen) {
            Screen.TIMER -> TimerScreen(
                state = state,
                settings = settings,
                onToggle = vm::toggle,
                onSelect = vm::select,
                onRestart = vm::restart,
                onToggleMic = {
                    if (settings.voice) vm.setVoiceEnabled(false)
                    else micPermission.launch(Manifest.permission.RECORD_AUDIO)
                },
                micListening = micListening,
                commandPulse = commandPulse,
                modifier = Modifier.padding(padding).fillMaxSize(),
            )

            Screen.EDIT -> Column(Modifier.padding(padding).padding(horizontal = 16.dp)) {
                EditTimers(
                    settings = settings,
                    onChange = vm::setSteps,
                    onAdd = { step -> vm.setSteps(settings.steps + step) },
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.padding(8.dp))
            }
        }
    }

    if (settingsOpen) {
        ModalBottomSheet(
            onDismissRequest = { settingsOpen = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        ) {
            SettingsSheet(
                settings = settings,
                micListening = micListening,
                debugTools = vm.debugTools,
                timeScale = timeScale,
                // Selecting a sound plays it: the labels cannot convey a pitch.
                onSound = { sound ->
                    vm.setSound(sound)
                    vm.previewSound(sound)
                },
                onHaptics = vm::setHaptics,
                onRestBeepSeconds = vm::setRestBeepSeconds,
                onVolume = vm::setVolume,
                onVoiceDebug = vm::setVoiceDebug,
                onVoiceTestPanel = vm::setVoiceTestPanel,
                // The slider previews the current sound, so it needs its own hook.
                onPreview = vm::previewSound,
                onVoice = { on ->
                    if (on) micPermission.launch(Manifest.permission.RECORD_AUDIO)
                    else vm.setVoiceEnabled(false)
                },
                onTimeScale = vm::setTimeScale,
                voiceStatus = voiceStatus,
                voiceDebug = voiceDebug,
                voiceLevel = voiceLevel,
                onDebugCommand = vm::fireCommand,
            )
        }
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
