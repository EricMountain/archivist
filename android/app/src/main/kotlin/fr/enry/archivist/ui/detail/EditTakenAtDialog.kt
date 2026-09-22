package fr.enry.archivist.ui.detail

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import fr.enry.archivist.domain.toIsoUtc
import java.time.Instant
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneOffset

private enum class Step { DATE, TIME }

/**
 * The "Edit date" menu action's dialog — corrects `takenAt` in [tzOffsetMin]'s local
 * wall-clock time, the same "what the photographer's watch said" framing
 * `modify_media.py taken-at --taken-at-local` uses. Deliberately doesn't offer to
 * change [tzOffsetMin] itself: that's a materially different correction (the *offset*
 * was wrong, not the date) with no obvious UI for picking a UTC offset, and nothing
 * asked for it yet — see `modify_media.py`'s own `--taken-at`/`--taken-at-local` split
 * for the two as genuinely separate operations.
 *
 * Two Material3 pickers, one after another rather than composed together: [DatePicker]
 * alone is already a full calendar grid, and stacking [TimePicker] under it in one
 * dialog crowds badly on a phone-width screen. [DatePicker]'s own `selectedDateMillis`
 * is UTC-midnight-of-the-picked-day, not a real instant — read back via
 * `atZone(ZoneOffset.UTC)` so the *calendar date* round-trips correctly regardless of
 * the device's real timezone, never treated as an actual moment in time.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditTakenAtDialog(
    currentTakenAt: String,
    tzOffsetMin: Int,
    onDismiss: () -> Unit,
    onConfirm: (newTakenAt: String) -> Unit,
) {
    val initialLocal =
        remember(currentTakenAt, tzOffsetMin) {
            Instant.parse(currentTakenAt).atOffset(ZoneOffset.ofTotalSeconds(tzOffsetMin * 60)).toLocalDateTime()
        }
    var step by remember { mutableStateOf(Step.DATE) }
    var pickedDate by remember { mutableStateOf(initialLocal.toLocalDate()) }

    when (step) {
        Step.DATE -> {
            val dateState =
                rememberDatePickerState(
                    initialSelectedDateMillis = pickedDate.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
                )
            DatePickerDialog(
                onDismissRequest = onDismiss,
                confirmButton = {
                    TextButton(onClick = {
                        val millis = dateState.selectedDateMillis
                        if (millis != null) {
                            pickedDate = Instant.ofEpochMilli(millis).atZone(ZoneOffset.UTC).toLocalDate()
                        }
                        step = Step.TIME
                    }) { Text("Next") }
                },
                dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
            ) {
                DatePicker(state = dateState)
            }
        }
        Step.TIME -> {
            val timeState =
                rememberTimePickerState(initialHour = initialLocal.hour, initialMinute = initialLocal.minute, is24Hour = true)
            AlertDialog(
                onDismissRequest = onDismiss,
                title = { Text("Time taken") },
                text = { TimePicker(state = timeState) },
                confirmButton = {
                    TextButton(onClick = {
                        val newLocal = LocalDateTime.of(pickedDate, LocalTime.of(timeState.hour, timeState.minute))
                        val newInstant = newLocal.toInstant(ZoneOffset.ofTotalSeconds(tzOffsetMin * 60))
                        onConfirm(toIsoUtc(newInstant))
                    }) { Text("Save") }
                },
                dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
            )
        }
    }
}
