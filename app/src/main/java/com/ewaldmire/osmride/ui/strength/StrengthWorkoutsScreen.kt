package com.ewaldmire.osmride.ui.strength

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
import androidx.compose.material.icons.filled.Delete
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ewaldmire.osmride.strength.StrengthExercise
import com.ewaldmire.osmride.strength.StrengthWorkoutEntry
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val dateFormatter = DateTimeFormatter.ofPattern("MMM d, yyyy")

/**
 * Body content only, no Scaffold/TopAppBar of its own - embedded inside WorkoutsHubScreen
 * alongside WorkoutsListContent. Purely a display of what fosslift has shared in - no manual
 * entry, no editing; fosslift stays the source of truth for actual strength training.
 */
@Composable
fun StrengthWorkoutsContent(padding: PaddingValues, viewModel: StrengthWorkoutsViewModel) {
    val entries by viewModel.entries.collectAsState()

    if (entries.isEmpty()) {
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(32.dp),
            verticalArrangement = Arrangement.Center,
        ) {
            Icon(Icons.Filled.FitnessCenter, contentDescription = null)
            Text(
                "No strength workouts shared yet. Use the share button after a workout in your " +
                    "other app to send it here.",
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
            items(entries, key = { it.id }) { entry ->
                StrengthWorkoutCard(entry = entry, onDelete = { viewModel.deleteEntry(entry.id) })
            }
        }
    }
}

@Composable
private fun StrengthWorkoutCard(entry: StrengthWorkoutEntry, onDelete: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(entry.workoutName ?: "Workout", style = MaterialTheme.typography.titleMedium)
                    Text(formatDate(entry.recordedAtEpochMillis), style = MaterialTheme.typography.bodySmall)
                }
                IconButton(onClick = onDelete) {
                    Icon(Icons.Filled.Delete, contentDescription = "Delete workout")
                }
            }
            entry.exercises.forEach { exercise -> ExerciseRow(exercise) }
        }
    }
}

@Composable
private fun ExerciseRow(exercise: StrengthExercise) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(
            exercise.name,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f).padding(end = 8.dp),
        )
        Text(
            "${exercise.topSetReps} × ${formatWeight(exercise.topSetWeightLbs)}",
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

private fun formatWeight(lbs: Double): String =
    if (lbs == lbs.toInt().toDouble()) "${lbs.toInt()} lb" else "$lbs lb"

private fun formatDate(epochMillis: Long): String =
    Instant.ofEpochMilli(epochMillis).atZone(ZoneId.systemDefault()).format(dateFormatter)
