package com.ewaldmire.osmride.ui.routes

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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AddRoad
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DirectionsBike
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.EditLocationAlt
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.ewaldmire.osmride.ride.RideEngine
import com.ewaldmire.osmride.route.RouteSummary
import com.ewaldmire.osmride.util.Units
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RoutesListScreen(
    onRouteSelected: (String) -> Unit,
    onCreateRoute: () -> Unit,
    onEditRoute: (String) -> Unit,
    onBack: () -> Unit,
    viewModel: RoutesListViewModel = viewModel(),
) {
    val context = LocalContext.current
    val routes by viewModel.routes.collectAsState()
    val importError by viewModel.importError.collectAsState()
    val activeRideEngine by viewModel.activeRideEngine.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    val coroutineScope = rememberCoroutineScope()
    var renamingRoute by remember { mutableStateOf<RouteSummary?>(null) }

    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) {
            viewModel.importGpx(uri, queryDisplayName(uri, context))
        }
    }

    LaunchedEffect(importError) {
        importError?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearImportError()
        }
    }

    renamingRoute?.let { route ->
        RenameRouteDialog(
            route = route,
            onSave = { newName ->
                viewModel.renameRoute(route.id, newName)
                renamingRoute = null
            },
            onDismiss = { renamingRoute = null },
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("New Ride") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
        floatingActionButton = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                FloatingActionButton(onClick = onCreateRoute) {
                    Icon(Icons.Filled.AddRoad, contentDescription = "Create route")
                }
                FloatingActionButton(onClick = { importLauncher.launch(arrayOf("*/*")) }) {
                    Icon(Icons.Filled.Add, contentDescription = "Import GPX route")
                }
            }
        },
        snackbarHost = { SnackbarHost(snackbarHostState) { Snackbar(it) } },
    ) { padding ->
        fun selectRoute(routeId: String) {
            val active = activeRideEngine
            if (active != null && active.route.id != routeId) {
                coroutineScope.launch {
                    snackbarHostState.showSnackbar("Finish your current ride first")
                }
            } else {
                onRouteSelected(routeId)
            }
        }

        if (routes.isEmpty()) {
            EmptyState(padding)
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                activeRideEngine?.let { active ->
                    item(key = "active-ride-banner") {
                        ActiveRideBanner(engine = active, onClick = { selectRoute(active.route.id) })
                    }
                }
                items(routes, key = { it.id }) { route ->
                    RouteCard(
                        route = route,
                        onClick = { selectRoute(route.id) },
                        onRename = { renamingRoute = route },
                        onEditRoute = { onEditRoute(route.id) },
                        onDelete = { viewModel.deleteRoute(route.id) },
                    )
                }
            }
        }
    }
}

@Composable
private fun ActiveRideBanner(engine: RideEngine, onClick: () -> Unit) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("Ride in progress", style = MaterialTheme.typography.titleMedium)
            Text(
                "${engine.route.name} — tap to resume",
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

@Composable
private fun EmptyState(padding: PaddingValues) {
    Column(
        modifier = Modifier.fillMaxSize().padding(padding).padding(32.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(Icons.Filled.DirectionsBike, contentDescription = null)
        Text(
            "No routes yet. Tap + to import a GPX route file.",
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.padding(top = 12.dp),
        )
    }
}

@Composable
private fun RouteCard(
    route: RouteSummary,
    onClick: () -> Unit,
    onRename: () -> Unit,
    onEditRoute: () -> Unit,
    onDelete: () -> Unit,
) {
    Card(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(route.name, style = MaterialTheme.typography.titleMedium)
                Text(
                    "${Units.formatMiles(route.totalDistanceMeters)} · " +
                        "${Units.formatFeet(route.elevationGainMeters)} climb",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            Row {
                if (route.waypoints != null) {
                    IconButton(onClick = onEditRoute) {
                        Icon(Icons.Filled.EditLocationAlt, contentDescription = "Edit route path")
                    }
                }
                IconButton(onClick = onRename) {
                    Icon(Icons.Filled.Edit, contentDescription = "Rename route")
                }
                IconButton(onClick = onDelete) {
                    Icon(Icons.Filled.Delete, contentDescription = "Delete route")
                }
            }
        }
    }
}

@Composable
private fun RenameRouteDialog(route: RouteSummary, onSave: (String) -> Unit, onDismiss: () -> Unit) {
    var name by remember(route.id) { mutableStateOf(route.name) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Rename Route") },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("Route name") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            TextButton(onClick = { onSave(name.ifBlank { route.name }) }) { Text("Save") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

private fun queryDisplayName(uri: Uri, context: android.content.Context): String? {
    context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
        val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
        if (index >= 0 && cursor.moveToFirst()) {
            return cursor.getString(index)?.substringBeforeLast(".")
        }
    }
    return null
}
