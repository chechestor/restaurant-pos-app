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
        val current = synchronized(lock) {
            stopped = true
            queue.clear()
            val active = player
            player = null
            lock.notifyAll()
            active
        }
        current?.runCatching {
            stop()
            release()
        }
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

        val soundMs = media?.duration?.takeIf { it > 0 }?.toLong() ?: if (sound) 500L else 0L
        val waitMs = maxOf(soundMs, if (vibration) 300L else 0L)
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
        val vibrator = if (Build.VERSION.SDK_INT >= 31) {
            val manager = app.getSystemService(VibratorManager::class.java)
            manager?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            app.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        } ?: return
        if (!vibrator.hasVibrator()) return
        vibrator.vibrate(VibrationEffect.createOneShot(300, VibrationEffect.DEFAULT_AMPLITUDE))
    }
}
