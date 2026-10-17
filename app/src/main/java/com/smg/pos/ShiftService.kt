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
    private var pollGeneration = 0
    private var serviceStartId = 0
    private val pollLock = Any()
    private var wakeLock: PowerManager.WakeLock? = null
    private val wakeLockGuard = Any()
    private var lastCalls: List<WaiterCall> = emptyList()
    private val callsLock = Any()
    private var observedEpoch = -1L
    private val overrides = HashMap<Int, SavedStatus>()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val started = synchronized(pollLock) {
            serviceStartId = startId
            runningService = this
            createChannel()
            startInForeground()
            val previous = pollThread
            if (!SessionStore.hasUsableSession() || !SessionStore.isShiftOn()) {
                pollGeneration += 1
                running = false
                pollThread = null
                previous?.interrupt()
                null
            } else {
                pollGeneration += 1
                running = true
                val generation = pollGeneration
                val thread = Thread({ pollLoop(generation, startId) }, "shift-poll")
                pollThread = thread
                previous?.interrupt()
                thread
            }
        }
        if (started == null) {
            releaseWakeLock()
            removeForeground()
            stopSelf(startId)
            return START_NOT_STICKY
        }
        started.start()
        acquireWakeLock()
        noteEpoch()
        return START_STICKY
    }

    override fun onDestroy() {
        val thread = synchronized(pollLock) {
            pollGeneration += 1
            running = false
            val current = pollThread
            pollThread = null
            current
        }
        if (runningService === this) runningService = null
        thread?.interrupt()
        SignalPlayer.stop()
        releaseWakeLock()
        super.onDestroy()
    }

    private fun pollLoop(generation: Int, startId: Int) {
        while (running && generation == pollGeneration) {
            val epoch = SessionStore.epoch
            try {
                if (!running || generation != pollGeneration) return
                noteEpoch()
                if (!SessionStore.isShiftOn()) {
                    if (generation == pollGeneration) stopShift(startId)
                    return
                }
                if (!SessionStore.hasUsableSession()) {
                    if (epoch == SessionStore.epoch && ShiftState.phase != ShiftState.Phase.LOGIN) {
                        SessionStore.clearSession()
                        ShiftState.showLogin(getString(R.string.session_expired))
                    }
                    stopShift(startId)
                    return
                }
                if (!ShiftState.manageKnown) {
                    when (val me = PosClient.fetchManagePermission()) {
                        is PosClient.ManageResult.Known -> {
                            if (running && generation == pollGeneration && epoch == SessionStore.epoch) {
                                ShiftState.publishCanManage(me.allowed, me.showRestaurant)
                            }
                        }
                        PosClient.ManageResult.SessionExpired -> {
                            if (!running || generation != pollGeneration || epoch != SessionStore.epoch) return
                            SignalPlayer.stop()
                            SessionStore.clearSession()
                            ShiftState.showLogin(getString(R.string.session_expired))
                            stopShift(startId)
                            return
                        }
                        PosClient.ManageResult.Network -> Unit
                    }
                }
                acquireWakeLock()
                val fetchedAt = System.currentTimeMillis()
                when (val result = PosClient.fetchCalls()) {
                    is PosClient.CallsResult.Ok -> {
                        if (!running || generation != pollGeneration || epoch != SessionStore.epoch || !SessionStore.isShiftOn()) continue
                        val fresh = if (!SessionStore.isBaselineDone()) {
                            SessionStore.markBaseline(result.calls.map { it.id })
                            emptyList()
                        } else {
                            SessionStore.consumeNew(result.calls)
                        }
                        synchronized(callsLock) {
                            if (!running || generation != pollGeneration || epoch != SessionStore.epoch || !SessionStore.isShiftOn()) return@synchronized
                            lastCalls = applyOverrides(result.calls, fetchedAt)
                            ShiftState.showList(lastCalls, isOffline = false)
                            if (fresh.isNotEmpty()) SignalPlayer.enqueue(this, fresh.size)
                        }
                    }
                    PosClient.CallsResult.Forbidden -> {
                        if (!running || generation != pollGeneration || epoch != SessionStore.epoch) continue
                        SignalPlayer.stop()
                        ShiftState.showDenied()
                        stopShift(startId)
                        return
                    }
                    PosClient.CallsResult.SessionExpired -> {
                        if (!running || generation != pollGeneration || epoch != SessionStore.epoch) continue
                        SignalPlayer.stop()
                        SessionStore.clearSession()
                        ShiftState.showLogin(getString(R.string.session_expired))
                        stopShift(startId)
                        return
                    }
                    PosClient.CallsResult.Network -> {
                        if (!running || generation != pollGeneration || epoch != SessionStore.epoch) continue
                        synchronized(callsLock) {
                            ShiftState.showList(lastCalls, isOffline = true)
                        }
                    }
                }
            } catch (_: InterruptedException) {
                return
            } catch (_: Exception) {
                if (running && generation == pollGeneration && epoch == SessionStore.epoch) {
                    ShiftState.showList(lastCalls, isOffline = true)
                }
            }
            var waited = 0
            while (running && generation == pollGeneration && waited < POLL_MS) {
                try {
                    Thread.sleep(200)
                } catch (_: InterruptedException) {
                    return
                }
                waited += 200
            }
        }
    }

    private fun noteEpoch() {
        val epoch = SessionStore.epoch
        if (epoch == observedEpoch) return
        observedEpoch = epoch
        synchronized(callsLock) {
            overrides.clear()
            lastCalls = emptyList()
        }
    }

    private fun rememberStatus(id: Int, status: String, epoch: Long) {
        synchronized(callsLock) {
            if (!running || epoch != SessionStore.epoch) return
            overrides[id] = SavedStatus(status, System.currentTimeMillis())
            val base = if (lastCalls.any { it.id == id }) lastCalls else ShiftState.calls
            lastCalls = base.map { if (it.id == id) it.copy(status = status) else it }
            ShiftState.showList(lastCalls, isOffline = false)
        }
    }

    private fun applyOverrides(server: List<WaiterCall>, fetchedAt: Long): List<WaiterCall> {
        if (overrides.isEmpty()) return server
        overrides.keys.retainAll(server.mapTo(HashSet()) { it.id })
        return server.map { call ->
            val saved = overrides[call.id] ?: return@map call
            when {
                call.status == saved.status -> {
                    overrides.remove(call.id)
                    call
                }
                fetchedAt < saved.at -> call.copy(status = saved.status)
                else -> {
                    overrides.remove(call.id)
                    call
                }
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

    private fun removeForeground() {
        if (Build.VERSION.SDK_INT >= 24) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
    }

    private fun stopShift(startId: Int) {
        val thread = synchronized(pollLock) {
            if (startId != serviceStartId) return
            pollGeneration += 1
            running = false
            val current = pollThread
            pollThread = null
            releaseWakeLock()
            removeForeground()
            current
        }
        thread?.interrupt()
        stopSelf(startId)
    }

    private data class SavedStatus(val status: String, val at: Long)

    companion object {
        private const val CHANNEL_ID = "shift"
        private const val NOTIFICATION_ID = 1001
        private const val POLL_MS = 5_000

        @Volatile
        private var runningService: ShiftService? = null

        fun applySavedStatus(id: Int, status: String, epoch: Long) {
            val service = runningService
            if (service == null) {
                if (epoch != SessionStore.epoch) return
                val merged = ShiftState.calls.map { if (it.id == id) it.copy(status = status) else it }
                ShiftState.showList(merged, isOffline = false)
                return
            }
            service.rememberStatus(id, status, epoch)
        }

        fun start(context: Context) {
            context.startForegroundService(Intent(context, ShiftService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, ShiftService::class.java))
        }
    }
}
