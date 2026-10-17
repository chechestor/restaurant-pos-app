package com.smg.pos

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import java.util.ArrayDeque

object SignalPlayer {
    private val lock = Object()
    private val queue = ArrayDeque<Int>()
    private var worker: Thread? = null
    private var player: MediaPlayer? = null
    private var vibrator: Vibrator? = null
    private var stopped = false

    fun enqueue(context: Context, count: Int) {
        if (count <= 0) return
        val app = context.applicationContext
        synchronized(lock) {
            stopped = false
            repeat(count) { queue.addLast(0) }
            if (worker == null) {
                worker = Thread({ drain(app) }, "shift-signal")
                worker?.start()
            }
        }
    }

    fun stop() {
        val active = synchronized(lock) {
            stopped = true
            queue.clear()
            val playing = player
            player = null
            val buzzing = vibrator
            vibrator = null
            lock.notifyAll()
            playing to buzzing
        }
        active.first?.runCatching {
            stop()
            release()
        }
        active.second?.cancel()
    }

    private fun drain(app: Context) {
        while (true) {
            synchronized(lock) {
                if (stopped || queue.isEmpty()) {
                    worker = null
                    return
                }
                queue.removeFirst()
            }
            playOne(app)
            if (synchronized(lock) { stopped }) {
                synchronized(lock) { worker = null }
                return
            }
            Thread.sleep(120)
        }
    }

    private fun playOne(app: Context) {
        val sound = SessionStore.soundEnabled()
        val vibration = SessionStore.vibrationEnabled()
        if (!sound && !vibration) return
        if (synchronized(lock) { stopped }) return

        var media: MediaPlayer? = null
        if (sound) {
            media = startSound(app)
            synchronized(lock) {
                if (stopped) {
                    media?.runCatching { release() }
                    return
                }
                player = media
            }
        }
        if (vibration) vibrate(app)

        val vibrationMs = if (vibration) SessionStore.vibrationDurationMs().toLong() else 0L
        val soundMs = media?.duration?.takeIf { it > 0 }?.toLong() ?: if (sound) 500L else 0L
        val waitMs = maxOf(soundMs, vibrationMs)
        val end = System.currentTimeMillis() + waitMs
        while (System.currentTimeMillis() < end) {
            if (synchronized(lock) { stopped }) break
            Thread.sleep(40)
        }
        val active = synchronized(lock) {
            val current = player
            player = null
            current
        }
        active?.runCatching { release() }
    }

    private fun startSound(app: Context): MediaPlayer? {
        return try {
            val media = MediaPlayer()
            media.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build(),
            )
            val fd = app.resources.openRawResourceFd(R.raw.call_signal)
            media.setDataSource(fd.fileDescriptor, fd.startOffset, fd.length)
            fd.close()
            media.prepare()
            media.start()
            media
        } catch (_: Exception) {
            null
        }
    }

    private fun vibrate(app: Context) {
        val vibrator = deviceVibrator(app) ?: return
        val duration = SessionStore.vibrationDurationMs().toLong()
        val amplitude = if (vibrator.hasAmplitudeControl()) {
            SessionStore.vibrationAmplitude()
        } else {
            VibrationEffect.DEFAULT_AMPLITUDE
        }
        synchronized(lock) {
            if (stopped) return
            this.vibrator = vibrator
        }
        vibrator.vibrate(VibrationEffect.createOneShot(duration, amplitude))
        if (synchronized(lock) { stopped }) vibrator.cancel()
    }

    private fun deviceVibrator(app: Context): Vibrator? {
        val vibrator = if (Build.VERSION.SDK_INT >= 31) {
            app.getSystemService(VibratorManager::class.java)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            app.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        } ?: return null
        return vibrator.takeIf { it.hasVibrator() }
    }

    fun supportsAmplitude(context: Context): Boolean {
        return deviceVibrator(context.applicationContext)?.hasAmplitudeControl() == true
    }
}
