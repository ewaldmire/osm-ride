package com.ewaldmire.osmride.ui.ridehub

import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import com.ewaldmire.osmride.ui.routes.RoutesListContent
import com.ewaldmire.osmride.ui.routes.RoutesListViewModel

/**
 * Route planning: create a route in-app or import a GPX file, then tap one to start riding.
 * Completed-activity history used to share this screen behind a Routes/History tab switcher, but
 * moved to Profile's Activities feed (see ActivitiesScreen.kt) once outdoor .fit imports could be
 * any activity type, not just cycling - "Ride" is now just about riding, not reviewing the past.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RideHubScreen(
    onRouteSelected: (String) -> Unit,
    onCreateRoute: () -> Unit,
    onEditRoute: (routeId: String, showDerivedHint: Boolean) -> Unit,
    routesViewModel: RoutesListViewModel = viewModel(),
) {
    val context = LocalContext.current
    val importError by routesViewModel.importError.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }

    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) {
            routesViewModel.importGpx(uri, queryDisplayName(uri, context))
        }
    }

    LaunchedEffect(importError) {
        importError?.let {
            snackbarHostState.showSnackbar(it)
            routesViewModel.clearImportError()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Ride")
                        Text(
                            "Create a route or import a GPX file, then tap it to start riding",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                },
                actions = {
                    // Text actions, not icon-only - a bare icon pair reads as "hard to tell what
                    // they do" (confirmed by the user) without a label.
                    TextButton(onClick = onCreateRoute) { Text("Create") }
                    TextButton(onClick = { importLauncher.launch(arrayOf("*/*")) }) { Text("Import") }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) { Snackbar(it) } },
    ) { padding ->
        RoutesListContent(
            padding = padding,
            viewModel = routesViewModel,
            snackbarHostState = snackbarHostState,
            onRouteSelected = onRouteSelected,
            onEditRoute = onEditRoute,
        )
    }
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
