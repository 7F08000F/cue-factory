package com.cuefactory.app.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.cuefactory.app.AppContainer
import com.cuefactory.app.MainActivity
import com.cuefactory.app.R
import com.cuefactory.app.util.AppLog
import com.cuefactory.core.model.JobState
import com.cuefactory.core.model.SplitJob

/**
 * Keeps the process elevated while split jobs are active.
 *
 * Android requires [startForeground] very soon after [Context.startForegroundService].
 * We promote in [onCreate] (not only [onStartCommand]) so heavy main-thread work or
 * delayed enqueues cannot trigger ForegroundServiceDidNotStartInTimeException.
 */
class SplitForegroundService : Service() {
    private val mainHandler = Handler(Looper.getMainLooper())
    private var stopIfIdlePosted = false

    private val stopIfIdle = Runnable {
        stopIfIdlePosted = false
        val jobs = AppContainer.get(this).jobQueue.snapshot()
        if (!hasActive(jobs)) {
            AppLog.i("no active jobs after grace — stop foreground service")
            stopForegroundCompat()
            stopSelf()
        }
    }

    private val listener: (List<SplitJob>) -> Unit = { jobs ->
        if (!hasActive(jobs)) {
            scheduleStopIfIdle()
        } else {
            cancelStopIfIdle()
            val nm = getSystemService(NotificationManager::class.java)
            nm.notify(NOTIFICATION_ID, buildNotification(jobs))
        }
    }

    override fun onCreate() {
        super.onCreate()
        AppLog.i("SplitForegroundService onCreate")
        ensureChannel()
        // Must run before any other work that could delay the FGS handshake.
        promoteToForeground(AppContainer.get(this).jobQueue.snapshot())
        AppContainer.get(this).addJobListener(listener)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return try {
            val jobs = AppContainer.get(this).jobQueue.snapshot()
            promoteToForeground(jobs)
            if (!hasActive(jobs)) {
                scheduleStopIfIdle()
            } else {
                cancelStopIfIdle()
            }
            START_STICKY
        } catch (e: Exception) {
            AppLog.e("onStartCommand failed: ${e.message}", e)
            // Still try to satisfy FGS contract before exiting.
            runCatching { promoteToForeground(emptyList()) }
            stopSelf()
            START_NOT_STICKY
        }
    }

    override fun onDestroy() {
        cancelStopIfIdle()
        AppContainer.get(this).removeJobListener(listener)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun promoteToForeground(jobs: List<SplitJob>) {
        val notification = buildNotification(jobs)
        if (Build.VERSION.SDK_INT >= 34) {
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            )
        } else {
            @Suppress("DEPRECATION")
            startForeground(NOTIFICATION_ID, notification)
        }
        AppLog.i("SplitForegroundService startForeground ok active=${hasActive(jobs)}")
    }

    private fun stopForegroundCompat() {
        if (Build.VERSION.SDK_INT >= 24) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
    }

    private fun scheduleStopIfIdle() {
        if (stopIfIdlePosted) return
        stopIfIdlePosted = true
        // Grace period so "start service then enqueue" races don't tear down immediately.
        mainHandler.postDelayed(stopIfIdle, IDLE_STOP_GRACE_MS)
    }

    private fun cancelStopIfIdle() {
        if (!stopIfIdlePosted) return
        mainHandler.removeCallbacks(stopIfIdle)
        stopIfIdlePosted = false
    }

    private fun buildNotification(jobs: List<SplitJob>): Notification {
        val running = jobs.filter {
            it.state == JobState.Running || it.state == JobState.Pending || it.state == JobState.Cancelling
        }
        val title = if (running.isEmpty()) {
            getString(R.string.notify_idle)
        } else {
            getString(R.string.notify_running, running.size)
        }
        val text = running.firstOrNull()?.let { job ->
            val name = job.album.sheet.title ?: job.album.audio.fileName
            val pct = (job.progress * 100).toInt()
            "$name · $pct% (${job.completedTracks}/${job.totalTracks})"
        } ?: getString(R.string.notify_tap)

        val pi = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentIntent(pi)
            .setOnlyAlertOnce(true)
            .setOngoing(running.isNotEmpty())
            .setProgress(
                100,
                ((running.firstOrNull()?.progress ?: 0f) * 100).toInt(),
                running.isEmpty(),
            )
            .build()
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notify_channel),
            NotificationManager.IMPORTANCE_LOW,
        )
        nm.createNotificationChannel(channel)
    }

    companion object {
        private const val CHANNEL_ID = "split_jobs"
        private const val NOTIFICATION_ID = 42
        private const val IDLE_STOP_GRACE_MS = 15_000L

        fun hasActive(jobs: List<SplitJob>): Boolean =
            jobs.any {
                it.state == JobState.Running ||
                    it.state == JobState.Pending ||
                    it.state == JobState.Cancelling
            }

        fun start(context: Context) {
            try {
                val intent = Intent(context, SplitForegroundService::class.java)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
                AppLog.i("SplitForegroundService start requested")
            } catch (e: Exception) {
                // Never crash UI over notification/FGS policy
                AppLog.e("SplitForegroundService start failed: ${e.message}", e)
            }
        }
    }
}
