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
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach

/**
 * Keeps the trainer BLE connection and ride clock alive while the screen is off, with a live
 * stats notification. Reads the shared [RideEngine] instance the ViewModel published on
 * [OsmRideApp.currentRideEngine] before starting this service.
 */
class RideForegroundService : LifecycleService() {

    @SuppressLint("MissingPermission")
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        startForeground(NOTIFICATION_ID, buildNotification(0.0, 0L))

        (application as OsmRideApp).currentRideEngine
            ?.stats
            ?.onEach { stats ->
                val manager = NotificationManagerCompat.from(this)
                manager.notify(NOTIFICATION_ID, buildNotification(stats.distanceMeters, stats.elapsedSeconds))
            }
            ?.launchIn(lifecycleScope)

        return START_STICKY
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
