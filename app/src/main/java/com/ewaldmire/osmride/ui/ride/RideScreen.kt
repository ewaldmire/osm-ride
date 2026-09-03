package com.ewaldmire.osmride.ui.ride

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.ewaldmire.osmride.ble.BleConnectionState
import com.ewaldmire.osmride.ride.RideState
import com.ewaldmire.osmride.util.Units

@Composable
fun RideScreen(
    routeId: String,
    onFinished: () -> Unit,
    viewModel: RideViewModel = viewModel(),
) {
    LaunchedEffect(routeId) { viewModel.loadRoute(routeId) }

    val route by viewModel.route.collectAsState()
    val stats by viewModel.stats.collectAsState()
    val trainerConnected by viewModel.trainerConnectionState.collectAsState()

    LaunchedEffect(stats.state) {
        if (stats.state == RideState.FINISHED) onFinished()
    }

    val currentRoute = route
    Box(modifier = Modifier.fillMaxSize()) {
        if (currentRoute != null) {
            BikeMapView(
                route = currentRoute,
                position = stats.position,
                followBike = true,
                modifier = Modifier.fillMaxSize(),
            )
        }

        Surface(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .padding(12.dp),
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
            shadowElevation = 4.dp,
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                LinearProgressIndicator(
                    progress = { stats.progressFraction.toFloat() },
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    StatChip("Distance", Units.formatMiles(stats.distanceMeters))
                    StatChip("Time", Units.formatDuration(stats.elapsedSeconds))
                    StatChip("Speed", Units.formatMph(stats.currentSpeedMps))
                }
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    StatChip("Cadence", Units.formatCadence(stats.currentCadenceRpm))
                    StatChip("Power", Units.formatWatts(stats.currentPowerWatts?.toDouble()))
                    StatChip("Heart Rate", Units.formatHeartRate(stats.currentHeartRateBpm))
                }
                if (trainerConnected != BleConnectionState.CONNECTED) {
                    Text(
                        "Trainer not connected — pair it first to track distance.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            }
        }

        Card(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(16.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                when (stats.state) {
                    RideState.IDLE -> {
                        Button(onClick = viewModel::start, modifier = Modifier.weight(1f)) {
                            Text("Start Ride")
                        }
                    }
                    RideState.RIDING -> {
                        OutlinedButton(onClick = viewModel::pause, modifier = Modifier.weight(1f)) {
                            Text("Pause")
                        }
                        Button(onClick = viewModel::finishManually, modifier = Modifier.weight(1f)) {
                            Text("Finish")
                        }
                    }
                    RideState.PAUSED -> {
                        Button(onClick = viewModel::start, modifier = Modifier.weight(1f)) {
                            Text("Resume")
                        }
                        Button(onClick = viewModel::finishManually, modifier = Modifier.weight(1f)) {
                            Text("Finish")
                        }
                    }
                    RideState.FINISHED -> Unit
                }
            }
        }
    }
}

@Composable
private fun StatChip(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, style = MaterialTheme.typography.titleMedium)
        Text(label, style = MaterialTheme.typography.labelSmall)
    }
}
