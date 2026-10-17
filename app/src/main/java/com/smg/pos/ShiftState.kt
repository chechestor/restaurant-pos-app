package com.smg.pos

import java.util.concurrent.CopyOnWriteArrayList

object ShiftState {
    enum class Phase { LOADING, LIST, DENIED, LOGIN, IDLE }

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

    @Volatile
    var canManage: Boolean = false

    @Volatile
    var showRestaurant: Boolean = false

    @Volatile
    var manageKnown: Boolean = false

    @Volatile
    var pendingStatus: Map<Int, String> = emptyMap()

    @Volatile
    var statusErrors: Map<Int, String> = emptyMap()

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
        resetStatusAccess()
    }

    fun showList(items: List<WaiterCall>, isOffline: Boolean) = publish {
        phase = Phase.LIST
        calls = items
        offline = isOffline
        val present = items.mapTo(HashSet()) { it.id }
        pendingStatus = pendingStatus.filterKeys { it in present }
        statusErrors = statusErrors.filterKeys { it in present }
    }

    fun showDenied() = publish {
        phase = Phase.DENIED
        calls = emptyList()
        offline = false
        resetStatusAccess()
    }

    fun showShiftOff() = publish {
        phase = Phase.IDLE
        calls = emptyList()
        offline = false
        resetStatusAccess()
    }

    fun showLogin(message: String?) = publish {
        phase = Phase.LOGIN
        calls = emptyList()
        offline = false
        loginMessage = message
        resetStatusAccess()
    }

    fun publishCanManage(allowed: Boolean, showRestaurant: Boolean) = publish {
        canManage = allowed
        this.showRestaurant = showRestaurant
        manageKnown = true
    }

    fun beginStatusChange(id: Int, status: String) = publish {
        pendingStatus = pendingStatus + (id to status)
        statusErrors = statusErrors - id
    }

    fun finishStatusChange(id: Int, error: String?) = publish {
        pendingStatus = pendingStatus - id
        statusErrors = if (error == null) statusErrors - id else statusErrors + (id to error)
    }

    private fun resetStatusAccess() {
        canManage = false
        showRestaurant = false
        manageKnown = false
        pendingStatus = emptyMap()
        statusErrors = emptyMap()
    }

    private fun publish(block: () -> Unit) {
        synchronized(this) { block() }
        listeners.forEach { it.onShiftChanged() }
    }
}
