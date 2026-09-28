package com.timergym.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AssistChip
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.timergym.data.AppSettings
import com.timergym.data.Sound
import com.timergym.voice.VoiceCommand
import com.timergym.voice.match
import kotlin.math.roundToInt

/** Rest reminder intervals offered in Settings. 0 means off. */
private val REST_REMINDER_CHOICES = listOf(0, 15, 30, 60)

@Composable
fun SettingsSheet(
    settings: AppSettings,
    micListening: Boolean,
    debugTools: Boolean,
    timeScale: Float,
    onSound: (Sound) -> Unit,
    onVolume: (Float) -> Unit,
    onPreview: (Sound) -> Unit,
    onHaptics: (Boolean) -> Unit,
    onRestBeepSeconds: (Int) -> Unit,
    onVoice: (Boolean) -> Unit,
    onVoiceDebug: (Boolean) -> Unit,
    onVoiceTestPanel: (Boolean) -> Unit,
    voiceStatus: String,
    voiceDebug: String,
    voiceLevel: Int,
    onTimeScale: (Float) -> Unit,
    onDebugCommand: (VoiceCommand) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(max = 560.dp)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp)
            .padding(bottom = 28.dp),
    ) {
        Text("End-of-timer sound", style = MaterialTheme.typography.titleMedium)
        Text(
            text = "Pick one to hear it. It is the cue when an exercise reaches zero.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
        SoundPicker(settings.sound, onSound)

        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "Volume",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = "${(settings.volume * 100).roundToInt()}%",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Slider(
            value = settings.volume,
            onValueChange = onVolume,
            // Preview on release, not on every pixel of the drag: each cue restarts from
            // its attack, so scrubbing would just be a stutter of attacks.
            onValueChangeFinished = { onPreview(settings.sound) },
            valueRange = 0f..1f,
        )
        Text(
            text = "How loud the cue is. The phone's Alarm volume still caps this, so " +
                "turn that bar up for the loudest beep.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(Modifier.height(8.dp))
        Toggle("Vibrate on each transition", settings.haptics, onHaptics)

        Spacer(Modifier.height(12.dp))
        Text("Rest reminder", style = MaterialTheme.typography.titleMedium)
        Text(
            text = "Beep this often once rest has started, so a long rest is more than a " +
                "number counting up.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            REST_REMINDER_CHOICES.forEach { choice ->
                FilterChip(
                    selected = settings.restBeepSeconds == choice,
                    onClick = { onRestBeepSeconds(choice) },
                    label = { Text(if (choice == 0) "Off" else "${choice}s") },
                )
            }
        }

        Spacer(Modifier.height(8.dp))
        HorizontalDivider()
        Spacer(Modifier.height(8.dp))

        Text("Voice control", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(4.dp))
        Text(
            text = "Say \"timer\" or \"start\" to begin, then \"pause\", \"continue\", " +
                "\"restart\" or \"stop\". Recognition is offline and limited to those words, " +
                "so it will not mistake gym noise for a command.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Toggle("Listen for commands", settings.voice, onVoice)
        // Checking a wording, and seeing that a word landed, is what stays useful once
        // voice works — so it gets its own switch, and it also drives the "Heard ..."
        // bar in the app. The noisy read-outs below are on a separate switch.
        Toggle("Show voice commands", settings.voiceTestPanel, onVoiceTestPanel)
        // One switch for the raw diagnostics: status, counters and time speed. Left on by
        // default, because these rows are why "it does not work" was ever debuggable.
        Toggle("Show debug options", settings.voiceDebug, onVoiceDebug)
        // The live mic level belongs to the voice-commands switch, not the debug one: it
        // is the thing you watch *while speaking*, not a diagnostic read-out. Gating it on
        // voiceDebug is what hid it whenever the counters were switched off.
        if (settings.voiceTestPanel && voiceDebug.isNotEmpty()) {
            Spacer(Modifier.height(4.dp))
            LevelBar(voiceLevel)
        }
        if (settings.voiceDebug) {
            Spacer(Modifier.height(4.dp))
            Text("Diagnostics", style = MaterialTheme.typography.titleSmall)
            // Status and mic state on one row: they were separate lines and read as two
            // unrelated facts, when in practice the mic state usually explains the status.
            Text(
                text = "Status: $voiceStatus · mic ${if (micListening) "open" else "closed"}",
                style = MaterialTheme.typography.bodyMedium,
                color = if (voiceStatus.startsWith("FAILED")) MaterialTheme.colorScheme.error
                else MaterialTheme.colorScheme.primary,
            )
            if (voiceDebug.isNotEmpty()) {
                Text(
                    text = voiceDebug,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Text(
            text = "Runs entirely on-device: the microphone stays open, so there is no " +
                "gap and no tone. Nothing is uploaded.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        // Each debug section carries its own leading divider, so switching either toggle
        // off cannot leave a dangling rule at the top of the other one.
        if (debugTools) {
            if (settings.voiceTestPanel) {
                Spacer(Modifier.height(8.dp))
                HorizontalDivider()
                Spacer(Modifier.height(8.dp))
                VoiceTestPanel(onDebugCommand)
            }
            if (settings.voiceDebug) {
                Spacer(Modifier.height(8.dp))
                HorizontalDivider()
                Spacer(Modifier.height(8.dp))
                Text("Debug · time speed", style = MaterialTheme.typography.titleMedium)
                Text(
                    text = "Runs the clock faster so a two-minute set finishes in seconds. " +
                        "Debug builds only.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(1f, 2f, 5f, 10f).forEach { factor ->
                        FilterChip(
                            selected = timeScale == factor,
                            onClick = { onTimeScale(factor) },
                            label = { Text("${factor}x") },
                        )
                    }
                }
            }
        }
    }
}

/**
 * The eight cues behind a dropdown rather than eight radio rows: the list was eating most
 * of the sheet, and the names only need to be legible once a choice has been made.
 *
 * [menuAnchor] takes no argument on material3 1.2.x (the Compose BOM here pins 2024.06.00).
 * The `MenuAnchorType` overload arrived in 1.3.0 and would not compile here.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SoundPicker(selected: Sound, onSelect: (Sound) -> Unit) {
    var open by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = open, onExpandedChange = { open = it }) {
        OutlinedTextField(
            value = selected.display,
            onValueChange = {},
            readOnly = true,
            singleLine = true,
            label = { Text("Sound") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = open) },
            // Transparent for the same reason as the phrase field: the sheet owns the
            // background, and a second guessed colour is just another thing to be wrong.
            colors = OutlinedTextFieldDefaults.colors(
                unfocusedContainerColor = Color.Transparent,
                focusedContainerColor = Color.Transparent,
            ),
            modifier = Modifier
                .menuAnchor()
                .fillMaxWidth(),
        )
        ExposedDropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            Sound.entries.forEach { sound ->
                DropdownMenuItem(
                    text = { Text(sound.display) },
                    onClick = {
                        open = false
                        // Picking still previews: the names cannot convey a pitch.
                        onSelect(sound)
                    },
                )
            }
        }
    }
}

/** A plain box rather than a Material indicator: no API to get wrong, and it cannot lie. */
@Composable
private fun LevelBar(level: Int) {
    val fraction = (level / 32767f).coerceIn(0f, 1f)
    Box(
        Modifier
            .fillMaxWidth()
            .height(6.dp)
            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(3.dp)),
    ) {
        Box(
            Modifier
                .fillMaxWidth(fraction)
                .height(6.dp)
                .background(
                    if (fraction < 0.02f) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.primary,
                    RoundedCornerShape(3.dp),
                ),
        )
    }
}

@Composable
private fun Toggle(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onChange(!checked) }
            .padding(vertical = 6.dp),
    ) {
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

/**
 * Debug-only, and the fastest way to tell the two voice problems apart: a recognition
 * failure (nothing is heard) versus a vocabulary failure (words are heard but do not
 * map). Type a phrase to check the mapping without a microphone, and fire each command
 * directly to check that the timer reacts.
 */
@Composable
private fun VoiceTestPanel(onDebugCommand: (VoiceCommand) -> Unit) {
    var phrase by remember { mutableStateOf("") }
    val result = remember(phrase) {
        if (phrase.isBlank()) null else match(phrase)?.let { "${it.command} (matched \"${it.keyword}\")" }
            ?: "no command"
    }

    Text("Debug · voice", style = MaterialTheme.typography.titleMedium)
    Text(
        text = "Type what the recognizer would return, to check the wording without a mic.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(8.dp))
    OutlinedTextField(
        value = phrase,
        onValueChange = { phrase = it },
        label = { Text("Heard phrase") },
        placeholder = { Text("pause the timer") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
        // Transparent rather than a second surface colour. The sheet already supplies the
        // background, and a second guessed colour is one more thing to be subtly wrong
        // against it; transparency cannot mismatch. The outline still delimits the field.
        colors = OutlinedTextFieldDefaults.colors(
            unfocusedLabelColor = MaterialTheme.colorScheme.onSurfaceVariant,
            focusedContainerColor = Color.Transparent,
            unfocusedContainerColor = Color.Transparent,
        ),
    )
    if (result != null) {
        Text(
            text = "→ $result",
            style = MaterialTheme.typography.bodyMedium,
            color = if (result == "no command") MaterialTheme.colorScheme.error
            else MaterialTheme.colorScheme.primary,
        )
    }
    Spacer(Modifier.height(10.dp))
    Text(
        text = "Skip the microphone and run the command as if it were heard:",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(4.dp))
    Column {
        VoiceCommand.entries.chunked(3).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { command ->
                    AssistChip(
                        onClick = { onDebugCommand(command) },
                        label = { Text("\"${command.name.lowercase()}\"") },
                    )
                }
            }
        }
    }
}

