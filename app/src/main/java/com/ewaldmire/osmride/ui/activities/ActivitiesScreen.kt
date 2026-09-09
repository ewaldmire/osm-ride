package com.ewaldmire.osmride.ui.activities

import android.content.Intent
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.DirectionsRun
import androidx.compose.material.icons.automirrored.filled.DirectionsWalk
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DirectionsBike
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FitnessCenter
import androidx.compose.material.icons.filled.Hiking
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Kayaking
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.SportsScore
import androidx.compose.material.icons.filled.Waves
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.ewaldmire.osmride.ride.ActivityType
import com.ewaldmire.osmride.ride.RideRecord
import com.ewaldmire.osmride.strength.StrengthExercise
import com.ewaldmire.osmride.strength.StrengthWorkoutEntry
import com.ewaldmire.osmride.util.Units
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val dateFormatter = DateTimeFormatter.ofPattern("MMM d, yyyy · h:mm a")

/**
 * Body content only, no Scaffold/TopAppBar of its own - embedded inside ProfileScreen alongside
 * the Metrics tab under one shared top bar (with an Activities/Metrics tab row). See
 * ProfileScreen.kt. Merges RideRecord (cycling, or any other .fit-imported outdoor activity - see
 * ActivityType) and StrengthWorkoutEntry (shared in from fosslift) into one sorted feed; each
 * renders with type-appropriate content since they're structurally very different.
 */
@Composable
fun ActivitiesContent(padding: PaddingValues, viewModel: ActivitiesViewModel) {
    val context = LocalContext.current
    val rides by viewModel.rides.collectAsState()
    val strengthEntries by viewModel.strengthEntries.collectAsState()
    val items = remember(rides, strengthEntries) { mergeActivities(rides, strengthEntries) }
    var editingRecord by remember { mutableStateOf<RideRecord?>(null) }

    editingRecord?.let { record ->
        EditRideDialog(
            record = record,
            onSave = { title, notes ->
                viewModel.updateRide(record.id, title, notes)
                editingRecord = null
            },
            onDismiss = { editingRecord = null },
        )
    }

    if (items.isEmpty()) {
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(32.dp),
            verticalArrangement = Arrangement.Center,
        ) {
            Icon(Icons.Filled.History, contentDescription = null)
            Text(
                "No activities yet. Ride, or use the button above to import a .fit file.",
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
            // Ride-only totals (distance/speed don't mean anything for a strength session) -
            // still worth showing since rides are the overwhelming majority of most people's
            // activity history.
            if (rides.isNotEmpty()) {
                item(key = "overview") { OverviewCard(rides) }
            }
            items(items, key = { keyOf(it) }) { item ->
                when (item) {
                    is ActivityListItem.Ride -> RideActivityCard(
                        record = item.record,
                        thumbnailFile = viewModel.thumbnailFile(item.record),
                        onEdit = { editingRecord = item.record },
                        onShare = {
                            val file = viewModel.gpxFile(item.record)
                            val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
                            val intent = Intent(Intent.ACTION_SEND).apply {
                                type = "application/gpx+xml"
                                putExtra(Intent.EXTRA_STREAM, uri)
                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            }
                            context.startActivity(Intent.createChooser(intent, "Export activity"))
                        },
                        onDelete = { viewModel.deleteRide(item.record.id) },
                    )
                    is ActivityListItem.Strength -> StrengthActivityCard(
                        entry = item.record,
                        onDelete = { viewModel.deleteStrengthEntry(item.record.id) },
                    )
                }
            }
        }
    }
}

private fun keyOf(item: ActivityListItem): String = when (item) {
    is ActivityListItem.Ride -> "ride_${item.record.id}"
    is ActivityListItem.Strength -> "strength_${item.record.id}"
}

/** Icon per [ActivityType] - not an exhaustive per-sport icon set, just enough that an activity
 * doesn't visually read as "a bike ride" when it wasn't one. */
fun activityIcon(type: ActivityType): ImageVector = when (type) {
    ActivityType.CYCLING -> Icons.Filled.DirectionsBike
    ActivityType.RUNNING -> Icons.AutoMirrored.Filled.DirectionsRun
    ActivityType.WALKING -> Icons.AutoMirrored.Filled.DirectionsWalk
    ActivityType.HIKING -> Icons.Filled.Hiking
    ActivityType.SWIMMING -> Icons.Filled.Waves
    ActivityType.KAYAKING -> Icons.Filled.Kayaking
    ActivityType.ROWING -> Icons.Filled.SportsScore
    ActivityType.OTHER -> Icons.Filled.SportsScore
}

private fun activityLabel(type: ActivityType): String = when (type) {
    ActivityType.CYCLING -> "Cycling"
    ActivityType.RUNNING -> "Running"
    ActivityType.WALKING -> "Walking"
    ActivityType.HIKING -> "Hiking"
    ActivityType.SWIMMING -> "Swimming"
    ActivityType.KAYAKING -> "Kayaking"
    ActivityType.ROWING -> "Rowing"
    ActivityType.OTHER -> "Activity"
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
private fun RideActivityCard(
    record: RideRecord,
    thumbnailFile: File?,
    onEdit: () -> Unit,
    onShare: () -> Unit,
    onDelete: () -> Unit,
) {
    val bitmap = remember(thumbnailFile) {
        thumbnailFile?.let { file -> BitmapFactory.decodeFile(file.path)?.asImageBitmap() }
    }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row {
                Box(modifier = Modifier.size(width = 160.dp, height = 96.dp)) {
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
                                activityIcon(record.activityType),
                                contentDescription = null,
                                modifier = Modifier.align(Alignment.Center).size(32.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                Column(modifier = Modifier.weight(1f).padding(start = 12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            activityIcon(record.activityType),
                            contentDescription = activityLabel(record.activityType),
                            modifier = Modifier.size(16.dp).padding(end = 4.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(record.title, style = MaterialTheme.typography.titleMedium)
                    }
                    if (record.title != record.routeName) {
                        Text("Route: ${record.routeName}", style = MaterialTheme.typography.bodySmall)
                    }
                    Text(formatDate(record.completedAtEpochMillis), style = MaterialTheme.typography.bodySmall)
                }
            }
            if (record.notes.isNotBlank()) {
                Text(
                    record.notes,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(Units.formatMiles(record.distanceMeters), style = MaterialTheme.typography.bodyMedium)
                Text(Units.formatDuration(record.durationSeconds), style = MaterialTheme.typography.bodyMedium)
                Text(Units.formatMph(record.avgSpeedMps), style = MaterialTheme.typography.bodyMedium)
                Text(Units.formatKilocalories(record.estimatedKilocalories), style = MaterialTheme.typography.bodyMedium)
            }
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                IconButton(onClick = onEdit) {
                    Icon(Icons.Filled.Edit, contentDescription = "Edit name/notes")
                }
                IconButton(onClick = onShare) {
                    Icon(Icons.Filled.Share, contentDescription = "Share GPX")
                }
                IconButton(onClick = onDelete) {
                    Icon(Icons.Filled.Delete, contentDescription = "Delete activity")
                }
            }
        }
    }
}

@Composable
private fun StrengthActivityCard(entry: StrengthWorkoutEntry, onDelete: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Filled.FitnessCenter,
                            contentDescription = "Strength",
                            modifier = Modifier.size(16.dp).padding(end = 4.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(entry.workoutName ?: "Workout", style = MaterialTheme.typography.titleMedium)
                    }
                    Text(formatDate(entry.recordedAtEpochMillis), style = MaterialTheme.typography.bodySmall)
                }
                IconButton(onClick = onDelete) {
                    Icon(Icons.Filled.Delete, contentDescription = "Delete workout")
                }
            }
            entry.exercises.forEach { exercise -> StrengthExerciseRow(exercise) }
        }
    }
}

@Composable
private fun StrengthExerciseRow(exercise: StrengthExercise) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(
            exercise.name,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f).padding(end = 8.dp),
        )
        Text(
            "${exercise.topSetReps} × ${formatStrengthWeight(exercise.topSetWeightLbs)}",
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

private fun formatStrengthWeight(lbs: Double): String =
    if (lbs == lbs.toInt().toDouble()) "${lbs.toInt()} lb" else "$lbs lb"

private fun formatDate(epochMillis: Long): String =
    Instant.ofEpochMilli(epochMillis).atZone(ZoneId.systemDefault()).format(dateFormatter)

@Composable
private fun EditRideDialog(record: RideRecord, onSave: (title: String, notes: String) -> Unit, onDismiss: () -> Unit) {
    var title by remember(record.id) { mutableStateOf(record.title) }
    var notes by remember(record.id) { mutableStateOf(record.notes) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Edit Activity") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text("Name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = notes,
                    onValueChange = { notes = it },
                    label = { Text("Notes") },
                    minLines = 3,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(title.ifBlank { record.routeName }, notes) }) {
                Text("Save")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}
