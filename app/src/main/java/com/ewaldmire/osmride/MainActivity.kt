package com.ewaldmire.osmride

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.widget.Toast
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

    /** Four shapes MainActivity can be launched with, distinguished by action/data:
     *  - plain MAIN/LAUNCHER (no data/extras) - normal app open, nothing to do here.
     *  - VIEW with an osmride://import-workout deep link - fosslift's strength-workout share
     *    (see StrengthWorkoutImport.kt).
     *  - SEND with a file in EXTRA_STREAM - the OS Sharesheet path for a .fit file shared from
     *    another app (e.g. a bike computer's companion app) - see AndroidManifest.xml's
     *    intent-filter and FitFileImporter.kt.
     *  - SEND_MULTIPLE, same as SEND but with EXTRA_STREAM as an ArrayList - some apps (Wahoo's
     *    ELEMNT app among them) always use the multi-file share path even for a single file. Only
     *    the first file is imported; OSM Ride only ever saves one ride from a share. */
    private fun handleIntent(intent: Intent?) {
        if (intent == null) return
        when (intent.action) {
            Intent.ACTION_VIEW -> {
                intent.data?.let { pendingWorkoutImportUri = it }
            }
            Intent.ACTION_SEND -> {
                // TEMPORARY - remove once the sending app's real MIME type is known, so the
                // manifest's currently-wildcarded */* filter can be narrowed to it instead.
                Toast.makeText(this, "OSM Ride received SEND with type: ${intent.type}", Toast.LENGTH_LONG).show()
                val uri = IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java) ?: return
                pendingFitShare = PendingFitShare(uri, queryDisplayName(uri))
            }
            Intent.ACTION_SEND_MULTIPLE -> {
                // TEMPORARY - see above.
                Toast.makeText(this, "OSM Ride received SEND_MULTIPLE with type: ${intent.type}", Toast.LENGTH_LONG).show()
                val uri = IntentCompat.getParcelableArrayListExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
                    ?.firstOrNull() ?: return
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
