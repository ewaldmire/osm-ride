package com.ewaldmire.osmride

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.ewaldmire.osmride.ui.navigation.OsmRideNavHost
import com.ewaldmire.osmride.ui.theme.OsmRideTheme

/** [android:launchMode]="singleTop" (see AndroidManifest.xml) means a fosslift share while
 * osm-ride is already running/backgrounded redelivers via onNewIntent rather than a fresh
 * onCreate - both paths funnel into [pendingWorkoutImportUri] as Compose state so OsmRideNavHost
 * sees it either way. */
class MainActivity : ComponentActivity() {
    private var pendingWorkoutImportUri by mutableStateOf<Uri?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        pendingWorkoutImportUri = intent?.data
        setContent {
            OsmRideTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    OsmRideNavHost(
                        pendingWorkoutImportUri = pendingWorkoutImportUri,
                        onPendingWorkoutImportConsumed = { pendingWorkoutImportUri = null },
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        pendingWorkoutImportUri = intent.data
    }
}
