package com.smg.pos

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != Intent.ACTION_BOOT_COMPLETED) return
        if (!SessionStore.hasUsableSession() || !SessionStore.isShiftOn()) return
        ShiftService.start(context)
    }
}
