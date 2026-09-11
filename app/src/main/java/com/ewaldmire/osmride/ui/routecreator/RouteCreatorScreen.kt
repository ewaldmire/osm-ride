package com.ewaldmire.osmride.ui.routecreator

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.HelpOutline
import androidx.compose.material.icons.filled.Undo
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.ewaldmire.osmride.route.RouteWaypoint
import com.ewaldmire.osmride.util.Units

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RouteCreatorScreen(
    routeId: String?,
    onBack: () -> Unit,
    onSaved: (String) -> Unit,
    showDerivedHint: Boolean = false,
    viewModel: RouteCreatorViewModel = viewModel(),
) {
    val name by viewModel.name.collectAsState()
    val waypoints by viewModel.waypoints.collectAsState()
    val previewGpx by viewModel.previewGpx.collectAsState()
    val isRouting by viewModel.isRouting.collectAsState()
    val error by viewModel.error.collectAsState()
    val saved by viewModel.saved.collectAsState()
    val hint by viewModel.hint.collectAsState()
    val selectedWaypointIndex by viewModel.selectedWaypointIndex.collectAsState()
    val selectedSegmentIndex by viewModel.selectedSegmentIndex.collectAsState()
    val waypointAnchorIndices by viewModel.waypointAnchorIndices.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    var showHelp by remember { mutableStateOf(false) }

    LaunchedEffect(routeId) {
        if (routeId != null) viewModel.loadForEdit(routeId, showDerivedHint)
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

    LaunchedEffect(hint) {
        hint?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearHint()
        }
    }

    LaunchedEffect(selectedWaypointIndex) {
        if (selectedWaypointIndex != null) {
            val result = snackbarHostState.showSnackbar(
                message = "Point selected",
                actionLabel = "Delete",
            )
            if (result == SnackbarResult.ActionPerformed) {
                viewModel.removeSelectedWaypoint()
            } else {
                viewModel.deselectWaypoint()
            }
        }
    }

    val highlightedSegmentPoints = remember(previewGpx, waypointAnchorIndices, selectedSegmentIndex) {
        val segment = selectedSegmentIndex
        val points = previewGpx?.points
        if (segment == null || points == null || segment + 1 >= waypointAnchorIndices.size) {
            null
        } else {
            val start = waypointAnchorIndices[segment].coerceIn(0, points.size - 1)
            val end = waypointAnchorIndices[segment + 1].coerceIn(0, points.size - 1)
            points.subList(start, end + 1).map { RouteWaypoint(it.lat, it.lon) }
        }
    }

    if (showHelp) {
        AlertDialog(
            onDismissRequest = { showHelp = false },
            title = { Text("Editing gestures") },
            text = {
                Text(
                    "• Tap empty map — add a point at the end of the route\n" +
                        "• Tap an existing point — select it, then tap \"Delete\" to remove it\n" +
                        "• Long-press anywhere — highlight the nearest section of the route to edit\n" +
                        "• Tap after a long-press — add a point there and reroute that section\n" +
                        "• Undo / Clear — remove the last point / clear all points",
                )
            },
            confirmButton = {
                TextButton(onClick = { showHelp = false }) { Text("Got it") }
            },
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (routeId != null) "Edit Route" else "Create Route") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { showHelp = true }) {
                        Icon(Icons.Filled.HelpOutline, contentDescription = "Editing gestures help")
                    }
                    IconButton(onClick = viewModel::undoLastWaypoint, enabled = waypoints.isNotEmpty()) {
                        Icon(Icons.Filled.Undo, contentDescription = "Remove last waypoint")
                    }
                    IconButton(onClick = viewModel::clearWaypoints, enabled = waypoints.isNotEmpty()) {
                        Icon(Icons.Filled.Clear, contentDescription = "Clear waypoints")
                    }
                    IconButton(onClick = viewModel::save, enabled = previewGpx != null && !isRouting) {
                        Icon(Icons.Filled.Check, contentDescription = "Save route")
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) { Snackbar(it) } },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            OutlinedTextField(
                value = name,
                onValueChange = viewModel::updateName,
                label = { Text("Route name") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            )
            Text(
                "Tap the map to add waypoints — roads are routed automatically.",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
            Box(modifier = Modifier.weight(1f).fillMaxWidth().padding(top = 8.dp)) {
                RouteCreatorMapView(
                    waypoints = waypoints,
                    previewPoints = previewGpx?.points?.map { RouteWaypoint(it.lat, it.lon) },
                    highlightedSegmentPoints = highlightedSegmentPoints,
                    selectedWaypointIndex = selectedWaypointIndex,
                    onMapTapped = { lat, lon ->
                        if (selectedSegmentIndex != null) {
                            viewModel.insertWaypointAtSelectedSegment(lat, lon)
                        } else {
                            viewModel.addWaypoint(lat, lon)
                        }
                    },
                    onWaypointTapped = viewModel::selectWaypoint,
                    onSegmentLongPressed = viewModel::selectNearestSegment,
                    modifier = Modifier.fillMaxSize(),
                )
                if (isRouting) {
                    CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                }
            }
            previewGpx?.let { gpx ->
                Text(
                    "${Units.formatMiles(gpx.totalDistanceMeters)} · ${Units.formatFeet(gpx.elevationGainMeters)} climb",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(16.dp),
                )
            }
        }
    }
}
