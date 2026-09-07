package com.ewaldmire.osmride.ui.summary

import android.content.Intent
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DirectionsBike
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
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
    val thumbnailFile = remember { viewModel.thumbnailFile() }
    val bitmap = remember(thumbnailFile) {
        thumbnailFile?.let { file -> BitmapFactory.decodeFile(file.path)?.asImageBitmap() }
    }

    Scaffold { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Ride Complete", style = MaterialTheme.typography.headlineSmall)

            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                // Same 5:3 aspect and Fit scaling as RouteCard/RideRecordCard's thumbnail, just
                // smaller (120x72, next to the name field rather than a whole card of its own) -
                // lets the rider see the route they just rode before they save, rather than only
                // seeing it later from History.
                Box(modifier = Modifier.size(width = 120.dp, height = 72.dp)) {
                    if (bitmap != null) {
                        Image(
                            bitmap = bitmap,
                            contentDescription = null,
                            contentScale = ContentScale.Fit,
                            modifier = Modifier.fillMaxSize(),
                        )
                    } else {
                        Box(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceVariant)) {
                            Icon(
                                Icons.Filled.DirectionsBike,
                                contentDescription = null,
                                modifier = Modifier.align(Alignment.Center).size(28.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.Center) {
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
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }
            OutlinedTextField(
                value = notes,
                onValueChange = { notes = it },
                label = { Text("Notes (optional)") },
                modifier = Modifier.fillMaxWidth(),
                minLines = 2,
            )

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    val summaryStats = listOf(
                        "Distance" to Units.formatMiles(stats.distanceMeters),
                        "Time" to Units.formatDuration(stats.elapsedSeconds),
                        "Avg Speed" to Units.formatMph(stats.avgSpeedMps),
                        "Calories" to Units.formatKilocalories(stats.estimatedKilocalories),
                        "Avg Power" to Units.formatWatts(stats.avgPowerWatts),
                        "Avg Cadence" to Units.formatCadence(stats.avgCadenceRpm),
                        "Avg Heart Rate" to Units.formatHeartRate(stats.avgHeartRateBpm),
                    )
                    // 3 columns keeps all 7 stats to 3 short rows instead of 7 full-width ones -
                    // the single biggest contributor to this screen needing a scroll before.
                    summaryStats.chunked(3).forEach { rowStats ->
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            rowStats.forEach { (label, value) -> SummaryStat(label, value, Modifier.weight(1f)) }
                            repeat(3 - rowStats.size) { Spacer(modifier = Modifier.weight(1f)) }
                        }
                    }
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
private fun SummaryStat(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, style = MaterialTheme.typography.titleMedium)
        Text(label, style = MaterialTheme.typography.labelSmall)
    }
}
