package com.ewaldmire.osmride.ui.navigation

import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.ewaldmire.osmride.ui.history.RideHistoryScreen
import com.ewaldmire.osmride.ui.pairing.DevicePairingScreen
import com.ewaldmire.osmride.ui.ride.RideScreen
import com.ewaldmire.osmride.ui.routecreator.RouteCreatorScreen
import com.ewaldmire.osmride.ui.routes.RoutesListScreen
import com.ewaldmire.osmride.ui.settings.SettingsScreen
import com.ewaldmire.osmride.ui.settings.WorkoutsListScreen
import com.ewaldmire.osmride.ui.summary.RideSummaryScreen
import com.ewaldmire.osmride.ui.workoutcreator.WorkoutCreatorScreen

@Composable
fun OsmRideNavHost(navController: NavHostController = rememberNavController()) {
    NavHost(navController = navController, startDestination = Destinations.HISTORY) {
        composable(Destinations.HISTORY) {
            RideHistoryScreen(
                onOpenSettings = { navController.navigate(Destinations.SETTINGS) },
                onOpenWorkouts = { navController.navigate(Destinations.WORKOUTS_LIST) },
                onNewRide = { navController.navigate(Destinations.ROUTES_LIST) },
                onOpenRoutes = { navController.navigate(Destinations.ROUTES_LIST) },
            )
        }
        composable(Destinations.ROUTES_LIST) {
            RoutesListScreen(
                onRouteSelected = { routeId -> navController.navigate(Destinations.ride(routeId)) },
                onCreateRoute = { navController.navigate(Destinations.ROUTE_CREATOR_NEW) },
                onEditRoute = { routeId -> navController.navigate(Destinations.routeCreatorEdit(routeId)) },
                onBack = { navController.popBackStack() },
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
            ),
        ) { backStackEntry ->
            RouteCreatorScreen(
                routeId = backStackEntry.arguments?.getString("routeId"),
                onBack = { navController.popBackStack() },
                onSaved = { navController.popBackStack() },
            )
        }
        composable(Destinations.SETTINGS) {
            SettingsScreen(
                onBack = { navController.popBackStack() },
                onOpenPairing = { navController.navigate(Destinations.PAIRING) },
                onOpenWorkouts = { navController.navigate(Destinations.WORKOUTS_LIST) },
            )
        }
        composable(Destinations.WORKOUTS_LIST) {
            WorkoutsListScreen(
                onBack = { navController.popBackStack() },
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
                            popUpTo(Destinations.HISTORY)
                        }
                    },
                )
            }
        }
        composable(Destinations.SUMMARY) {
            RideSummaryScreen(
                onDone = {
                    navController.popBackStack(Destinations.HISTORY, inclusive = false)
                },
            )
        }
    }
}
