package com.stash.core.data.onedrive

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Foreground service that keeps an OneDrive sync pass alive while the
 * app is backgrounded, the screen is off, or the user swipes the app
 * away — Android only guarantees long network work for foreground
 * services, and Samsung in particular freezes plain background apps
 * within minutes.
 *
 * The service does NOT run the sync itself — [OneDriveSyncManager] owns
 * execution (single-flight, stoppable). This service just (1) asks the
 * manager to start, (2) mirrors [OneDriveSyncManager.progress] into a
 * progress notification with a Stop action, and (3) stops itself when
 * the pass finishes or is stopped.
 */
@AndroidEntryPoint
class OneDriveSyncService : Service() {

    @Inject
    lateinit var syncManager: OneDriveSyncManager

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            syncManager.stopSync()
            stopSelf()
            return START_NOT_STICKY
        }

        startForeground(NOTIFICATION_ID, buildNotification("Starting sync…", 0, 0))
        syncManager.requestSync()

        scope.launch {
            // Give the manager a beat to flip `running`, then mirror
            // progress until the pass ends.
            var sawRunning = false
            syncManager.progress.collectLatest { p ->
                if (p.running) {
                    sawRunning = true
                    notify(buildNotification(progressText(p), p.total, p.done))
                } else if (sawRunning) {
                    stopSelf()
                }
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun progressText(p: OneDriveSyncManager.SyncProgress): String {
        val eta = p.etaMs?.let { " · ${formatEta(it)} left" } ?: ""
        val current = p.uploading?.let { " — $it" } ?: ""
        return "${p.percent}%$eta$current"
    }

    private fun buildNotification(text: String, max: Int, progress: Int): Notification {
        val stopIntent = PendingIntent.getService(
            this,
            0,
            Intent(this, OneDriveSyncService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentTitle("Syncing to OneDrive")
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setProgress(max, progress, max == 0)
            .addAction(0, "Stop", stopIntent)
            .build()
    }

    private fun notify(notification: Notification) {
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
            .notify(NOTIFICATION_ID, notification)
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "OneDrive sync",
                NotificationManager.IMPORTANCE_LOW,
            ).apply { description = "Progress while your library uploads to OneDrive" }
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
                .createNotificationChannel(channel)
        }
    }

    companion object {
        private const val CHANNEL_ID = "onedrive_sync"
        private const val NOTIFICATION_ID = 41702
        const val ACTION_STOP = "com.stash.onedrive.STOP_SYNC"

        /** Start (or no-op into) a foreground-backed sync pass. */
        fun start(context: Context) {
            val intent = Intent(context, OneDriveSyncService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun formatEta(ms: Long): String {
            val totalMin = ms / 60_000
            return when {
                totalMin < 1 -> "under a minute"
                totalMin < 60 -> "${totalMin}m"
                else -> "${totalMin / 60}h ${totalMin % 60}m"
            }
        }
    }
}
