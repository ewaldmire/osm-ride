package com.ewaldmire.osmride

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
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
import androidx.core.content.IntentCompat
import com.ewaldmire.osmride.ui.navigation.OsmRideNavHost
import com.ewaldmire.osmride.ui.theme.OsmRideTheme

/** A [pendingFitShareUri]/name pair, kept together so they're always updated atomically - see
 * [MainActivity.handleIntent]. */
private data class PendingFitShare(val uri: Uri, val displayName: String?)

/** [android:launchMode]="singleTop" (see AndroidManifest.xml) means a share/deep-link while
 * osm-ride is already running/backgrounded redelivers via onNewIntent rather than a fresh
 * onCreate - both paths funnel into Compose state here so OsmRideNavHost sees it either way. */
class MainActivity : ComponentActivity() {
    private var pendingWorkoutImportUri by mutableStateOf<Uri?>(null)
    private var pendingFitShare by mutableStateOf<PendingFitShare?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        handleIntent(intent)
        setContent {
            OsmRideTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    OsmRideNavHost(
                        pendingWorkoutImportUri = pendingWorkoutImportUri,
                        onPendingWorkoutImportConsumed = { pendingWorkoutImportUri = null },
                        pendingFitShareUri = pendingFitShare?.uri,
                        pendingFitShareDisplayName = pendingFitShare?.displayName,
                        onPendingFitShareConsumed = { pendingFitShare = null },
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    /** Three shapes MainActivity can be launched with, distinguished by action/data:
     *  - plain MAIN/LAUNCHER (no data/extras) - normal app open, nothing to do here.
     *  - VIEW with an osmride://import-workout deep link - fosslift's strength-workout share
     *    (see StrengthWorkoutImport.kt).
     *  - SEND with a file in EXTRA_STREAM - the OS Sharesheet path for a .fit file shared from
     *    another app (e.g. a bike computer's companion app) - see AndroidManifest.xml's
     *    ACTION_SEND intent-filter and FitFileImporter.kt. */
    private fun handleIntent(intent: Intent?) {
        if (intent == null) return
        when (intent.action) {
            Intent.ACTION_VIEW -> {
                intent.data?.let { pendingWorkoutImportUri = it }
            }
            Intent.ACTION_SEND -> {
                val uri = IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java) ?: return
                pendingFitShare = PendingFitShare(uri, queryDisplayName(uri))
            }
        }
    }

    private fun queryDisplayName(uri: Uri): String? {
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (index >= 0 && cursor.moveToFirst()) {
                return cursor.getString(index)
            }
        }
        return null
    }
}
