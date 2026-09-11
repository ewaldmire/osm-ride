package com.ewaldmire.osmride.ui.profile

import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.MonitorWeight
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.ewaldmire.osmride.ui.activities.ActivitiesContent
import com.ewaldmire.osmride.ui.activities.ActivitiesViewModel
import com.ewaldmire.osmride.util.Units
import com.ewaldmire.osmride.weight.FtpEntry
import com.ewaldmire.osmride.weight.WaistEntry
import com.ewaldmire.osmride.weight.WeightEntry

enum class ProfileTab { Activities, Metrics }

/**
 * Personal "about you" data: completed activities (any .fit-imported outdoor activity, plus
 * strength workouts shared in from fosslift - see ActivitiesScreen.kt) and body
 * measurements/training info (weight/measurements/body-fat/FTP, all via Body Metrics) - as opposed to
 * Settings, which is just app configuration (Bluetooth pairing). Tabbed the same way as the Ride
 * hub/Workouts screen used to be, since Activities used to live under Ride hub before .fit
 * imports could be non-cycling activities.
 *
 * [initialTab] is a one-shot hint, not a persistent nav argument - mirrors RideHubScreen's own
 * former doc comment on the same pattern. It's how an incoming .fit-Sharesheet import, a fosslift
 * strength-workout share, or finishing a live ride (OsmRideNavHost) lands here on the Activities
 * sub-tab specifically. [initialTabRequestId] is keyed on instead of [initialTab] itself so the
 * effect below reliably re-fires even when two requests in a row ask for the same tab (e.g. two
 * rides finished back to back) - a plain value key wouldn't change on the second request, so
 * nothing would force the switch back if the rider had manually flipped to Metrics in between.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileScreen(
    initialTab: ProfileTab,
    initialTabRequestId: Int = 0,
    onOpenBodyMetrics: () -> Unit,
    profileViewModel: ProfileViewModel = viewModel(),
    activitiesViewModel: ActivitiesViewModel = viewModel(),
) {
    val context = LocalContext.current
    var selectedTab by rememberSaveable { mutableStateOf(initialTab) }
    LaunchedEffect(initialTabRequestId) { selectedTab = initialTab }

    val importError by activitiesViewModel.importError.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }

    val importFitLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) {
            activitiesViewModel.importFitFile(uri, queryDisplayName(uri, context))
        }
    }

    LaunchedEffect(importError) {
        importError?.let {
            snackbarHostState.showSnackbar(it)
            activitiesViewModel.clearImportError()
        }
    }

    Scaffold(
        topBar = {
            Column {
                TopAppBar(
                    title = {
                        Column {
                            Text("Profile")
                            Text(
                                if (selectedTab == ProfileTab.Activities) {
                                    "Completed rides, outdoor activities, and workouts"
                                } else {
                                    "Personal info used to tailor your training"
                                },
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    },
                    actions = {
                        if (selectedTab == ProfileTab.Activities) {
                            TextButton(onClick = { importFitLauncher.launch(arrayOf("*/*")) }) { Text("Import") }
                        }
                    },
                )
                TabRow(selectedTabIndex = selectedTab.ordinal) {
                    Tab(
                        selected = selectedTab == ProfileTab.Activities,
                        onClick = { selectedTab = ProfileTab.Activities },
                        text = { Text("Activities") },
                    )
                    Tab(
                        selected = selectedTab == ProfileTab.Metrics,
                        onClick = { selectedTab = ProfileTab.Metrics },
                        text = { Text("Metrics") },
                    )
                }
            }
        },
        snackbarHost = { SnackbarHost(snackbarHostState) { Snackbar(it) } },
    ) { padding ->
        when (selectedTab) {
            ProfileTab.Activities -> ActivitiesContent(padding = padding, viewModel = activitiesViewModel)
            ProfileTab.Metrics -> MetricsTab(padding = padding, viewModel = profileViewModel, onOpenBodyMetrics = onOpenBodyMetrics)
        }
    }
}

@Composable
private fun MetricsTab(
    padding: PaddingValues,
    viewModel: ProfileViewModel,
    onOpenBodyMetrics: () -> Unit,
) {
    val weightEntries by viewModel.weightEntries.collectAsState()
    val waistEntries by viewModel.waistEntries.collectAsState()
    val ftpEntries by viewModel.ftpEntries.collectAsState()
    val latestWeight = weightEntries.firstOrNull()
    val latestWaist = waistEntries.firstOrNull()
    val latestFtp = ftpEntries.firstOrNull()

    Column(
        modifier = Modifier.padding(padding).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Card(onClick = onOpenBodyMetrics, modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Filled.MonitorWeight, contentDescription = null, modifier = Modifier.padding(end = 16.dp))
                    Column {
                        Text("Body Metrics", style = MaterialTheme.typography.titleMedium)
                        Text(
                            bodyMetricsSummary(latestWeight, latestWaist, latestFtp),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
                Icon(Icons.Filled.ChevronRight, contentDescription = null)
            }
        }
    }
}

private fun bodyMetricsSummary(latestWeight: WeightEntry?, latestWaist: WaistEntry?, latestFtp: FtpEntry?): String {
    if (latestWeight == null && latestWaist == null && latestFtp == null) return "No measurements logged yet"
    val parts = mutableListOf<String>()
    latestWeight?.let { parts.add("Weight ${Units.formatWeightLbs(it.weightKg)}") }
    latestWaist?.let { parts.add("Waist ${Units.formatWaistCm(it.waistCm)}") }
    latestFtp?.let { parts.add("FTP ${it.ftpWatts} W") }
    return parts.joinToString(" · ")
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
