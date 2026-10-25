package com.smg.pos

import android.app.Application
import androidx.appcompat.app.AppCompatDelegate

class PosApp : Application() {
    override fun onCreate() {
        super.onCreate()
        SessionStore.init(this)
        AppCompatDelegate.setDefaultNightMode(SessionStore.nightMode())
    }
}
