package com.ewaldmire.osmride.ui.ride

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.view.WindowManager
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.ewaldmire.osmride.ble.BleConnectionState
import com.ewaldmire.osmride.ble.GradeControlState
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
    val gradeControlState by viewModel.gradeControlState.collectAsState()

    LaunchedEffect(stats.state) {
        if (stats.state == RideState.FINISHED) onFinished()
    }

    // The map is edge-to-edge and its tile colors are unpredictable, so force light (white)
    // status bar icons for this screen and back them with a scrim below, rather than leaving
    // them to whatever color the map happens to show through.
    val view = LocalView.current
    DisposableEffect(Unit) {
        val window = view.context.findActivity()?.window
        val controller = window?.let { WindowCompat.getInsetsController(it, view) }
        val originalLightStatusBars = controller?.isAppearanceLightStatusBars
        controller?.isAppearanceLightStatusBars = false

        // Rides commonly run 30-90+ minutes; nothing else keeps the screen from sleeping.
        window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        onDispose {
            if (originalLightStatusBars != null) {
                controller?.isAppearanceLightStatusBars = originalLightStatusBars
            }
            window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
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

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(120.dp)
                .background(
                    Brush.verticalGradient(
                        listOf(Color.Black.copy(alpha = 0.45f), Color.Transparent),
                    ),
                ),
        )

        Surface(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .statusBarsPadding()
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
                    StatChip("Grade", Units.formatGrade(stats.currentGradePercent))
                }
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    StatChip("Cadence", Units.formatCadence(stats.currentCadenceRpm))
                    StatChip("Power", Units.formatWatts(stats.currentPowerWatts?.toDouble()))
                    StatChip("Heart Rate", Units.formatHeartRate(stats.currentHeartRateBpm))
                }
                gradeControlStatusText(gradeControlState)?.let { statusText ->
                    Text(
                        statusText,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp),
                    )
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
                .navigationBarsPadding()
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

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

/** Null when there's nothing worth telling the rider (not connected, or trainer doesn't
 * support it at all) - only surface the states that need a heads-up. */
private fun gradeControlStatusText(state: GradeControlState): String? = when (state) {
    GradeControlState.REQUESTING -> "Auto-resistance: connecting…"
    GradeControlState.ACTIVE -> "Auto-resistance: on"
    GradeControlState.REJECTED -> "Auto-resistance: unavailable"
    GradeControlState.UNAVAILABLE -> null
}

@Composable
private fun StatChip(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, style = MaterialTheme.typography.titleMedium)
        Text(label, style = MaterialTheme.typography.labelSmall)
    }
}
