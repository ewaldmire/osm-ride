package com.ewaldmire.osmride.ui.settings

import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FitnessCenter
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.ewaldmire.osmride.ride.Workout
import com.ewaldmire.osmride.ui.workout.WorkoutProfileChart
import com.ewaldmire.osmride.util.Units
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WorkoutsListScreen(
    onBack: () -> Unit,
    onCreateWorkout: () -> Unit,
    onEditWorkout: (String) -> Unit,
    viewModel: WorkoutsListViewModel = viewModel(),
) {
    val context = LocalContext.current
    val workouts by viewModel.workouts.collectAsState()
    val importError by viewModel.importError.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    var renamingWorkout by remember { mutableStateOf<Workout?>(null) }

    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) {
            viewModel.importWorkout(uri, queryDisplayName(uri, context))
        }
    }

    LaunchedEffect(importError) {
        importError?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearImportError()
        }
    }

    renamingWorkout?.let { workout ->
        RenameWorkoutDialog(
            workout = workout,
            onSave = { newName ->
                viewModel.renameWorkout(workout.id, newName)
                renamingWorkout = null
            },
            onDismiss = { renamingWorkout = null },
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Workout Library") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
        floatingActionButton = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                FloatingActionButton(onClick = onCreateWorkout) {
                    Icon(Icons.Filled.Tune, contentDescription = "Create workout")
                }
                FloatingActionButton(onClick = { importLauncher.launch(arrayOf("*/*")) }) {
                    Icon(Icons.Filled.Add, contentDescription = "Import workout file")
                }
            }
        },
        snackbarHost = { SnackbarHost(snackbarHostState) { Snackbar(it) } },
    ) { padding ->
        if (workouts.isEmpty()) {
            Column(
                modifier = Modifier.fillMaxSize().padding(padding).padding(32.dp),
                verticalArrangement = Arrangement.Center,
            ) {
                Icon(Icons.Filled.FitnessCenter, contentDescription = null)
                Text(
                    "No workouts yet. Tap + to import an .erg, .mrc, or .zwo file.",
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
                        onRename = { renamingWorkout = workout },
                        onEditBlocks = { onEditWorkout(workout.id) },
                        onDelete = { viewModel.deleteWorkout(workout.id) },
                    )
                }
            }
        }
    }
}

@Composable
private fun WorkoutCard(
    workout: Workout,
    onRename: () -> Unit,
    onEditBlocks: () -> Unit,
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
                    IconButton(onClick = onEditBlocks) {
                        Icon(Icons.Filled.Tune, contentDescription = "Edit workout blocks")
                    }
                    IconButton(onClick = onRename) {
                        Icon(Icons.Filled.Edit, contentDescription = "Rename workout")
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

@Composable
private fun RenameWorkoutDialog(workout: Workout, onSave: (String) -> Unit, onDismiss: () -> Unit) {
    var name by remember(workout.id) { mutableStateOf(workout.name) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Rename Workout") },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("Workout name") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            TextButton(onClick = { onSave(name.ifBlank { workout.name }) }) { Text("Save") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
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

private fun queryDisplayName(uri: Uri, context: android.content.Context): String? {
    context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
        val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
        if (index >= 0 && cursor.moveToFirst()) {
            return cursor.getString(index)
        }
    }
    return null
}
