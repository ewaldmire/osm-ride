package com.ewaldmire.osmride.ui.ride

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.res.Configuration
import android.view.WindowManager
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Terrain
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.ewaldmire.osmride.ble.BleConnectionState
import com.ewaldmire.osmride.ble.GradeControlState
import com.ewaldmire.osmride.ride.RideState
import com.ewaldmire.osmride.ride.Workout
import com.ewaldmire.osmride.ui.workout.WorkoutProfileChart
import com.ewaldmire.osmride.util.Units

@Composable
fun RideScreen(
    routeId: String,
    onFinished: () -> Unit,
    viewModel: RideViewModel = viewModel(),
) {
    LaunchedEffect(routeId) { viewModel.loadRoute(routeId) }

    val route by viewModel.route.collectAsState()
    val stats by viewModel.stats.collectAsState()
    val trainerConnected by viewModel.trainerConnectionState.collectAsState()
    val gradeControlState by viewModel.gradeControlState.collectAsState()
    val availableWorkouts by viewModel.availableWorkouts.collectAsState()
    val selectedWorkout by viewModel.selectedWorkout.collectAsState()
    var showWorkoutPicker by remember { mutableStateOf(false) }

    // Zoom/rotation-mode "stick" across rides via SharedPreferences, not just this composition.
    val prefsContext = LocalContext.current
    var zoomLevel by remember { mutableStateOf(MapViewPrefs.getZoom(prefsContext)) }
    var headingUp by remember { mutableStateOf(MapViewPrefs.getHeadingUp(prefsContext)) }
    var tiltDegrees by remember { mutableStateOf(MapViewPrefs.getTiltDegrees(prefsContext)) }

    val isLandscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE

    LaunchedEffect(stats.state) {
        if (stats.state == RideState.FINISHED) onFinished()
    }

    // The map is edge-to-edge and its tile colors are unpredictable, so force light (white)
    // status bar icons for this screen and back them with a scrim below, rather than leaving
    // them to whatever color the map happens to show through.
    val view = LocalView.current
    DisposableEffect(Unit) {
        val window = view.context.findActivity()?.window
        val controller = window?.let { WindowCompat.getInsetsController(it, view) }
        val originalLightStatusBars = controller?.isAppearanceLightStatusBars
        controller?.isAppearanceLightStatusBars = false

        // Rides commonly run 30-90+ minutes; nothing else keeps the screen from sleeping.
        window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        onDispose {
            if (originalLightStatusBars != null) {
                controller?.isAppearanceLightStatusBars = originalLightStatusBars
            }
            window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    val currentRoute = route
    Box(modifier = Modifier.fillMaxSize()) {
        if (currentRoute != null) {
            BikeMapView(
                route = currentRoute,
                position = stats.position,
                followBike = true,
                zoomLevel = zoomLevel,
                headingUp = headingUp,
                tiltDegrees = tiltDegrees,
                modifier = Modifier.fillMaxSize(),
            )
        }

        MapControls(
            headingUp = headingUp,
            tiltDegrees = tiltDegrees,
            onZoomIn = {
                zoomLevel = MapViewPrefs.clampZoom(zoomLevel + 1.0)
                MapViewPrefs.setZoom(prefsContext, zoomLevel)
            },
            onZoomOut = {
                zoomLevel = MapViewPrefs.clampZoom(zoomLevel - 1.0)
                MapViewPrefs.setZoom(prefsContext, zoomLevel)
            },
            onToggleHeadingUp = {
                headingUp = !headingUp
                MapViewPrefs.setHeadingUp(prefsContext, headingUp)
            },
            onTiltChanged = { newTilt ->
                tiltDegrees = MapViewPrefs.clampTilt(newTilt)
                MapViewPrefs.setTiltDegrees(prefsContext, tiltDegrees)
            },
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .padding(end = 16.dp),
        )

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(120.dp)
                .background(
                    Brush.verticalGradient(
                        listOf(Color.Black.copy(alpha = 0.45f), Color.Transparent),
                    ),
                ),
        )

        // In landscape, a full-width stats card (like portrait's) would cover most of the map -
        // dock it to the left as a narrower column instead, matching the pause/finish card
        // moving to the right, so the map stays visible in between.
        Surface(
            modifier = if (isLandscape) {
                Modifier
                    .align(Alignment.CenterStart)
                    .fillMaxWidth(0.42f)
                    .statusBarsPadding()
                    .padding(12.dp)
            } else {
                Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(12.dp)
            },
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
            shadowElevation = 4.dp,
        ) {
            Column(modifier = Modifier.padding(12.dp).verticalScroll(rememberScrollState())) {
                LinearProgressIndicator(
                    progress = { stats.progressFraction.toFloat() },
                    modifier = Modifier.fillMaxWidth(),
                )
                currentRoute?.let { route ->
                    ElevationProfileChart(
                        route = route,
                        progressMeters = stats.distanceMeters,
                        modifier = Modifier.fillMaxWidth().height(36.dp).padding(top = 8.dp),
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    StatChip("Distance", Units.formatMiles(stats.distanceMeters))
                    StatChip("Time", Units.formatDuration(stats.elapsedSeconds))
                    StatChip("Speed", Units.formatMph(stats.currentSpeedMps))
                }
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    StatChip("Cadence", Units.formatCadence(stats.currentCadenceRpm))
                    StatChip("Power", Units.formatWatts(stats.currentPowerWatts?.toDouble()))
                    StatChip("Heart Rate", Units.formatHeartRate(stats.currentHeartRateBpm))
                }
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    StatChip("Grade", Units.formatGrade(stats.currentGradePercent))
                    StatChip("ERG Target", Units.formatWatts(stats.currentTargetWatts?.toDouble()))
                }
                selectedWorkout?.let { workout ->
                    WorkoutProfileChart(
                        workout = workout,
                        progressSeconds = stats.elapsedSeconds,
                        modifier = Modifier.fillMaxWidth().height(48.dp).padding(top = 8.dp),
                    )
                }
                controlStatusText(gradeControlState, stats.currentTargetWatts != null)?.let { statusText ->
                    Text(
                        statusText,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
                if (trainerConnected != BleConnectionState.CONNECTED) {
                    Text(
                        "Trainer not connected — pair it first to track distance.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            }
        }

        // Landscape mirrors the stats card: dock to the right as a narrower column instead of
        // spanning the full width, so it doesn't cover the map.
        Card(
            modifier = if (isLandscape) {
                Modifier
                    .align(Alignment.BottomEnd)
                    .fillMaxWidth(0.32f)
                    .navigationBarsPadding()
                    .padding(16.dp)
            } else {
                Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(16.dp)
            },
        ) {
            if (stats.state == RideState.IDLE) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "Workout: ${selectedWorkout?.name ?: "None"}",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    TextButton(onClick = { showWorkoutPicker = true }) {
                        Text(if (selectedWorkout == null) "Choose" else "Change")
                    }
                }
            }
            RideActionButtons(state = stats.state, isLandscape = isLandscape, viewModel = viewModel)
        }
    }

    if (showWorkoutPicker) {
        WorkoutPickerDialog(
            workouts = availableWorkouts,
            onSelect = { workoutId ->
                viewModel.selectWorkout(workoutId)
                showWorkoutPicker = false
            },
            onDismiss = { showWorkoutPicker = false },
        )
    }
}

@Composable
private fun RideActionButtons(state: RideState, isLandscape: Boolean, viewModel: RideViewModel) {
    if (isLandscape) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            when (state) {
                RideState.IDLE -> {
                    Button(onClick = viewModel::start, modifier = Modifier.fillMaxWidth()) {
                        Text("Start Ride")
                    }
                }
                RideState.RIDING -> {
                    OutlinedButton(onClick = viewModel::pause, modifier = Modifier.fillMaxWidth()) {
                        Text("Pause")
                    }
                    Button(onClick = viewModel::finishManually, modifier = Modifier.fillMaxWidth()) {
                        Text("Finish")
                    }
                }
                RideState.PAUSED -> {
                    Button(onClick = viewModel::start, modifier = Modifier.fillMaxWidth()) {
                        Text("Resume")
                    }
                    Button(onClick = viewModel::finishManually, modifier = Modifier.fillMaxWidth()) {
                        Text("Finish")
                    }
                }
                RideState.FINISHED -> Unit
            }
        }
    } else {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            when (state) {
                RideState.IDLE -> {
                    Button(onClick = viewModel::start, modifier = Modifier.weight(1f)) {
                        Text("Start Ride")
                    }
                }
                RideState.RIDING -> {
                    OutlinedButton(onClick = viewModel::pause, modifier = Modifier.weight(1f)) {
                        Text("Pause")
                    }
                    Button(onClick = viewModel::finishManually, modifier = Modifier.weight(1f)) {
                        Text("Finish")
                    }
                }
                RideState.PAUSED -> {
                    Button(onClick = viewModel::start, modifier = Modifier.weight(1f)) {
                        Text("Resume")
                    }
                    Button(onClick = viewModel::finishManually, modifier = Modifier.weight(1f)) {
                        Text("Finish")
                    }
                }
                RideState.FINISHED -> Unit
            }
        }
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

@Composable
private fun MapControls(
    headingUp: Boolean,
    tiltDegrees: Float,
    onZoomIn: () -> Unit,
    onZoomOut: () -> Unit,
    onToggleHeadingUp: () -> Unit,
    onTiltChanged: (Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        MapControlButton(onClick = onZoomIn) {
            Icon(Icons.Filled.Add, contentDescription = "Zoom in")
        }
        MapControlButton(onClick = onZoomOut) {
            Icon(Icons.Filled.Remove, contentDescription = "Zoom out")
        }
        // Highlighted when locked to north-up, since heading-up (bike always points up) is the
        // default - the highlight calls out that the rider has switched away from it.
        MapControlButton(
            onClick = onToggleHeadingUp,
            highlighted = !headingUp,
        ) {
            Icon(
                Icons.Filled.Explore,
                contentDescription = if (headingUp) "Switch to north-up" else "Switch to heading-up",
            )
        }
        // A button-driven toggle can't express "how much" tilt, and the two-finger tilt gesture
        // is easy to miss/fumble on a phone propped up mid-ride - this slider is the reliable,
        // discoverable control for it.
        TiltSlider(tiltDegrees = tiltDegrees, onTiltChanged = onTiltChanged)
    }
}

@Composable
private fun TiltSlider(tiltDegrees: Float, onTiltChanged: (Float) -> Unit) {
    // Local copy so dragging feels immediate; the real tiltDegrees (and the map update it
    // triggers) only commits on release, so a fast drag doesn't queue up a pile of camera
    // animations fighting each other.
    var sliderPosition by remember(tiltDegrees) { mutableStateOf(tiltDegrees) }
    Surface(
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
        shadowElevation = 4.dp,
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(Icons.Filled.Terrain, contentDescription = "Camera tilt")
            Slider(
                value = sliderPosition,
                onValueChange = { sliderPosition = it },
                onValueChangeFinished = { onTiltChanged(sliderPosition) },
                valueRange = 0f..RIDE_CAMERA_MAX_PITCH_DEGREES.toFloat(),
                modifier = Modifier.width(120.dp),
            )
        }
    }
}

@Composable
private fun MapControlButton(
    onClick: () -> Unit,
    highlighted: Boolean = false,
    content: @Composable () -> Unit,
) {
    Surface(
        onClick = onClick,
        shape = CircleShape,
        color = if (highlighted) {
            MaterialTheme.colorScheme.primaryContainer
        } else {
            MaterialTheme.colorScheme.surface.copy(alpha = 0.92f)
        },
        shadowElevation = 4.dp,
        modifier = Modifier.size(48.dp),
    ) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
            content()
        }
    }
}

/** Sticky ride-map zoom/rotation-mode preference, shared across all rides. */
private object MapViewPrefs {
    private const val PREFS_NAME = "map_view_prefs"
    private const val KEY_ZOOM = "zoom"
    private const val KEY_HEADING_UP = "heading_up"
    private const val KEY_TILT_DEGREES = "tilt_degrees"
    private const val DEFAULT_ZOOM = 18.0
    private const val MIN_ZOOM = 12.0
    private const val MAX_ZOOM = 20.0

    fun getZoom(context: Context): Double =
        prefs(context).getFloat(KEY_ZOOM, DEFAULT_ZOOM.toFloat()).toDouble()

    fun setZoom(context: Context, zoom: Double) {
        prefs(context).edit().putFloat(KEY_ZOOM, zoom.toFloat()).apply()
    }

    fun getHeadingUp(context: Context): Boolean = prefs(context).getBoolean(KEY_HEADING_UP, true)

    fun setHeadingUp(context: Context, headingUp: Boolean) {
        prefs(context).edit().putBoolean(KEY_HEADING_UP, headingUp).apply()
    }

    fun getTiltDegrees(context: Context): Float =
        prefs(context).getFloat(KEY_TILT_DEGREES, RIDE_CAMERA_DEFAULT_TILT_DEGREES.toFloat())

    fun setTiltDegrees(context: Context, tiltDegrees: Float) {
        prefs(context).edit().putFloat(KEY_TILT_DEGREES, tiltDegrees).apply()
    }

    fun clampZoom(zoom: Double): Double = zoom.coerceIn(MIN_ZOOM, MAX_ZOOM)

    fun clampTilt(tiltDegrees: Float): Float = tiltDegrees.coerceIn(0f, RIDE_CAMERA_MAX_PITCH_DEGREES.toFloat())

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}

/** Null when there's nothing worth telling the rider (not connected, or trainer doesn't
 * support it at all) - only surface the states that need a heads-up. [hasTarget] picks the
 * wording: ERG mode (workout target power) vs simulated-grade auto-resistance. */
private fun controlStatusText(state: GradeControlState, hasTarget: Boolean): String? = when (state) {
    GradeControlState.REQUESTING -> "Trainer control: connecting…"
    GradeControlState.ACTIVE -> if (hasTarget) "ERG mode: on" else "Auto-resistance: on"
    GradeControlState.REJECTED -> "Trainer control: unavailable"
    GradeControlState.UNAVAILABLE -> null
}

@Composable
private fun WorkoutPickerDialog(workouts: List<Workout>, onSelect: (String?) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Choose Workout") },
        text = {
            if (workouts.isEmpty()) {
                Text("No workouts imported yet. Add one from Settings > Workout Library.")
            } else {
                Column {
                    workouts.forEach { workout ->
                        TextButton(
                            onClick = { onSelect(workout.id) },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(workout.name, modifier = Modifier.weight(1f))
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onSelect(null) }) { Text("None") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

@Composable
private fun StatChip(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, style = MaterialTheme.typography.titleMedium)
        Text(label, style = MaterialTheme.typography.labelSmall)
    }
}
