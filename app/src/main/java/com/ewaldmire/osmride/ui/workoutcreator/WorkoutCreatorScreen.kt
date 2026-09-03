package com.ewaldmire.osmride.ui.workoutcreator

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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.ewaldmire.osmride.ui.workout.WorkoutProfileChart
import com.ewaldmire.osmride.util.Units
import java.util.UUID

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WorkoutCreatorScreen(
    workoutId: String?,
    onBack: () -> Unit,
    onSaved: (String) -> Unit,
    viewModel: WorkoutCreatorViewModel = viewModel(),
) {
    val name by viewModel.name.collectAsState()
    val blocks by viewModel.blocks.collectAsState()
    val previewWorkout by viewModel.previewWorkout.collectAsState()
    val error by viewModel.error.collectAsState()
    val saved by viewModel.saved.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    var editingBlockId by remember { mutableStateOf<String?>(null) }
    var showAddDialog by remember { mutableStateOf(false) }

    LaunchedEffect(workoutId) {
        if (workoutId != null) viewModel.loadForEdit(workoutId)
    }

    LaunchedEffect(saved) {
        saved?.let(onSaved)
    }

    LaunchedEffect(error) {
        error?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearError()
        }
    }

    if (showAddDialog) {
        BlockEditorDialog(
            initial = null,
            onSave = { draft -> viewModel.addBlock(draft); showAddDialog = false },
            onDismiss = { showAddDialog = false },
        )
    }
    val blockBeingEdited = blocks.find { it.id == editingBlockId }
    if (blockBeingEdited != null) {
        BlockEditorDialog(
            initial = blockBeingEdited,
            onSave = { draft -> viewModel.updateBlock(draft); editingBlockId = null },
            onDismiss = { editingBlockId = null },
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (workoutId != null) "Edit Workout" else "Create Workout") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = viewModel::save, enabled = blocks.isNotEmpty()) {
                        Icon(Icons.Filled.Check, contentDescription = "Save workout")
                    }
                },
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { showAddDialog = true }) {
                Icon(Icons.Filled.Add, contentDescription = "Add block")
            }
        },
        snackbarHost = { SnackbarHost(snackbarHostState) { Snackbar(it) } },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            OutlinedTextField(
                value = name,
                onValueChange = viewModel::updateName,
                label = { Text("Workout name") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            )

            if (blocks.isNotEmpty()) {
                WorkoutProfileChart(
                    workout = previewWorkout,
                    modifier = Modifier.fillMaxWidth().height(72.dp).padding(horizontal = 16.dp),
                )
                Text(
                    Units.formatDuration(previewWorkout.totalDurationSeconds),
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
            }

            if (blocks.isEmpty()) {
                Column(
                    modifier = Modifier.fillMaxSize().padding(32.dp),
                    verticalArrangement = Arrangement.Center,
                ) {
                    Text(
                        "No blocks yet. Tap + to add a steady, ramp, or free-ride interval.",
                        style = MaterialTheme.typography.bodyLarge,
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(blocks, key = { it.id }) { block ->
                        val index = blocks.indexOf(block)
                        BlockRow(
                            index = index + 1,
                            block = block,
                            canMoveUp = index > 0,
                            canMoveDown = index < blocks.lastIndex,
                            onMoveUp = { viewModel.moveBlock(block.id, -1) },
                            onMoveDown = { viewModel.moveBlock(block.id, 1) },
                            onEdit = { editingBlockId = block.id },
                            onDelete = { viewModel.removeBlock(block.id) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun BlockRow(
    index: Int,
    block: WorkoutBlockDraft,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("$index. ${blockSummary(block)}", style = MaterialTheme.typography.titleMedium)
                Text(Units.formatDuration(block.durationSeconds), style = MaterialTheme.typography.bodySmall)
            }
            Row {
                IconButton(onClick = onMoveUp, enabled = canMoveUp) {
                    Icon(Icons.Filled.KeyboardArrowUp, contentDescription = "Move up")
                }
                IconButton(onClick = onMoveDown, enabled = canMoveDown) {
                    Icon(Icons.Filled.KeyboardArrowDown, contentDescription = "Move down")
                }
                IconButton(onClick = onEdit) {
                    Icon(Icons.Filled.Edit, contentDescription = "Edit block")
                }
                IconButton(onClick = onDelete) {
                    Icon(Icons.Filled.Delete, contentDescription = "Delete block")
                }
            }
        }
    }
}

private fun blockSummary(block: WorkoutBlockDraft): String = when (block.type) {
    BlockType.STEADY -> "Steady · ${block.watts}W"
    BlockType.RAMP -> "Ramp · ${block.startWatts}→${block.endWatts}W"
    BlockType.FREE_RIDE -> "Free Ride"
}

@Composable
private fun BlockEditorDialog(
    initial: WorkoutBlockDraft?,
    onSave: (WorkoutBlockDraft) -> Unit,
    onDismiss: () -> Unit,
) {
    var type by remember { mutableStateOf(initial?.type ?: BlockType.STEADY) }
    var minutesText by remember { mutableStateOf(((initial?.durationSeconds ?: 300) / 60).toString()) }
    var secondsText by remember { mutableStateOf(((initial?.durationSeconds ?: 300) % 60).toString()) }
    var wattsText by remember { mutableStateOf((initial?.watts ?: 150).toString()) }
    var startWattsText by remember { mutableStateOf((initial?.startWatts ?: 150).toString()) }
    var endWattsText by remember { mutableStateOf((initial?.endWatts ?: 250).toString()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial != null) "Edit Block" else "Add Block") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = type == BlockType.STEADY,
                        onClick = { type = BlockType.STEADY },
                        label = { Text("Steady") },
                    )
                    FilterChip(
                        selected = type == BlockType.RAMP,
                        onClick = { type = BlockType.RAMP },
                        label = { Text("Ramp") },
                    )
                    FilterChip(
                        selected = type == BlockType.FREE_RIDE,
                        onClick = { type = BlockType.FREE_RIDE },
                        label = { Text("Free Ride") },
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = minutesText,
                        onValueChange = { minutesText = it.filter { c -> c.isDigit() } },
                        label = { Text("Minutes") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    OutlinedTextField(
                        value = secondsText,
                        onValueChange = { secondsText = it.filter { c -> c.isDigit() } },
                        label = { Text("Seconds") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                }
                when (type) {
                    BlockType.STEADY -> OutlinedTextField(
                        value = wattsText,
                        onValueChange = { wattsText = it.filter { c -> c.isDigit() } },
                        label = { Text("Power (W)") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    BlockType.RAMP -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = startWattsText,
                            onValueChange = { startWattsText = it.filter { c -> c.isDigit() } },
                            label = { Text("Start (W)") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            singleLine = true,
                            modifier = Modifier.weight(1f),
                        )
                        OutlinedTextField(
                            value = endWattsText,
                            onValueChange = { endWattsText = it.filter { c -> c.isDigit() } },
                            label = { Text("End (W)") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            singleLine = true,
                            modifier = Modifier.weight(1f),
                        )
                    }
                    BlockType.FREE_RIDE -> Text(
                        "No target power is sent to the trainer during this block.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val totalSeconds = ((minutesText.toLongOrNull() ?: 0) * 60 + (secondsText.toLongOrNull() ?: 0))
                    .coerceAtLeast(1)
                onSave(
                    WorkoutBlockDraft(
                        id = initial?.id ?: UUID.randomUUID().toString(),
                        durationSeconds = totalSeconds,
                        type = type,
                        watts = wattsText.toIntOrNull() ?: 150,
                        startWatts = startWattsText.toIntOrNull() ?: 150,
                        endWatts = endWattsText.toIntOrNull() ?: 250,
                    ),
                )
            }) { Text("Save") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}
