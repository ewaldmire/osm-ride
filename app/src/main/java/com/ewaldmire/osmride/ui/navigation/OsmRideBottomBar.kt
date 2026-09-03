package com.ewaldmire.osmride.ui.navigation

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FitnessCenter
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.ImageVector

private data class BottomBarTab(val route: String, val label: String, val icon: ImageVector)

private val bottomBarTabs = listOf(
    BottomBarTab(Destinations.HISTORY, "History", Icons.Filled.History),
    BottomBarTab(Destinations.ROUTES_LIST, "Routes", Icons.Filled.Map),
    BottomBarTab(Destinations.WORKOUTS_LIST, "Workouts", Icons.Filled.FitnessCenter),
    BottomBarTab(Destinations.SETTINGS, "Settings", Icons.Filled.Settings),
)

/** Persistent bottom navigation shown on every screen except [Destinations.RIDE] (the map/riding
 * screen needs the whole viewport). None of the 4 tabs shows as selected on sub-screens reached
 * from within a tab (Route Creator, Workout Creator, Pairing, ride Summary) - those keep their
 * own back arrow via their own Scaffold's topBar, nested inside this one. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OsmRideBottomBar(currentRoute: String?, onNavigate: (String) -> Unit) {
    NavigationBar {
        bottomBarTabs.forEach { tab ->
            NavigationBarItem(
                selected = currentRoute == tab.route,
                onClick = { onNavigate(tab.route) },
                icon = { Icon(tab.icon, contentDescription = tab.label) },
                label = { Text(tab.label) },
            )
        }
    }
}
