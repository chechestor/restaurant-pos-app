package com.smg.pos

import android.app.Application

class PosApp : Application() {
    override fun onCreate() {
        super.onCreate()
        SessionStore.init(this)
    }
}
