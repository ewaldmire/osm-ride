package com.ewaldmire.osmride.ui.navigation

import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.ewaldmire.osmride.OsmRideApp
import com.ewaldmire.osmride.ride.FitFileImporter
import com.ewaldmire.osmride.strength.StrengthWorkoutImport
import com.ewaldmire.osmride.ui.bodymetrics.BodyMetricsScreen
import com.ewaldmire.osmride.ui.pairing.DevicePairingScreen
import com.ewaldmire.osmride.ui.profile.ProfileScreen
import com.ewaldmire.osmride.ui.profile.ProfileTab
import com.ewaldmire.osmride.ui.ride.RideScreen
import com.ewaldmire.osmride.ui.ridehub.RideHubScreen
import com.ewaldmire.osmride.ui.routecreator.RouteCreatorScreen
import com.ewaldmire.osmride.ui.settings.SettingsScreen
import com.ewaldmire.osmride.ui.settings.WorkoutsListScreen
import com.ewaldmire.osmride.ui.summary.RideSummaryScreen
import com.ewaldmire.osmride.ui.workoutcreator.WorkoutCreatorScreen

/** The persistent bottom bar (see [OsmRideBottomBar]) lives on this single outer Scaffold, not
 * on each screen - every screen still keeps its own Scaffold+TopAppBar for its title, nested
 * inside this one's content slot. The two don't fight over padding: this Scaffold only ever
 * contributes bottom padding (the bar), each inner Scaffold only ever contributes top padding
 * (its own top bar), so nesting them is safe. Hidden only on [Destinations.RIDE] - the map needs
 * the full viewport while riding.
 *
 * Only genuine sub-pages (Route Creator, Workout Creator, Pairing, Body Metrics) get a back arrow
 * in their TopAppBar. The four bottom-nav root tabs (Profile, Ride, Workouts, Settings) don't -
 * they're reached only via the bottom bar, so a "back" affordance on them was both redundant with
 * it and, worse, always landed on the same fixed screen regardless of which tab the user actually
 * came from (the bottom bar's popUpTo(RIDE_HUB) reset below means popBackStack() on a root tab
 * always resolves to the same anchor, not whatever tab preceded it).
 *
 * [pendingWorkoutImportUri] is the osmride://import-workout deep link fosslift launches to share a
 * completed strength workout in; [pendingFitShareUri] is a .fit file shared in via the OS
 * Sharesheet (e.g. from a bike computer's companion app) - see MainActivity, which owns
 * intent/onNewIntent handling and hands both down here as Compose state. Each is non-null exactly
 * once per share; consumed via their matching onConsumed callback so rotation/recomposition
 * doesn't reimport it. Both land on Profile's Activities tab on success (see [profileInitialTab]
 * below) - strength workouts and any .fit-imported activity are both just "activities" now. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OsmRideNavHost(
    navController: NavHostController = rememberNavController(),
    pendingWorkoutImportUri: Uri? = null,
    onPendingWorkoutImportConsumed: () -> Unit = {},
    pendingFitShareUri: Uri? = null,
    pendingFitShareDisplayName: String? = null,
    onPendingFitShareConsumed: () -> Unit = {},
) {
    val currentRoute = navController.currentBackStackEntryAsState().value?.destination?.route
    val context = LocalContext.current

    // One-shot hint for ProfileScreen: which sub-tab to land on next time it's (re)shown. Only
    // the two LaunchedEffects below ever change this away from its default - see ProfileScreen's
    // own doc comment for why a hoisted flag, not a nav argument, is what actually works here
    // (the ProfileScreen backstack entry is never destroyed/recreated across a share, so a nav
    // argument's default would only apply on a truly fresh entry, not this one).
    var profileInitialTab by remember { mutableStateOf(ProfileTab.Activities) }

    LaunchedEffect(pendingWorkoutImportUri) {
        val uri = pendingWorkoutImportUri ?: return@LaunchedEffect
        val imported = StrengthWorkoutImport.parse(uri)
        if (imported != null) {
            val app = context.applicationContext as OsmRideApp
            app.strengthWorkoutRepository.addEntry(
                recordedAtEpochMillis = imported.recordedAtEpochMillis,
                workoutName = imported.workoutName,
                exercises = imported.exercises,
            )
            profileInitialTab = ProfileTab.Activities
            navController.navigate(Destinations.PROFILE) {
                popUpTo(Destinations.RIDE_HUB) { saveState = true }
                launchSingleTop = true
                restoreState = true
            }
        }
        onPendingWorkoutImportConsumed()
    }

    LaunchedEffect(pendingFitShareUri) {
        val uri = pendingFitShareUri ?: return@LaunchedEffect
        val app = context.applicationContext as OsmRideApp
        val error = FitFileImporter.importFitFile(app, uri, pendingFitShareDisplayName)
        if (error == null) {
            profileInitialTab = ProfileTab.Activities
            navController.navigate(Destinations.PROFILE) {
                popUpTo(Destinations.RIDE_HUB) { saveState = true }
                launchSingleTop = true
                restoreState = true
            }
        } else {
            // Launched fresh from the OS Sharesheet - no screen/Snackbar already on-screen to
            // report into (unlike the in-app "Import" button's importError, see
            // ActivitiesViewModel), so a Toast is the only reasonable way to surface this.
            Toast.makeText(context, error, Toast.LENGTH_LONG).show()
        }
        onPendingFitShareConsumed()
    }

    Scaffold(
        bottomBar = {
            if (currentRoute != Destinations.RIDE) {
                OsmRideBottomBar(
                    currentRoute = currentRoute,
                    onNavigate = { route ->
                        navController.navigate(route) {
                            popUpTo(Destinations.RIDE_HUB) { saveState = true }
                            launchSingleTop = true
                            restoreState = true
                        }
                    },
                )
            }
        },
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = Destinations.RIDE_HUB,
            modifier = Modifier.padding(innerPadding),
        ) {
            composable(Destinations.RIDE_HUB) {
                RideHubScreen(
                    onRouteSelected = { routeId -> navController.navigate(Destinations.ride(routeId)) },
                    onCreateRoute = { navController.navigate(Destinations.ROUTE_CREATOR_NEW) },
                    onEditRoute = { routeId, showDerivedHint ->
                        navController.navigate(Destinations.routeCreatorEdit(routeId, showDerivedHint))
                    },
                )
            }
            composable(
                Destinations.ROUTE_CREATOR,
                arguments = listOf(
                    navArgument("routeId") {
                        type = NavType.StringType
                        nullable = true
                        defaultValue = null
                    },
                    navArgument("showDerivedHint") {
                        type = NavType.BoolType
                        defaultValue = false
                    },
                ),
            ) { backStackEntry ->
                RouteCreatorScreen(
                    routeId = backStackEntry.arguments?.getString("routeId"),
                    showDerivedHint = backStackEntry.arguments?.getBoolean("showDerivedHint") ?: false,
                    onBack = { navController.popBackStack() },
                    onSaved = { navController.popBackStack() },
                )
            }
            composable(Destinations.PROFILE) {
                ProfileScreen(
                    initialTab = profileInitialTab,
                    onOpenBodyMetrics = { navController.navigate(Destinations.BODY_METRICS) },
                )
            }
            composable(Destinations.SETTINGS) {
                SettingsScreen(
                    onOpenPairing = { navController.navigate(Destinations.PAIRING) },
                )
            }
            composable(Destinations.BODY_METRICS) {
                BodyMetricsScreen(onBack = { navController.popBackStack() })
            }
            composable(Destinations.WORKOUTS_LIST) {
                WorkoutsListScreen(
                    onCreateWorkout = { navController.navigate(Destinations.WORKOUT_CREATOR_NEW) },
                    onEditWorkout = { workoutId -> navController.navigate(Destinations.workoutCreatorEdit(workoutId)) },
                )
            }
            composable(
                Destinations.WORKOUT_CREATOR,
                arguments = listOf(
                    navArgument("workoutId") {
                        type = NavType.StringType
                        nullable = true
                        defaultValue = null
                    },
                ),
            ) { backStackEntry ->
                WorkoutCreatorScreen(
                    workoutId = backStackEntry.arguments?.getString("workoutId"),
                    onBack = { navController.popBackStack() },
                    onSaved = { navController.popBackStack() },
                )
            }
            composable(Destinations.PAIRING) {
                DevicePairingScreen(onDone = { navController.popBackStack() })
            }
            composable(
                Destinations.RIDE,
                arguments = listOf(navArgument("routeId") { type = NavType.StringType }),
            ) { backStackEntry ->
                val routeId = backStackEntry.arguments?.getString("routeId")
                if (routeId != null) {
                    RideScreen(
                        routeId = routeId,
                        onFinished = {
                            navController.navigate(Destinations.SUMMARY) {
                                popUpTo(Destinations.RIDE_HUB)
                            }
                        },
                        onOpenPairing = { navController.navigate(Destinations.PAIRING) },
                        onCancel = { navController.popBackStack() },
                    )
                }
            }
            composable(Destinations.SUMMARY) {
                RideSummaryScreen(
                    onDone = {
                        navController.popBackStack(Destinations.RIDE_HUB, inclusive = false)
                    },
                )
            }
        }
    }
}
