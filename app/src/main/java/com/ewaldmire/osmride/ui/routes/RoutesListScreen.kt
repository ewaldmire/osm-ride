package com.ewaldmire.osmride.ui.routes

import android.content.Intent
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DirectionsBike
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.ewaldmire.osmride.ride.RideEngine
import com.ewaldmire.osmride.route.RouteSummary
import com.ewaldmire.osmride.util.Units
import java.io.File
import kotlinx.coroutines.launch

/**
 * Body content only, no Scaffold/TopAppBar of its own - embedded inside RideHubScreen alongside
 * RideHistoryContent under one shared top bar (with a Routes/History tab row and, only while
 * this tab is active, the Create/Import actions). See RideHubScreen.kt.
 */
@Composable
fun RoutesListContent(
    padding: PaddingValues,
    viewModel: RoutesListViewModel,
    snackbarHostState: SnackbarHostState,
    onRouteSelected: (String) -> Unit,
    onEditRoute: (routeId: String, showDerivedHint: Boolean) -> Unit,
) {
    val context = LocalContext.current
    val routes by viewModel.routes.collectAsState()
    val activeRideEngine by viewModel.activeRideEngine.collectAsState()
    val coroutineScope = rememberCoroutineScope()

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
                    thumbnailFile = viewModel.thumbnailFile(route),
                    onClick = { selectRoute(route.id) },
                    onEdit = {
                        viewModel.prepareEdit(route.id) { showDerivedHint ->
                            onEditRoute(route.id, showDerivedHint)
                        }
                    },
                    onExport = {
                        val file = viewModel.routeFile(route)
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
                        context.startActivity(Intent.createChooser(intent, "Export route"))
                    },
                    onDelete = { viewModel.deleteRoute(route.id) },
                )
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
            "No routes yet. Create a route or import a GPX file, then tap it to start riding.",
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.padding(top = 12.dp),
        )
    }
}

@Composable
private fun RouteCard(
    route: RouteSummary,
    thumbnailFile: File?,
    onClick: () -> Unit,
    onEdit: () -> Unit,
    onExport: () -> Unit,
    onDelete: () -> Unit,
) {
    // Cheap to decode on every recomposition at this size (a small cached PNG, not the full map)
    // and avoids pulling in an image-loading library just for this one static-per-file bitmap.
    val bitmap = remember(thumbnailFile) {
        thumbnailFile?.let { file -> BitmapFactory.decodeFile(file.path)?.asImageBitmap() }
    }

    Card(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Row(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
            // Same 5:3 aspect ratio as the generated PNG (see RouteThumbnailGenerator's
            // THUMBNAIL_WIDTH/THUMBNAIL_HEIGHT) and ContentScale.Fit (not Crop) - shows the
            // whole route, not just whatever the camera's exact-fit bounds happen to leave near
            // the edges cropped off.
            Box(modifier = Modifier.size(width = 160.dp, height = 96.dp)) {
                if (bitmap != null) {
                    Image(
                        bitmap = bitmap,
                        contentDescription = null,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxSize(),
                    )
                } else {
                    // No cached snapshot yet (not generated, still generating, or this route
                    // predates the feature) - a plain icon placeholder rather than a gap.
                    Box(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceVariant)) {
                        Icon(
                            Icons.Filled.Place,
                            contentDescription = null,
                            modifier = Modifier.align(Alignment.Center).size(32.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            // Matches the thumbnail's own height (Row sizes to its tallest child) with the
            // action icons pinned to the bottom via SpaceBetween, instead of a separate full-width
            // row below - avoids the wasted whitespace of a 96dp-tall thumbnail sitting next to a
            // much shorter text block.
            Column(
                modifier = Modifier.weight(1f).fillMaxHeight().padding(start = 12.dp),
                verticalArrangement = Arrangement.SpaceBetween,
            ) {
                Column {
                    Text(
                        route.name,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        "${Units.formatMiles(route.totalDistanceMeters)} · " +
                            "${Units.formatFeet(route.elevationGainMeters)} climb",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    // Every route opens the full route creator to edit - which already has its
                    // own name field, covering renaming too. Plain GPX imports get a sparse
                    // waypoint list derived from their track the first time they're opened this
                    // way (see RoutesListViewModel.prepareEdit).
                    IconButton(onClick = onEdit) {
                        Icon(Icons.Filled.Edit, contentDescription = "Edit route")
                    }
                    IconButton(onClick = onExport) {
                        Icon(Icons.Filled.Share, contentDescription = "Export route")
                    }
                    IconButton(onClick = onDelete) {
                        Icon(Icons.Filled.Delete, contentDescription = "Delete route")
                    }
                }
            }
        }
    }
}
