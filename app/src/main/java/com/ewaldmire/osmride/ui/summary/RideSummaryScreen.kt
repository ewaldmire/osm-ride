package com.ewaldmire.osmride.ui.summary

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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

    Scaffold { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text("Ride Complete", style = MaterialTheme.typography.headlineMedium)
            Text(viewModel.routeName, style = MaterialTheme.typography.titleMedium)

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    SummaryRow("Distance", Units.formatMiles(stats.distanceMeters))
                    SummaryRow("Time", Units.formatDuration(stats.elapsedSeconds))
                    SummaryRow("Avg Speed", Units.formatMph(stats.avgSpeedMps))
                    SummaryRow("Avg Power", Units.formatWatts(stats.avgPowerWatts))
                    SummaryRow("Avg Cadence", Units.formatCadence(stats.avgCadenceRpm))
                    SummaryRow("Avg Heart Rate", Units.formatHeartRate(stats.avgHeartRateBpm))
                }
            }

            if (viewModel.hasTrackPoints) {
                Button(
                    onClick = {
                        val file = viewModel.writeGpxFile() ?: return@Button
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

            OutlinedButton(
                onClick = {
                    viewModel.discard()
                    onDone()
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Done")
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
