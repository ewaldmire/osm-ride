package com.ewaldmire.osmride.ui.history

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import com.ewaldmire.osmride.ride.RideRecord
import com.ewaldmire.osmride.util.Units
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val dateFormatter = DateTimeFormatter.ofPattern("MMM d, yyyy · h:mm a")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RideHistoryScreen(
    onBack: () -> Unit,
    viewModel: RideHistoryViewModel = viewModel(),
) {
    val context = LocalContext.current
    val rides by viewModel.rides.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Ride History") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        if (rides.isEmpty()) {
            Column(
                modifier = Modifier.fillMaxSize().padding(padding).padding(32.dp),
                verticalArrangement = Arrangement.Center,
            ) {
                Icon(Icons.Filled.History, contentDescription = null)
                Text(
                    "No completed rides yet.",
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.padding(top = 12.dp),
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item(key = "overview") { OverviewCard(rides) }
                items(rides, key = { it.id }) { record ->
                    RideRecordCard(
                        record = record,
                        onShare = {
                            val file = viewModel.gpxFile(record)
                            val uri = FileProvider.getUriForFile(
                                context,
                                "${context.packageName}.fileprovider",
                                file,
                            )
                            val intent = Intent(Intent.ACTION_SEND).apply {
                                type = "application/gpx+xml"
                                putExtra(Intent.EXTRA_STREAM, uri)
                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            }
                            context.startActivity(Intent.createChooser(intent, "Export ride"))
                        },
                        onDelete = { viewModel.deleteRide(record.id) },
                    )
                }
            }
        }
    }
}

@Composable
private fun OverviewCard(rides: List<RideRecord>) {
    val totalDistanceMeters = rides.sumOf { it.distanceMeters }
    val totalDurationSeconds = rides.sumOf { it.durationSeconds }
    val totalKilocalories = rides.mapNotNull { it.estimatedKilocalories }.sum()
        .takeIf { rides.any { r -> r.estimatedKilocalories != null } }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("All-time", style = MaterialTheme.typography.titleMedium)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                OverviewStat("Rides", rides.size.toString())
                OverviewStat("Distance", Units.formatMiles(totalDistanceMeters))
                OverviewStat("Time", Units.formatDuration(totalDurationSeconds))
                OverviewStat("Calories", Units.formatKilocalories(totalKilocalories))
            }
        }
    }
}

@Composable
private fun OverviewStat(label: String, value: String) {
    Column {
        Text(value, style = MaterialTheme.typography.titleMedium)
        Text(label, style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
private fun RideRecordCard(record: RideRecord, onShare: () -> Unit, onDelete: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column {
                    Text(record.title, style = MaterialTheme.typography.titleMedium)
                    if (record.title != record.routeName) {
                        Text("Route: ${record.routeName}", style = MaterialTheme.typography.bodySmall)
                    }
                    Text(
                        formatDate(record.completedAtEpochMillis),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Row {
                    IconButton(onClick = onShare) {
                        Icon(Icons.Filled.Share, contentDescription = "Share GPX")
                    }
                    IconButton(onClick = onDelete) {
                        Icon(Icons.Filled.Delete, contentDescription = "Delete ride")
                    }
                }
            }
            if (record.notes.isNotBlank()) {
                Text(
                    record.notes,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(Units.formatMiles(record.distanceMeters), style = MaterialTheme.typography.bodyMedium)
                Text(Units.formatDuration(record.durationSeconds), style = MaterialTheme.typography.bodyMedium)
                Text(Units.formatMph(record.avgSpeedMps), style = MaterialTheme.typography.bodyMedium)
                Text(Units.formatKilocalories(record.estimatedKilocalories), style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

private fun formatDate(epochMillis: Long): String =
    Instant.ofEpochMilli(epochMillis).atZone(ZoneId.systemDefault()).format(dateFormatter)
