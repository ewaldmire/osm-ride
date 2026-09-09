package com.ewaldmire.osmride.ui.settings

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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FitnessCenter
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.ewaldmire.osmride.ride.Workout
import com.ewaldmire.osmride.ui.workout.WorkoutProfileChart
import com.ewaldmire.osmride.util.Units
import kotlin.math.roundToInt

/**
 * Body content only, no Scaffold/TopAppBar of its own - embedded inside WorkoutsHubScreen
 * alongside StrengthWorkoutsContent under one shared top bar (with a Cycling/Weights tab row).
 * Create/Import actions and the import launcher/snackbar live on the hub, same split as
 * RideHubScreen/RoutesListContent. See WorkoutsHubScreen.kt.
 */
@Composable
fun WorkoutsListContent(
    padding: PaddingValues,
    viewModel: WorkoutsListViewModel,
    onEditWorkout: (String) -> Unit,
) {
    val workouts by viewModel.workouts.collectAsState()

    if (workouts.isEmpty()) {
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(32.dp),
            verticalArrangement = Arrangement.Center,
        ) {
            Icon(Icons.Filled.FitnessCenter, contentDescription = null)
            Text(
                "No workouts yet. Use the buttons above to create or import one.",
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
            items(workouts, key = { it.id }) { workout ->
                WorkoutCard(
                    workout = workout,
                    onEdit = { onEditWorkout(workout.id) },
                    onDelete = { viewModel.deleteWorkout(workout.id) },
                )
            }
        }
    }
}

@Composable
private fun WorkoutCard(
    workout: Workout,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(workout.name, style = MaterialTheme.typography.titleMedium)
                    val avgWatts = workout.averageWatts()
                    Text(
                        Units.formatDuration(workout.totalDurationSeconds) +
                            (avgWatts?.let { " · avg $it W" } ?: ""),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                Row {
                    // Opens the full workout creator, which already has its own name field -
                    // covers renaming too, same simplification as RoutesListScreen's RouteCard.
                    IconButton(onClick = onEdit) {
                        Icon(Icons.Filled.Edit, contentDescription = "Edit workout")
                    }
                    IconButton(onClick = onDelete) {
                        Icon(Icons.Filled.Delete, contentDescription = "Delete workout")
                    }
                }
            }
            WorkoutProfileChart(
                workout = workout,
                modifier = Modifier.fillMaxWidth().height(56.dp).padding(top = 8.dp),
            )
        }
    }
}

private fun Workout.averageWatts(): Int? {
    var weightedSum = 0.0
    var totalSeconds = 0.0
    for (seg in segments) {
        val startWatts = seg.startWatts ?: continue
        val endWatts = seg.endWatts ?: continue
        val duration = (seg.endSeconds - seg.startSeconds).toDouble()
        if (duration <= 0) continue
        weightedSum += (startWatts + endWatts) / 2.0 * duration
        totalSeconds += duration
    }
    return if (totalSeconds > 0) (weightedSum / totalSeconds).roundToInt() else null
}
