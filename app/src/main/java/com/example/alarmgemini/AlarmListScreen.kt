package com.example.alarmgemini

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.clickable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import java.time.format.DateTimeFormatter
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults

@Composable
fun AlarmListScreen(
    viewModel: AlarmListViewModel = viewModel(),
    onOpenChat: () -> Unit = {}
) {
    val alarms by viewModel.alarms.collectAsState()
    var showAddSheet by remember { mutableStateOf(false) }

    Scaffold(
        floatingActionButton = {
            Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                androidx.compose.material3.SmallFloatingActionButton(onClick = onOpenChat) {
                    Icon(Icons.Default.Keyboard, contentDescription = "Chat")
                }
                FloatingActionButton(onClick = { showAddSheet = true }) {
                    Icon(Icons.Default.Add, contentDescription = "Add alarm")
                }
            }
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(WindowInsets.systemBars.asPaddingValues())
        ) {
            items(alarms, key = { it.id }) { alarm ->
                AlarmRow(
                    alarm = alarm,
                    onToggle = { enabled -> viewModel.toggle(alarm.id, enabled) },
                    onTimeChange = { newDt -> viewModel.updateTime(alarm.id, newDt) }
                )
                HorizontalDivider()
            }
        }
    }

    if (showAddSheet) {
        AddAlarmSheet(onAdd = { time, days ->
            viewModel.addAlarm(time, days)
            showAddSheet = false
        }, onDismiss = { showAddSheet = false })
    }
}

private val timeFormatter = DateTimeFormatter.ofPattern("h:mm")
private val ampmFormatter = DateTimeFormatter.ofPattern("a")

@Composable
private fun AlarmRow(
    alarm: AlarmUiModel,
    onToggle: (Boolean) -> Unit,
    onTimeChange: (java.time.LocalDateTime) -> Unit
) {
    val context = LocalContext.current
    val showPicker = remember { mutableStateOf(false) }

    Column(modifier = Modifier
        .fillMaxWidth()
        .padding(horizontal = 16.dp, vertical = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Row {
                    Text(
                        text = alarm.time.format(timeFormatter),
                        style = MaterialTheme.typography.displaySmall.copy(fontWeight = FontWeight.SemiBold),
                        color = if (alarm.enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f),
                        modifier = Modifier.clickable { showPicker.value = true }
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = alarm.time.format(ampmFormatter),
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                    )
                    Spacer(Modifier.width(8.dp))
                    // ID chip
                    AssistChip(
                        onClick = {},
                        label = { Text("#${alarm.id}") },
                        enabled = false,
                        shape = MaterialTheme.shapes.small,
                        border = null,
                        colors = AssistChipDefaults.assistChipColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
                    )
                }
                // next ring info or recurrence
                if (alarm.recurringDays.isNotEmpty()) {
                    Text(
                        text = alarm.recurringDays.joinToString {
                            it.name.substring(0,3).replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                    )
                }
            }
            Switch(checked = alarm.enabled, onCheckedChange = onToggle)
        }
        IconButton(onClick = { /* TODO: expand details */ }) {
            Icon(Icons.Default.ExpandMore, contentDescription = "Expand")
        }

        if (showPicker.value) {
            val initialHour = alarm.time.hour
            val initialMinute = alarm.time.minute
            val timePickerDialog = android.app.TimePickerDialog(
                context,
                { _, hour, min ->
                    val newDateTime = alarm.dateTime
                        .withHour(hour)
                        .withMinute(min)
                        .withSecond(0)
                        .withNano(0)
                    onTimeChange(newDateTime)
                },
                initialHour,
                initialMinute,
                false
            )
            // Show dialog imperatively then reset flag
            timePickerDialog.setOnDismissListener { showPicker.value = false }
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                timePickerDialog.show()
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
private fun AddAlarmSheet(onAdd: (java.time.LocalTime, List<java.time.DayOfWeek>) -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    var hourText by rememberSaveable { mutableStateOf("") }
    var minuteText by rememberSaveable { mutableStateOf("") }
    val selectedDays = remember { mutableStateMapOf<java.time.DayOfWeek, Boolean>() }
    // initialize days map
    java.time.DayOfWeek.values().forEach { if (it !in selectedDays) selectedDays[it] = false }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add Alarm") },
        text = {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = hourText,
                        onValueChange = { hourText = it.filter { ch -> ch.isDigit() }.take(2) },
                        label = { Text("HH") },
                        keyboardOptions = KeyboardOptions.Default.copy(keyboardType = KeyboardType.Number),
                        modifier = Modifier.width(72.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                    OutlinedTextField(
                        value = minuteText,
                        onValueChange = { minuteText = it.filter { ch -> ch.isDigit() }.take(2) },
                        label = { Text("MM") },
                        keyboardOptions = KeyboardOptions.Default.copy(keyboardType = KeyboardType.Number),
                        modifier = Modifier.width(72.dp)
                    )
                }

                Spacer(Modifier.height(12.dp))
                Text("Repeat on:")
                Spacer(Modifier.height(4.dp))
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    java.time.DayOfWeek.values().forEach { day ->
                        val checked = selectedDays[day] == true
                        FilterChip(
                            selected = checked,
                            onClick = { selectedDays[day] = !checked },
                            label = { Text(day.name.substring(0,3)) },
                            colors = FilterChipDefaults.filterChipColors()
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val hour = hourText.toIntOrNull()
                val minute = minuteText.toIntOrNull()
                if (hour != null && minute != null && hour in 0..23 && minute in 0..59) {
                    onAdd(java.time.LocalTime.of(hour, minute), selectedDays.filter { it.value }.keys.toList())
                }
            }) { Text("Add") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
} 