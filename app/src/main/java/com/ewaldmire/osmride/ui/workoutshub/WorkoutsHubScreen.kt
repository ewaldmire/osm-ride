package com.ewaldmire.osmride.ui.workoutshub

import android.content.Context
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
import com.ewaldmire.osmride.ui.settings.WorkoutsListContent
import com.ewaldmire.osmride.ui.settings.WorkoutsListViewModel
import com.ewaldmire.osmride.ui.strength.StrengthWorkoutsContent
import com.ewaldmire.osmride.ui.strength.StrengthWorkoutsViewModel

enum class WorkoutsHubTab { Cycling, Weights }

/**
 * Cycling (ERG-mode trainer workouts, created/imported in-app) and Weights (strength workouts
 * shared in from fosslift) share one bottom-nav tab behind a switcher - same pattern as the Ride
 * hub's Routes/History split (see RideHubScreen.kt). Weights is read-only from osm-ride's side:
 * fosslift owns strength-training data, this just displays what it shares in.
 *
 * [initialTab] is a one-shot hint, not a persistent nav argument - mirrors RideHubScreen's own
 * doc comment. It's how a fosslift import (OsmRideNavHost) lands here on the Weights sub-tab
 * specifically, without disturbing whatever sub-tab was last chosen on every other visit.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WorkoutsHubScreen(
    initialTab: WorkoutsHubTab,
    onCreateWorkout: () -> Unit,
    onEditWorkout: (String) -> Unit,
    workoutsViewModel: WorkoutsListViewModel = viewModel(),
    strengthViewModel: StrengthWorkoutsViewModel = viewModel(),
) {
    val context = LocalContext.current
    var selectedTab by rememberSaveable { mutableStateOf(initialTab) }
    LaunchedEffect(initialTab) { selectedTab = initialTab }

    val importError by workoutsViewModel.importError.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }

    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) {
            workoutsViewModel.importWorkout(uri, queryDisplayName(uri, context))
        }
    }

    LaunchedEffect(importError) {
        importError?.let {
            snackbarHostState.showSnackbar(it)
            workoutsViewModel.clearImportError()
        }
    }

    Scaffold(
        topBar = {
            Column {
                TopAppBar(
                    title = {
                        Column {
                            Text("Workout")
                            Text(
                                if (selectedTab == WorkoutsHubTab.Cycling) {
                                    "Import .erg, .mrc, or .zwo files for ERG mode"
                                } else {
                                    "Strength workouts shared in from your other app"
                                },
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    },
                    actions = {
                        if (selectedTab == WorkoutsHubTab.Cycling) {
                            // Text actions, not icon-only - a bare icon pair reads as "hard to
                            // tell what they do" (confirmed by the user) without a label.
                            TextButton(onClick = onCreateWorkout) { Text("Create") }
                            TextButton(onClick = { importLauncher.launch(arrayOf("*/*")) }) { Text("Import") }
                        }
                    },
                )
                TabRow(selectedTabIndex = selectedTab.ordinal) {
                    Tab(
                        selected = selectedTab == WorkoutsHubTab.Cycling,
                        onClick = { selectedTab = WorkoutsHubTab.Cycling },
                        text = { Text("Cycling") },
                    )
                    Tab(
                        selected = selectedTab == WorkoutsHubTab.Weights,
                        onClick = { selectedTab = WorkoutsHubTab.Weights },
                        text = { Text("Weights") },
                    )
                }
            }
        },
        snackbarHost = { SnackbarHost(snackbarHostState) { Snackbar(it) } },
    ) { padding ->
        when (selectedTab) {
            WorkoutsHubTab.Cycling -> WorkoutsListContent(
                padding = padding,
                viewModel = workoutsViewModel,
                onEditWorkout = onEditWorkout,
            )
            WorkoutsHubTab.Weights -> StrengthWorkoutsContent(padding = padding, viewModel = strengthViewModel)
        }
    }
}

private fun queryDisplayName(uri: Uri, context: Context): String? {
    context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
        val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
        if (index >= 0 && cursor.moveToFirst()) {
            return cursor.getString(index)
        }
    }
    return null
}
