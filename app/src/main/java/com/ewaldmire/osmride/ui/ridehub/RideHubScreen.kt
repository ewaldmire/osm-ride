package com.ewaldmire.osmride.ui.ridehub

import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import com.ewaldmire.osmride.ui.history.RideHistoryContent
import com.ewaldmire.osmride.ui.history.RideHistoryViewModel
import com.ewaldmire.osmride.ui.routes.RoutesListContent
import com.ewaldmire.osmride.ui.routes.RoutesListViewModel

enum class RideHubTab { Routes, History }

/**
 * Riding and reviewing past rides are both "the main thing you do here", so Routes and History
 * share one bottom-nav tab (with a switcher, not two nav destinations) rather than living as
 * separate top-level screens. Create/Import only show up while the Routes sub-tab is active.
 *
 * [initialTab] is a one-shot hint, not a persistent nav argument - see its LaunchedEffect below.
 * It's how the ride-finish flow (OsmRideNavHost) lands back here on the History sub-tab
 * specifically, without disturbing whatever sub-tab the rider chooses on every other visit
 * (rememberSaveable already handles that on its own).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RideHubScreen(
    initialTab: RideHubTab,
    onRouteSelected: (String) -> Unit,
    onCreateRoute: () -> Unit,
    onEditRoute: (routeId: String, showDerivedHint: Boolean) -> Unit,
    routesViewModel: RoutesListViewModel = viewModel(),
    historyViewModel: RideHistoryViewModel = viewModel(),
) {
    val context = LocalContext.current
    var selectedTab by rememberSaveable { mutableStateOf(initialTab) }
    LaunchedEffect(initialTab) { selectedTab = initialTab }

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
            Column {
                TopAppBar(
                    title = { Text("Ride") },
                    actions = {
                        if (selectedTab == RideHubTab.Routes) {
                            // Text actions, not icon-only - a bare icon pair reads as "hard to
                            // tell what they do" (confirmed by the user) without a label.
                            TextButton(onClick = onCreateRoute) { Text("Create") }
                            TextButton(onClick = { importLauncher.launch(arrayOf("*/*")) }) { Text("Import") }
                        }
                    },
                )
                TabRow(selectedTabIndex = selectedTab.ordinal) {
                    Tab(
                        selected = selectedTab == RideHubTab.Routes,
                        onClick = { selectedTab = RideHubTab.Routes },
                        text = { Text("Routes") },
                    )
                    Tab(
                        selected = selectedTab == RideHubTab.History,
                        onClick = { selectedTab = RideHubTab.History },
                        text = { Text("History") },
                    )
                }
            }
        },
        snackbarHost = { SnackbarHost(snackbarHostState) { Snackbar(it) } },
    ) { padding ->
        when (selectedTab) {
            RideHubTab.Routes -> RoutesListContent(
                padding = padding,
                viewModel = routesViewModel,
                snackbarHostState = snackbarHostState,
                onRouteSelected = onRouteSelected,
                onEditRoute = onEditRoute,
            )
            RideHubTab.History -> RideHistoryContent(padding = padding, viewModel = historyViewModel)
        }
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
