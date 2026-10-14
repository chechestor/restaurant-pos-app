package com.smg.pos

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat

class ShiftService : Service() {
    private var pollThread: Thread? = null
    private var running = false
    private var wakeLock: PowerManager.WakeLock? = null
    private val wakeLockGuard = Any()
    private var lastCalls: List<WaiterCall> = emptyList()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        createChannel()
        startInForeground()
        acquireWakeLock()
        if (!SessionStore.hasUsableSession()) {
            stopShift()
            return START_NOT_STICKY
        }
        ensurePollThread()
        return START_STICKY
    }

    override fun onDestroy() {
        running = false
        pollThread?.interrupt()
        SignalPlayer.stop()
        releaseWakeLock()
        super.onDestroy()
    }

    private fun ensurePollThread() {
        if (pollThread?.isAlive == true) return
        running = true
        pollThread = Thread({ pollLoop() }, "shift-poll").also { it.start() }
    }

    private fun pollLoop() {
        while (running) {
            val epoch = SessionStore.epoch
            try {
                if (!running) return
                if (!SessionStore.hasUsableSession()) {
                    if (epoch == SessionStore.epoch && ShiftState.phase != ShiftState.Phase.LOGIN) {
                        SessionStore.clearSession()
                        ShiftState.showLogin(getString(R.string.session_expired))
                    }
                    stopShift()
                    return
                }
                acquireWakeLock()
                when (val result = PosClient.fetchCalls()) {
                    is PosClient.CallsResult.Ok -> {
                        if (!running || epoch != SessionStore.epoch) continue
                        val fresh = if (!SessionStore.isBaselineDone()) {
                            SessionStore.markBaseline(result.calls.map { it.id })
                            emptyList()
                        } else {
                            SessionStore.consumeNew(result.calls)
                        }
                        lastCalls = result.calls
                        ShiftState.showList(result.calls, isOffline = false)
                        if (fresh.isNotEmpty()) SignalPlayer.enqueue(this, fresh.size)
                    }
                    PosClient.CallsResult.Forbidden -> {
                        if (!running || epoch != SessionStore.epoch) continue
                        SignalPlayer.stop()
                        ShiftState.showDenied()
                        stopShift()
                        return
                    }
                    PosClient.CallsResult.SessionExpired -> {
                        if (!running || epoch != SessionStore.epoch) continue
                        SignalPlayer.stop()
                        SessionStore.clearSession()
                        ShiftState.showLogin(getString(R.string.session_expired))
                        stopShift()
                        return
                    }
                    PosClient.CallsResult.Network -> {
                        if (!running || epoch != SessionStore.epoch) continue
                        ShiftState.showList(lastCalls, isOffline = true)
                    }
                }
            } catch (_: InterruptedException) {
                return
            } catch (_: Exception) {
                if (running && epoch == SessionStore.epoch) {
                    ShiftState.showList(lastCalls, isOffline = true)
                }
            }
            var waited = 0
            while (running && waited < POLL_MS) {
                try {
                    Thread.sleep(200)
                } catch (_: InterruptedException) {
                    return
                }
                waited += 200
            }
        }
    }

    private fun startInForeground() {
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= 29) {
            val type = if (Build.VERSION.SDK_INT >= 34) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            } else {
                0
            }
            startForeground(NOTIFICATION_ID, notification, type)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun buildNotification(): Notification {
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_shift)
            .setContentTitle(getString(R.string.shift_running))
            .setContentText(getString(R.string.shift_running_detail))
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    private fun createChannel() {
        val manager = getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.shift_channel),
            NotificationManager.IMPORTANCE_DEFAULT,
        )
        channel.setSound(null, null)
        channel.enableVibration(false)
        channel.setShowBadge(false)
        manager.createNotificationChannel(channel)
    }

    private fun acquireWakeLock() {
        synchronized(wakeLockGuard) {
            val power = getSystemService(PowerManager::class.java)
            val lock = wakeLock ?: power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "smgpos:shift").also {
                it.setReferenceCounted(false)
                wakeLock = it
            }
            if (lock.isHeld) lock.release()
            lock.acquire(60 * 60 * 1000L)
        }
    }

    private fun releaseWakeLock() {
        synchronized(wakeLockGuard) {
            wakeLock?.runCatching { if (isHeld) release() }
        }
    }

    private fun stopShift() {
        running = false
        pollThread?.interrupt()
        releaseWakeLock()
        if (Build.VERSION.SDK_INT >= 24) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
        stopSelf()
    }

    companion object {
        private const val CHANNEL_ID = "shift"
        private const val NOTIFICATION_ID = 1001
        private const val POLL_MS = 5_000

        fun start(context: Context) {
            context.startForegroundService(Intent(context, ShiftService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, ShiftService::class.java))
        }
    }
}
