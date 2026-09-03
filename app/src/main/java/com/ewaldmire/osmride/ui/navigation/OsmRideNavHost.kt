package com.ewaldmire.osmride.ui.navigation

import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.ewaldmire.osmride.ui.pairing.DevicePairingScreen
import com.ewaldmire.osmride.ui.ride.RideScreen
import com.ewaldmire.osmride.ui.routes.RoutesListScreen
import com.ewaldmire.osmride.ui.summary.RideSummaryScreen

@Composable
fun OsmRideNavHost(navController: NavHostController = rememberNavController()) {
    NavHost(navController = navController, startDestination = Destinations.ROUTES_LIST) {
        composable(Destinations.ROUTES_LIST) {
            RoutesListScreen(
                onRouteSelected = { routeId -> navController.navigate(Destinations.ride(routeId)) },
                onPairDevices = { navController.navigate(Destinations.PAIRING) },
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
                            popUpTo(Destinations.ROUTES_LIST)
                        }
                    },
                )
            }
        }
        composable(Destinations.SUMMARY) {
            RideSummaryScreen(
                onDone = {
                    navController.popBackStack(Destinations.ROUTES_LIST, inclusive = false)
                },
            )
        }
    }
}
