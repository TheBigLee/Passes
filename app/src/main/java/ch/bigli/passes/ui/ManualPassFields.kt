@file:OptIn(ExperimentalMaterial3Api::class)

package ch.bigli.passes.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import ch.bigli.passes.domain.TransitType
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneOffset

/** A read-only text field that opens a [DatePickerDialog] on tap. */
@Composable
private fun DateField(label: String, date: LocalDate?, onDateChange: (LocalDate) -> Unit) {
    var showPicker by remember { mutableStateOf(false) }
    Box(Modifier.fillMaxWidth()) {
        OutlinedTextField(
            value = date?.format(MANUAL_PASS_DATE_FORMAT) ?: "",
            onValueChange = {},
            readOnly = true,
            enabled = false,
            label = { Text(label) },
            colors = OutlinedTextFieldDefaults.colors(
                disabledTextColor = OutlinedTextFieldDefaults.colors().unfocusedTextColor,
                disabledBorderColor = OutlinedTextFieldDefaults.colors().unfocusedIndicatorColor,
                disabledLabelColor = OutlinedTextFieldDefaults.colors().unfocusedLabelColor,
            ),
            modifier = Modifier.fillMaxWidth(),
        )
        Box(Modifier.matchParentSize().clickable { showPicker = true })
    }
    if (showPicker) {
        // DatePickerState works in UTC start-of-day millis - converting back with
        // ZoneOffset.UTC (not the system zone) avoids landing on the wrong day.
        val initialMillis = date?.atStartOfDay(ZoneOffset.UTC)?.toInstant()?.toEpochMilli()
        val state = rememberDatePickerState(initialSelectedDateMillis = initialMillis)
        DatePickerDialog(
            onDismissRequest = { showPicker = false },
            confirmButton = {
                TextButton(onClick = {
                    state.selectedDateMillis?.let {
                        onDateChange(Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate())
                    }
                    showPicker = false
                }) { Text("OK") }
            },
            dismissButton = { TextButton(onClick = { showPicker = false }) { Text("Cancel") } },
        ) { DatePicker(state = state) }
    }
}

/** A read-only text field that opens a [TimePicker] wrapped in an [AlertDialog] on tap. */
@Composable
private fun TimeField(label: String, time: LocalTime?, onTimeChange: (LocalTime) -> Unit) {
    var showPicker by remember { mutableStateOf(false) }
    Box(Modifier.fillMaxWidth()) {
        OutlinedTextField(
            value = time?.format(MANUAL_PASS_TIME_FORMAT) ?: "",
            onValueChange = {},
            readOnly = true,
            enabled = false,
            label = { Text(label) },
            colors = OutlinedTextFieldDefaults.colors(
                disabledTextColor = OutlinedTextFieldDefaults.colors().unfocusedTextColor,
                disabledBorderColor = OutlinedTextFieldDefaults.colors().unfocusedIndicatorColor,
                disabledLabelColor = OutlinedTextFieldDefaults.colors().unfocusedLabelColor,
            ),
            modifier = Modifier.fillMaxWidth(),
        )
        Box(Modifier.matchParentSize().clickable { showPicker = true })
    }
    if (showPicker) {
        val state = rememberTimePickerState(
            initialHour = time?.hour ?: 12,
            initialMinute = time?.minute ?: 0,
            is24Hour = true,
        )
        AlertDialog(
            onDismissRequest = { showPicker = false },
            confirmButton = {
                TextButton(onClick = {
                    onTimeChange(LocalTime.of(state.hour, state.minute))
                    showPicker = false
                }) { Text("OK") }
            },
            dismissButton = { TextButton(onClick = { showPicker = false }) { Text("Cancel") } },
            text = { TimePicker(state = state) },
        )
    }
}

@Composable
fun EventFields(draft: EventDraft, onDraftChange: (EventDraft) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        OutlinedTextField(
            value = draft.eventName,
            onValueChange = { onDraftChange(draft.copy(eventName = it)) },
            label = { Text("Event name") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = draft.location,
            onValueChange = { onDraftChange(draft.copy(location = it)) },
            label = { Text("Location") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        DateField("Date", draft.date) { onDraftChange(draft.copy(date = it)) }
        TimeField("Time", draft.time) { onDraftChange(draft.copy(time = it)) }
    }
}

@Composable
fun BoardingFields(draft: BoardingDraft, onDraftChange: (BoardingDraft) -> Unit) {
    var transitExpanded by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        OutlinedTextField(
            value = draft.from,
            onValueChange = { onDraftChange(draft.copy(from = it)) },
            label = { Text("From") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = draft.to,
            onValueChange = { onDraftChange(draft.copy(to = it)) },
            label = { Text("To") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        ExposedDropdownMenuBox(expanded = transitExpanded, onExpandedChange = { transitExpanded = it }) {
            OutlinedTextField(
                value = draft.transitType.name,
                onValueChange = {},
                readOnly = true,
                label = { Text("Transit mode") },
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = transitExpanded) },
                modifier = Modifier.menuAnchor(MenuAnchorType.PrimaryNotEditable).fillMaxWidth(),
            )
            ExposedDropdownMenu(expanded = transitExpanded, onDismissRequest = { transitExpanded = false }) {
                TransitType.entries.forEach { t ->
                    DropdownMenuItem(
                        text = { Text(t.name) },
                        onClick = { onDraftChange(draft.copy(transitType = t)); transitExpanded = false },
                    )
                }
            }
        }
        DateField("Date", draft.date) { onDraftChange(draft.copy(date = it)) }
        TimeField("Boards", draft.time) { onDraftChange(draft.copy(time = it)) }
    }
}

@Composable
fun LoyaltyFields(draft: LoyaltyDraft, onDraftChange: (LoyaltyDraft) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        OutlinedTextField(
            value = draft.memberName,
            onValueChange = { onDraftChange(draft.copy(memberName = it)) },
            label = { Text("Member name") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = draft.details,
            onValueChange = { onDraftChange(draft.copy(details = it)) },
            label = { Text("Details") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
fun GenericFieldsForm(draft: GenericDraft, onDraftChange: (GenericDraft) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        OutlinedTextField(
            value = draft.description,
            onValueChange = { onDraftChange(draft.copy(description = it)) },
            label = { Text("Description") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        DateField("Date", draft.date) { onDraftChange(draft.copy(date = it)) }
        TimeField("Time", draft.time) { onDraftChange(draft.copy(time = it)) }
    }
}
