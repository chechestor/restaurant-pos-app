package com.smg.pos

import java.util.concurrent.CopyOnWriteArrayList

object ShiftState {
    enum class Phase { LOADING, LIST, DENIED, LOGIN }

    interface Listener {
        fun onShiftChanged()
    }

    private val listeners = CopyOnWriteArrayList<Listener>()

    @Volatile
    var calls: List<WaiterCall> = emptyList()

    @Volatile
    var phase: Phase = Phase.LOADING

    @Volatile
    var offline: Boolean = false

    @Volatile
    var loginMessage: String? = null

    fun addListener(listener: Listener) {
        listeners.add(listener)
    }

    fun removeListener(listener: Listener) {
        listeners.remove(listener)
    }

    fun showLoading() = publish {
        phase = Phase.LOADING
        offline = false
        calls = emptyList()
    }

    fun showList(items: List<WaiterCall>, isOffline: Boolean) = publish {
        phase = Phase.LIST
        calls = items
        offline = isOffline
    }

    fun showDenied() = publish {
        phase = Phase.DENIED
        calls = emptyList()
        offline = false
    }

    fun showLogin(message: String?) = publish {
        phase = Phase.LOGIN
        calls = emptyList()
        offline = false
        loginMessage = message
    }

    private fun publish(block: () -> Unit) {
        synchronized(this) { block() }
        listeners.forEach { it.onShiftChanged() }
    }
}
