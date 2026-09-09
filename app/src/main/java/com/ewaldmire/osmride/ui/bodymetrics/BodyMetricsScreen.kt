package com.ewaldmire.osmride.ui.bodymetrics

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.MonitorWeight
import androidx.compose.material3.Card
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.ewaldmire.osmride.util.Units
import com.ewaldmire.osmride.weight.WaistEntry
import com.ewaldmire.osmride.weight.WeightEntry
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

private val dateFormatter = DateTimeFormatter.ofPattern("MMM d, yyyy")

/** Material3's DatePicker works in UTC internally (selectedDateMillis is UTC midnight of the
 * chosen day) - converting straight to epoch millis and using it as a real timestamp would shift
 * the date by a day in any negative-UTC-offset zone (e.g. all of the Americas). Going through
 * LocalDate and re-anchoring to local noon avoids that day-shift entirely. */
private fun LocalDate.toUtcMidnightMillis(): Long = atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
private fun localDateFromUtcMidnightMillis(millis: Long): LocalDate =
    Instant.ofEpochMilli(millis).atZone(ZoneOffset.UTC).toLocalDate()
private fun LocalDate.toLocalNoonEpochMillis(): Long =
    atTime(12, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
private fun formatDate(epochMillis: Long): String =
    Instant.ofEpochMilli(epochMillis).atZone(ZoneId.systemDefault()).format(dateFormatter)

/**
 * Weight and waist tracking together, behind a tab switcher (same pattern as the Ride hub) -
 * waist is the primary "visible abs" signal per this feature's own spec, at least as important as
 * weight, so it gets equal billing rather than being buried as an afterthought.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BodyMetricsScreen(
    onBack: () -> Unit,
    viewModel: BodyMetricsViewModel = viewModel(),
) {
    val weightEntries by viewModel.weightEntries.collectAsState()
    val waistEntries by viewModel.waistEntries.collectAsState()
    var selectedTab by rememberSaveable { mutableStateOf(0) }

    val weightPoints = remember(weightEntries) { weightTrendPoints(weightEntries) }
    val waistPoints = remember(waistEntries) { waistTrendPoints(waistEntries) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Body Metrics")
                        Text(
                            "Track your weight and waist over time",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            WeeklyCheckInCard(
                weightDelta = weeklyDelta(weightPoints),
                waistDelta = weeklyDelta(waistPoints),
                modifier = Modifier.fillMaxWidth().padding(16.dp),
            )
            TabRow(selectedTabIndex = selectedTab) {
                Tab(selected = selectedTab == 0, onClick = { selectedTab = 0 }, text = { Text("Weight") })
                Tab(selected = selectedTab == 1, onClick = { selectedTab = 1 }, text = { Text("Waist") })
            }
            if (selectedTab == 0) {
                WeightTab(
                    entries = weightEntries,
                    points = weightPoints,
                    onAdd = viewModel::addWeightEntry,
                    onDelete = viewModel::deleteWeightEntry,
                )
            } else {
                WaistTab(
                    viewModel = viewModel,
                    entries = waistEntries,
                    points = waistPoints,
                    onAdd = viewModel::addWaistEntry,
                    onDelete = viewModel::deleteWaistEntry,
                )
            }
        }
    }
}

@Composable
private fun WeeklyCheckInCard(weightDelta: WeeklyDelta?, waistDelta: WeeklyDelta?, modifier: Modifier = Modifier) {
    Card(modifier = modifier) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("This Week", style = MaterialTheme.typography.titleMedium)
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                WeeklyStat(label = "Weight", delta = weightDelta, unit = "lb", modifier = Modifier.weight(1f))
                WeeklyStat(label = "Waist", delta = waistDelta, unit = "in", modifier = Modifier.weight(1f))
            }
            // A shrinking waist at a flat weight is still real recomposition progress, not a
            // plateau - worth calling out explicitly since the scale alone would read as nothing
            // happening.
            val weightFlat = weightDelta?.delta?.let { kotlin.math.abs(it) < 0.5 } ?: false
            val waistDown = (waistDelta?.delta ?: 0.0) <= -0.25
            if (weightFlat && waistDown) {
                Text(
                    "Waist is down even though weight is flat — still working.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}

@Composable
private fun WeeklyStat(label: String, delta: WeeklyDelta?, unit: String, modifier: Modifier = Modifier) {
    Column(modifier = modifier) {
        Text(label, style = MaterialTheme.typography.labelSmall)
        if (delta == null) {
            Text("No data yet", style = MaterialTheme.typography.bodyMedium)
        } else {
            Text(String.format(Locale.US, "%.1f %s", delta.currentAvg, unit), style = MaterialTheme.typography.titleMedium)
            val change = delta.delta
            Text(
                if (change == null) {
                    "Not enough history yet"
                } else {
                    String.format(Locale.US, "%+.1f %s this week", change, unit)
                },
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun WeightTab(
    entries: List<WeightEntry>,
    points: List<TrendPoint>,
    onAdd: (weightLbs: Double, recordedAtEpochMillis: Long) -> Unit,
    onDelete: (id: String) -> Unit,
) {
    var input by remember { mutableStateOf("") }
    var selectedDate by remember { mutableStateOf(LocalDate.now()) }
    var showDatePicker by remember { mutableStateOf(false) }

    if (showDatePicker) {
        MeasurementDatePickerDialog(selectedDate, onSelect = { selectedDate = it }, onDismiss = { showDatePicker = false })
    }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        MeasurementEntryForm(
            label = "Weight (lb)",
            input = input,
            onInputChange = { input = it },
            selectedDate = selectedDate,
            onDatePickerRequested = { showDatePicker = true },
            onLog = {
                val value = input.toDoubleOrNull()
                if (value != null && value > 0) {
                    onAdd(value, selectedDate.toLocalNoonEpochMillis())
                    input = ""
                    selectedDate = LocalDate.now()
                }
            },
        )

        if (entries.isEmpty()) {
            EmptyMeasurementState("No weigh-ins logged yet.")
        } else {
            if (points.size >= 2) {
                TrendChart(
                    points = points,
                    modifier = Modifier.fillMaxWidth().height(120.dp).padding(top = 16.dp, bottom = 8.dp),
                )
            }
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(top = 8.dp),
                contentPadding = PaddingValues(vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(entries, key = { it.id }) { entry ->
                    MeasurementEntryRow(
                        valueText = Units.formatWeightLbs(entry.weightKg),
                        dateText = formatDate(entry.recordedAtEpochMillis),
                        onDelete = { onDelete(entry.id) },
                    )
                }
            }
        }
    }
}

@Composable
private fun WaistTab(
    viewModel: BodyMetricsViewModel,
    entries: List<WaistEntry>,
    points: List<TrendPoint>,
    onAdd: (waistInches: Double, recordedAtEpochMillis: Long) -> Unit,
    onDelete: (id: String) -> Unit,
) {
    var input by remember { mutableStateOf("") }
    var selectedDate by remember { mutableStateOf(LocalDate.now()) }
    var showDatePicker by remember { mutableStateOf(false) }

    if (showDatePicker) {
        MeasurementDatePickerDialog(selectedDate, onSelect = { selectedDate = it }, onDismiss = { showDatePicker = false })
    }

    LazyColumn(modifier = Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            MeasurementEntryForm(
                label = "Waist (in)",
                input = input,
                onInputChange = { input = it },
                selectedDate = selectedDate,
                onDatePickerRequested = { showDatePicker = true },
                onLog = {
                    val value = input.toDoubleOrNull()
                    if (value != null && value > 0) {
                        onAdd(value, selectedDate.toLocalNoonEpochMillis())
                        input = ""
                        selectedDate = LocalDate.now()
                    }
                },
            )
        }
        item { BodyFatEstimateCard(viewModel) }
        if (entries.isEmpty()) {
            item { EmptyMeasurementState("No waist measurements logged yet.") }
        } else {
            if (points.size >= 2) {
                item {
                    TrendChart(
                        points = points,
                        modifier = Modifier.fillMaxWidth().height(120.dp).padding(top = 8.dp, bottom = 8.dp),
                    )
                }
            }
            items(entries, key = { it.id }) { entry ->
                MeasurementEntryRow(
                    valueText = Units.formatWaistInches(entry.waistCm),
                    dateText = formatDate(entry.recordedAtEpochMillis),
                    onDelete = { onDelete(entry.id) },
                )
            }
        }
    }
}

@Composable
private fun BodyFatEstimateCard(viewModel: BodyMetricsViewModel) {
    var neckInput by remember { mutableStateOf(viewModel.getNeckInches()?.let { String.format(Locale.US, "%.1f", it) } ?: "") }
    var heightInput by remember { mutableStateOf(viewModel.getHeightInches()?.let { String.format(Locale.US, "%.1f", it) } ?: "") }
    val bodyFatPercent = viewModel.estimateBodyFatPercent()

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Body Fat Estimate", style = MaterialTheme.typography.titleMedium)
            Text(
                "Navy method, from your latest waist measurement plus neck and height below.",
                style = MaterialTheme.typography.bodySmall,
            )
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = neckInput,
                    onValueChange = { text ->
                        neckInput = text.filter { it.isDigit() || it == '.' }
                        viewModel.setNeckInches(neckInput.toDoubleOrNull())
                    },
                    label = { Text("Neck (in)") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
                OutlinedTextField(
                    value = heightInput,
                    onValueChange = { text ->
                        heightInput = text.filter { it.isDigit() || it == '.' }
                        viewModel.setHeightInches(heightInput.toDoubleOrNull())
                    },
                    label = { Text("Height (in)") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
            }
            Text(
                if (bodyFatPercent != null) {
                    String.format(Locale.US, "Estimated body fat: %.1f%%", bodyFatPercent)
                } else {
                    "Enter a waist measurement, neck, and height to see an estimate."
                },
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

@Composable
private fun MeasurementEntryForm(
    label: String,
    input: String,
    onInputChange: (String) -> Unit,
    selectedDate: LocalDate,
    onDatePickerRequested: () -> Unit,
    onLog: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = onDatePickerRequested, modifier = Modifier.fillMaxWidth()) {
            Text(if (selectedDate == LocalDate.now()) "Today" else selectedDate.format(dateFormatter))
        }
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = input,
                onValueChange = { text -> onInputChange(text.filter { it.isDigit() || it == '.' }) },
                label = { Text(label) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onLog) { Text("Log") }
        }
    }
}

@Composable
private fun MeasurementEntryRow(valueText: String, dateText: String, onDelete: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column {
                Text(valueText, style = MaterialTheme.typography.titleMedium)
                Text(dateText, style = MaterialTheme.typography.bodySmall)
            }
            IconButton(onClick = onDelete) {
                Icon(Icons.Filled.Delete, contentDescription = "Delete entry")
            }
        }
    }
}

@Composable
private fun EmptyMeasurementState(message: String) {
    Column(
        modifier = Modifier.fillMaxSize().padding(top = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(Icons.Filled.MonitorWeight, contentDescription = null)
        Text(message, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(top = 12.dp))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MeasurementDatePickerDialog(selectedDate: LocalDate, onSelect: (LocalDate) -> Unit, onDismiss: () -> Unit) {
    val datePickerState = rememberDatePickerState(initialSelectedDateMillis = selectedDate.toUtcMidnightMillis())
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(
                onClick = {
                    datePickerState.selectedDateMillis?.let { millis -> onSelect(localDateFromUtcMidnightMillis(millis)) }
                    onDismiss()
                },
            ) { Text("OK") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    ) {
        DatePicker(state = datePickerState)
    }
}
