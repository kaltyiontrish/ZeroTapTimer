package com.timergym.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.timergym.data.AppSettings
import com.timergym.data.Step
import com.timergym.timer.RunState
import com.timergym.timer.SessionState
import com.timergym.timer.Stage

/** The exercise button, and the gap it sits in. Sized here so the two-row cap below
 * cannot drift out of step with the button it is meant to measure. */
private val BUTTON = 54.dp
private val GAP = 8.dp

/** How long the clock takes to blink after a word is recognized. */
private const val FLASH_MS = 220

/**
 * The blink never reaches full white. A solid disc at 100% is startling mid-set and hides
 * the countdown for a fifth of a second; half white still reads unmistakably as a
 * confirmation without competing with the dial it sits on.
 */
private const val BLINK_ALPHA = 0.5f

/** The clock, and the white blink drawn over it. One constant so the blink cannot end up
 * a slightly different size from the dial it is meant to cover. */
private val DIAL = 292.dp

/** Two rows of buttons: the most that fit without crowding the dial out of the screen. */
private val TWO_ROWS = BUTTON * 2 + GAP

/**
 * Top to bottom: a row of square exercise buttons, the dial, and the play control.
 * [onSelect] picks which exercise the dial is showing. The dial is the only rest
 * readout, so nothing is duplicated underneath it.
 */
@Composable
fun TimerScreen(
    state: SessionState,
    settings: AppSettings,
    onToggle: () -> Unit,
    onSelect: (Int) -> Unit,
    onRestart: () -> Unit,
    onToggleMic: () -> Unit,
    micListening: Boolean,
    commandPulse: Int = 0,
    modifier: Modifier = Modifier,
) {
    val step = settings.steps.getOrNull(state.exerciseIndex) ?: settings.steps.first()
    val resting = state.stage == Stage.REST
    val accent = if (resting) RestColor else WorkColor
    val running = state.runState == RunState.RUNNING
    val paused = state.runState == RunState.PAUSED
    // The "we heard you" blink. Snaps to its peak and fades, so it reads as a blink rather
    // than a slow swell, and covers the whole clock face at that peak.
    val flash = remember { Animatable(0f) }
    LaunchedEffect(commandPulse) {
        if (commandPulse > 0) {
            flash.snapTo(1f)
            flash.animateTo(0f, tween(FLASH_MS))
        }
    }

    Column(
        modifier = modifier.fillMaxSize().padding(horizontal = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        ExerciseButtons(
            steps = settings.steps,
            selected = state.exerciseIndex,
            onSelect = onSelect,
        )
        Spacer(Modifier.height(28.dp))

        Box(Modifier.size(DIAL), contentAlignment = Alignment.Center) {
            Dial(
                // The ring unwinds clockwise from twelve o'clock, the way a countdown
                // should: the gap grows clockwise as the exercise burns down.
                remainingFraction = if (resting) null
                else (state.exerciseRemainingMs.toFloat() / state.exerciseDurationMs.coerceAtLeast(1L))
                    .coerceIn(0f, 1f),
                accent = accent,
            )
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = formatMs(state.dialMs),
                    style = BigDigits,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = if (resting) "RESTING" else "EXERCISE",
                    style = MaterialTheme.typography.labelLarge,
                    letterSpacing = 2.sp,
                    color = accent,
                )
                Text(
                    text = if (resting) "press start when ready" else "${step.seconds}s",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            // Drawn last so it sits over the digits. The middle of the clock is the one
            // place the eye already is, which is why this beats a badge anywhere else.
            if (flash.value > 0.01f) {
                Box(
                    Modifier
                        .size(DIAL)
                        .background(
                            Color.White.copy(alpha = flash.value * BLINK_ALPHA),
                            CircleShape,
                        )
                )
            }
        }

        Spacer(Modifier.height(34.dp))
        ControlRow(
            running = running,
            paused = paused,
            accent = accent,
            micOn = settings.voice,
            micListening = micListening,
            onToggleMic = onToggleMic,
            onToggle = onToggle,
            onRestart = onRestart,
        )
    }
}

/**
 * The ring unwinds clockwise from twelve o'clock. [remainingFraction] is 1 at the start
 * and 0 at the end; null draws the track only, which is what a stopwatch wants.
 */
@Composable
private fun Dial(
    remainingFraction: Float?,
    accent: Color,
) {
    Canvas(Modifier.fillMaxSize()) {
        val stroke = 22.dp.toPx()
        val inset = stroke / 2f
        val arc = Size(size.width - stroke, size.height - stroke)
        drawArc(
            color = accent.copy(alpha = 0.15f),
            startAngle = -90f,
            sweepAngle = 360f,
            useCenter = false,
            topLeft = Offset(inset, inset),
            size = arc,
            style = Stroke(width = stroke, cap = StrokeCap.Round),
        )
        if (remainingFraction != null) {
            // Clockwise depletion. The gap has to grow clockwise *from twelve*, which
            // means the arc starts at -90 + the elapsed sweep and wraps back to twelve.
            // Starting at -90 with a shrinking sweep instead would drag the visible edge
            // anticlockwise back toward twelve, which reads as running in reverse.
            val gap = 360f * (1f - remainingFraction)
            drawArc(
                color = accent,
                startAngle = -90f + gap,
                sweepAngle = 360f * remainingFraction,
                useCenter = false,
                topLeft = Offset(inset, inset),
                size = arc,
                style = Stroke(width = stroke, cap = StrokeCap.Round),
            )
        }
    }
}

/**
 * Mic, then start/pause, then restart. The mic lives here rather than in the top bar so
 * every control you reach for mid-set is in one place.
 */
@Composable
private fun ControlRow(
    running: Boolean,
    paused: Boolean,
    accent: Color,
    micOn: Boolean,
    micListening: Boolean,
    onToggleMic: () -> Unit,
    onToggle: () -> Unit,
    onRestart: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        FilledTonalIconButton(onClick = onToggleMic, modifier = Modifier.size(56.dp)) {
            Icon(
                imageVector = if (micOn) Icons.Filled.Mic else Icons.Filled.MicOff,
                contentDescription = if (micOn) "Turn voice control off" else "Turn voice control on",
                tint = when {
                    micListening -> MaterialTheme.colorScheme.primary
                    micOn -> MaterialTheme.colorScheme.onSurfaceVariant
                    else -> MaterialTheme.colorScheme.outline
                },
            )
        }
        Button(
            onClick = onToggle,
            // Wide enough that "Pause" never wraps, even at a large font scale: the icon
            // (30) + gap (8) + the word + the button's own 24dp content padding each side.
            modifier = Modifier.size(width = 168.dp, height = 72.dp),
            shape = CircleShape,
            colors = ButtonDefaults.buttonColors(
                containerColor = accent,
                contentColor = Color.Black,
            ),
        ) {
            Icon(
                imageVector = if (running) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                contentDescription = null,
                modifier = Modifier.size(30.dp),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                // "Start" on a paused timer was a lie: it was already started. The voice
                // commands have always called this "continue", so the button agrees.
                text = when {
                    running -> "Pause"
                    paused -> "Resume"
                    else -> "Start"
                },
                fontSize = 18.sp,
                fontWeight = FontWeight.SemiBold,
                // Belt and braces: if someone has font scaling cranked up, let the label
                // shrink rather than break "Pause" across two lines.
                maxLines = 1,
                softWrap = false,
            )
        }
        FilledTonalIconButton(onClick = onRestart, modifier = Modifier.size(56.dp)) {
            Icon(Icons.Filled.Refresh, contentDescription = "Restart")
        }
    }
}

/**
 * A square button per exercise at the top of the screen, showing its length in seconds.
 *
 * Rows are centred, so a part-filled last row looks deliberate rather than left-aligned,
 * and the block is capped at two rows (116dp) with anything beyond that scrolling
 * vertically. The dial below is the thing you actually watch mid-set; a twenty-exercise
 * list must not be able to push it off the screen.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ExerciseButtons(
    steps: List<Step>,
    selected: Int,
    onSelect: (Int) -> Unit,
) {
    FlowRow(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = TWO_ROWS)
            .verticalScroll(rememberScrollState()),
        // The alignment argument centres each row, including the short last one.
        horizontalArrangement = Arrangement.spacedBy(GAP, Alignment.CenterHorizontally),
        verticalArrangement = Arrangement.spacedBy(GAP),
    ) {
        steps.forEachIndexed { index, step ->
            val isSelected = index == selected
            FilledTonalButton(
                onClick = { onSelect(index) },
                // Square, so the grid reads as a grid.
                modifier = Modifier.size(BUTTON),
                shape = RoundedCornerShape(14.dp),
                border = if (isSelected) BorderStroke(2.dp, WorkColor) else null,
                contentPadding = PaddingValues(0.dp),
                colors = if (isSelected) {
                    ButtonDefaults.filledTonalButtonColors(
                        containerColor = WorkColor.copy(alpha = 0.22f),
                        contentColor = WorkColor,
                    )
                } else {
                    ButtonDefaults.filledTonalButtonColors(
                        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                },
            ) {
                Text(
                    text = step.seconds.toString(),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = if (isSelected) WorkColor else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

