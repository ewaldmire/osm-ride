package com.ewaldmire.osmride.ui.summary

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import com.ewaldmire.osmride.util.Units

@Composable
fun RideSummaryScreen(
    onDone: () -> Unit,
    viewModel: RideSummaryViewModel = viewModel(),
) {
    val context = LocalContext.current
    val stats = viewModel.stats
    val savedRecord by viewModel.savedRecord.collectAsState()

    var title by remember { mutableStateOf(viewModel.routeName) }
    var notes by remember { mutableStateOf("") }

    Scaffold { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text("Ride Complete", style = MaterialTheme.typography.headlineMedium)

            OutlinedTextField(
                value = title,
                onValueChange = { title = it },
                label = { Text("Ride name") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )
            Text(
                "Route: ${viewModel.routeName}",
                style = MaterialTheme.typography.bodySmall,
            )
            OutlinedTextField(
                value = notes,
                onValueChange = { notes = it },
                label = { Text("Notes (optional)") },
                modifier = Modifier.fillMaxWidth(),
                minLines = 3,
            )

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    SummaryRow("Distance", Units.formatMiles(stats.distanceMeters))
                    SummaryRow("Time", Units.formatDuration(stats.elapsedSeconds))
                    SummaryRow("Avg Speed", Units.formatMph(stats.avgSpeedMps))
                    SummaryRow("Calories", Units.formatKilocalories(stats.estimatedKilocalories))
                    SummaryRow("Avg Power", Units.formatWatts(stats.avgPowerWatts))
                    SummaryRow("Avg Cadence", Units.formatCadence(stats.avgCadenceRpm))
                    SummaryRow("Avg Heart Rate", Units.formatHeartRate(stats.avgHeartRateBpm))
                }
            }

            Text(
                "Saved to ride history.",
                style = MaterialTheme.typography.bodySmall,
            )

            Button(
                onClick = {
                    viewModel.saveTitleAndNotes(title, notes)
                    viewModel.clearActiveRideEngine()
                    onDone()
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Done")
            }

            if (viewModel.hasTrackPoints) {
                OutlinedButton(
                    enabled = savedRecord != null,
                    onClick = {
                        val file = viewModel.gpxFileToShare() ?: return@OutlinedButton
                        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
                        val intent = Intent(Intent.ACTION_SEND).apply {
                            type = "application/gpx+xml"
                            putExtra(Intent.EXTRA_STREAM, uri)
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }
                        context.startActivity(Intent.createChooser(intent, "Export ride"))
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Export / Share GPX")
                }
            }
        }
    }
}

@Composable
private fun SummaryRow(label: String, value: String) {
    Column {
        Text(label, style = MaterialTheme.typography.labelMedium)
        Text(value, style = MaterialTheme.typography.titleLarge)
    }
}
