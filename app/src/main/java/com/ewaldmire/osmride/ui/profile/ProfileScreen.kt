package com.ewaldmire.osmride.ui.profile

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.MonitorWeight
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.ewaldmire.osmride.ui.settings.SettingsPrefs
import com.ewaldmire.osmride.util.Units
import com.ewaldmire.osmride.weight.WaistEntry
import com.ewaldmire.osmride.weight.WeightEntry
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val dateFormatter = DateTimeFormatter.ofPattern("MMM d, yyyy")

/** Personal "about you" data used elsewhere in the app - FTP (for %FTP-based workouts) and
 * weight/waist (their own tracking screen, see BodyMetricsScreen) - as opposed to Settings, which
 * is just app configuration (Bluetooth pairing). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileScreen(
    onOpenBodyMetrics: () -> Unit,
    viewModel: ProfileViewModel = viewModel(),
) {
    val context = LocalContext.current
    var ftpText by remember { mutableStateOf(SettingsPrefs.getFtpWatts(context)?.toString() ?: "") }
    val weightEntries by viewModel.weightEntries.collectAsState()
    val waistEntries by viewModel.waistEntries.collectAsState()
    val latestWeight = weightEntries.firstOrNull()
    val latestWaist = waistEntries.firstOrNull()

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Profile")
                        Text(
                            "Personal info used to tailor your training",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier.padding(padding).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Card(onClick = onOpenBodyMetrics, modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                        Icon(Icons.Filled.MonitorWeight, contentDescription = null, modifier = Modifier.padding(end = 16.dp))
                        Column {
                            Text("Body Metrics", style = MaterialTheme.typography.titleMedium)
                            Text(
                                bodyMetricsSummary(latestWeight, latestWaist),
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                    Icon(Icons.Filled.ChevronRight, contentDescription = null)
                }
            }

            Text("Training", style = MaterialTheme.typography.titleMedium)
            OutlinedTextField(
                value = ftpText,
                onValueChange = { text ->
                    ftpText = text.filter { it.isDigit() }
                    SettingsPrefs.setFtpWatts(context, ftpText.toIntOrNull())
                },
                label = { Text("FTP (watts)") },
                supportingText = { Text("Needed to convert %FTP-based .mrc/.zwo workouts to watts") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

private fun formatDate(epochMillis: Long): String =
    Instant.ofEpochMilli(epochMillis).atZone(ZoneId.systemDefault()).format(dateFormatter)

private fun bodyMetricsSummary(latestWeight: WeightEntry?, latestWaist: WaistEntry?): String {
    if (latestWeight == null && latestWaist == null) return "No measurements logged yet"
    val parts = mutableListOf<String>()
    latestWeight?.let { parts.add("Weight ${Units.formatWeightLbs(it.weightKg)}") }
    latestWaist?.let { parts.add("Waist ${Units.formatWaistCm(it.waistCm)}") }
    return parts.joinToString(" · ")
}
