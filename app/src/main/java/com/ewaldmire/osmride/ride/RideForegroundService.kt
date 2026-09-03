package com.ewaldmire.osmride.ride

import android.annotation.SuppressLint
import android.app.Notification
import android.app.PendingIntent
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.ewaldmire.osmride.MainActivity
import com.ewaldmire.osmride.OsmRideApp
import com.ewaldmire.osmride.R
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Owns driving the active ride: feeds trainer/HR BLE samples and a 1Hz clock tick into the
 * shared [RideEngine] on [OsmRideApp.currentRideEngine], and shows a live stats notification.
 *
 * This runs independently of any UI screen. [com.ewaldmire.osmride.ui.ride.RideViewModel] is
 * only a thin observer of the engine's stats and gets torn down/recreated by navigation like any
 * other ViewModel - if that ViewModel were the one driving the engine (as it used to be), the
 * ride would stop progressing (and its onCleared() would kill this service) the moment the user
 * navigated away from the ride screen, e.g. to fix a Bluetooth connection. Keeping the drive loop
 * here instead means the ride keeps going regardless, and the UI can reattach to it later.
 */
@SuppressLint("MissingPermission")
class RideForegroundService : LifecycleService() {
    private var isDriving = false

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        startForeground(NOTIFICATION_ID, buildNotification(0.0, 0L))

        if (!isDriving) {
            isDriving = true
            startDriving()
        }

        return START_STICKY
    }

    private fun startDriving() {
        val app = application as OsmRideApp
        val engine = app.currentRideEngine ?: return

        app.trainerBleManager.samples.onEach { engine.onTrainerSample(it) }.launchIn(lifecycleScope)
        app.heartRateBleManager.samples.onEach { engine.onHeartRateSample(it) }.launchIn(lifecycleScope)

        lifecycleScope.launch {
            while (isActive) {
                delay(1000)
                engine.onClockTick()
            }
        }

        engine.stats
            .onEach { stats ->
                val manager = NotificationManagerCompat.from(this)
                manager.notify(NOTIFICATION_ID, buildNotification(stats.distanceMeters, stats.elapsedSeconds))
                // A workout's target power and a route's simulated grade are mutually exclusive
                // trainer control modes - ERG (workout) takes priority when both are present.
                val targetWatts = stats.currentTargetWatts
                if (targetWatts != null) {
                    app.trainerBleManager.setTargetPower(targetWatts)
                } else {
                    stats.currentGradePercent?.let { app.trainerBleManager.setSimulatedGrade(it) }
                }
                if (stats.state == RideState.FINISHED) {
                    stopSelf()
                }
            }
            .launchIn(lifecycleScope)
    }

    private fun buildNotification(distanceMeters: Double, elapsedSeconds: Long): Notification {
        val miles = distanceMeters / METERS_PER_MILE
        val minutes = elapsedSeconds / 60
        val seconds = elapsedSeconds % 60
        val contentIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.ride_notification_title))
            .setContentText(String.format("%.2f mi · %d:%02d", miles, minutes, seconds))
            .setSmallIcon(R.drawable.ic_notification)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(contentIntent)
            .build()
    }

    companion object {
        const val CHANNEL_ID = "ride_tracking"
        const val NOTIFICATION_ID = 1001
        private const val METERS_PER_MILE = 1609.344
    }
}
