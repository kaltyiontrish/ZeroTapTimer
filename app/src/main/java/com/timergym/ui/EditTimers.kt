package com.timergym.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Card
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.timergym.data.AppSettings
import com.timergym.data.Step

/**
 * Add / retime / reorder / remove exercises. A single exercise is just a plain timer,
 * so this screen is both the timer editor and the workout builder.
 */
@Composable
fun EditTimers(
    settings: AppSettings,
    onChange: (List<Step>) -> Unit,
    onAdd: (Step) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        LazyColumn(
            modifier = Modifier.weight(1f, fill = false),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            itemsIndexed(settings.steps) { index, step ->
                StepRow(
                    step = step,
                    canDelete = settings.steps.size > 1,
                    onSeconds = { s ->
                        onChange(settings.steps.replaceAt(index, step.copy(seconds = s.coerceIn(1, 3600))))
                    },
                    onDelete = { onChange(settings.steps.filterIndexed { i, _ -> i != index }) },
                )
            }
        }

        Spacer(Modifier.height(12.dp))
        FilledTonalButton(
            onClick = {
                // A round length that is not already on the button row, so the new button is
                // never a duplicate of one already there. Falls back to a fresh longer value
                // once every round number is taken.
                val seconds = (30..90 step 10)
                    .firstOrNull { candidate -> settings.steps.none { it.seconds == candidate } }
                    ?: ((settings.steps.maxOfOrNull { it.seconds } ?: 30) + 10)
                onAdd(
                    Step(
                        id = (settings.steps.maxOfOrNull { it.id } ?: 0L) + 1L,
                        seconds = seconds,
                    )
                )
            },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Icon(Icons.Filled.Add, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text("Add a timer")
        }
        Text(
            text = "Set each timer's length. The buttons at the top of the timer screen load " +
                "whichever one you tap, and rest starts on its own when one runs out.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 8.dp),
        )
    }
}

private fun List<Step>.replaceAt(index: Int, step: Step): List<Step> =
    toMutableList().also { it[index] = step }

@Composable
private fun StepRow(
    step: Step,
    canDelete: Boolean,
    onSeconds: (Int) -> Unit,
    onDelete: () -> Unit,
) {
    var seconds by remember(step.id, step.seconds) { mutableStateOf(step.seconds.toString()) }

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = seconds,
                    onValueChange = { raw ->
                        val digits = raw.filter { it.isDigit() }.take(4)
                        seconds = digits
                        digits.toIntOrNull()?.let(onSeconds)
                    },
                    label = { Text("Seconds") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.width(140.dp),
                )
                Spacer(Modifier.weight(1f))
                IconButton(onClick = onDelete, enabled = canDelete) {
                    Icon(Icons.Filled.Delete, contentDescription = "Remove this timer")
                }
            }
            if (!canDelete) {
                Text(
                    text = "The last timer can't be removed.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
