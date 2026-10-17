package com.smg.pos

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.view.isVisible
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlin.concurrent.thread

class MainActivity : AppCompatActivity(), ShiftState.Listener {
    private enum class Screen { LOGIN, SHIFT_OFF, CALLS, SETTINGS, DENIED }

    private lateinit var loginScreen: View
    private lateinit var shiftOffScreen: View
    private lateinit var callsScreen: View
    private lateinit var settingsScreen: View
    private lateinit var deniedScreen: View
    private lateinit var emailInput: android.widget.EditText
    private lateinit var passwordInput: android.widget.EditText
    private lateinit var loginButton: Button
    private lateinit var loginError: TextView
    private lateinit var callsList: RecyclerView
    private lateinit var callsMessage: TextView
    private lateinit var callsOffline: TextView
    private lateinit var callsNotifications: TextView
    private lateinit var soundSwitch: SwitchCompat
    private lateinit var vibrationSwitch: SwitchCompat
    private lateinit var startShiftSwitch: SwitchCompat
    private val callsAdapter = CallsAdapter()

    private var screen = Screen.LOGIN
    private var loginInFlight = false
    private var ignoreSwitch = false
    private var loginErrorText: String? = null
    private var endConfirm: AlertDialog? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        bindViews()
        screen = savedInstanceState?.getString(KEY_SCREEN)
            ?.let { runCatching { Screen.valueOf(it) }.getOrNull() }
            ?: Screen.LOGIN
        if (!SessionStore.hasUsableSession()) {
            SessionStore.clearSession()
            screen = Screen.LOGIN
        } else if (SessionStore.isShiftOn()) {
            if (screen == Screen.LOGIN || screen == Screen.SHIFT_OFF) screen = Screen.CALLS
            ShiftService.start(this)
        } else {
            screen = Screen.SHIFT_OFF
            ShiftState.showShiftOff()
            ShiftService.stop(this)
        }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (screen == Screen.SETTINGS) {
                    screen = Screen.CALLS
                    render()
                } else {
                    finish()
                }
            }
        })
        render()
    }

    override fun onStart() {
        super.onStart()
        ShiftState.addListener(this)
        render()
    }

    override fun onStop() {
        ShiftState.removeListener(this)
        super.onStop()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(KEY_SCREEN, screen.name)
    }

    override fun onShiftChanged() {
        runOnUiThread {
            if (!isDestroyed) render()
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_NOTIFICATIONS) render()
    }

    private fun bindViews() {
        loginScreen = findViewById(R.id.screen_login)
        shiftOffScreen = findViewById(R.id.screen_shift_off)
        callsScreen = findViewById(R.id.screen_calls)
        settingsScreen = findViewById(R.id.screen_settings)
        deniedScreen = findViewById(R.id.screen_denied)
        emailInput = findViewById(R.id.email)
        passwordInput = findViewById(R.id.password)
        loginButton = findViewById(R.id.login_button)
        loginError = findViewById(R.id.login_error)
        callsList = findViewById(R.id.calls_list)
        callsMessage = findViewById(R.id.calls_message)
        callsOffline = findViewById(R.id.calls_offline)
        callsNotifications = findViewById(R.id.calls_notifications)
        soundSwitch = findViewById(R.id.sound_switch)
        vibrationSwitch = findViewById(R.id.vibration_switch)
        startShiftSwitch = findViewById(R.id.start_shift_switch)

        callsList.layoutManager = LinearLayoutManager(this)
        callsList.adapter = callsAdapter
        callsAdapter.onStatusSelected = { callId, status -> changeStatus(callId, status) }

        loginButton.setOnClickListener { attemptLogin() }
        passwordInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                attemptLogin()
                true
            } else {
                false
            }
        }
        findViewById<View>(R.id.settings_button).setOnClickListener {
            screen = Screen.SETTINGS
            render()
        }
        findViewById<View>(R.id.end_shift_button).setOnClickListener { confirmEndShift() }
        findViewById<View>(R.id.denied_end_shift).setOnClickListener { confirmEndShift() }
        startShiftSwitch.setOnCheckedChangeListener { _, checked ->
            if (!ignoreSwitch && checked) startShift()
        }
        findViewById<View>(R.id.settings_back).setOnClickListener {
            screen = Screen.CALLS
            render()
        }
        soundSwitch.setOnCheckedChangeListener { _, checked ->
            if (!ignoreSwitch) SessionStore.setSoundEnabled(checked)
        }
        vibrationSwitch.setOnCheckedChangeListener { _, checked ->
            if (!ignoreSwitch) SessionStore.setVibrationEnabled(checked)
        }
    }

    private fun attemptLogin() {
        if (loginInFlight) return
        val email = emailInput.text.toString().trim()
        val password = passwordInput.text.toString()
        if (email.isEmpty() || password.isEmpty()) {
            loginErrorText = getString(R.string.enter_credentials)
            render()
            return
        }
        loginInFlight = true
        loginButton.isEnabled = false
        loginErrorText = null
        render()
        thread(name = "login") {
            val result = PosClient.login(email, password)
            runOnUiThread {
                loginInFlight = false
                loginButton.isEnabled = true
                when (result) {
                    is PosClient.LoginResult.Ok -> {
                        SessionStore.beginSession(result.access, result.refresh)
                        loginErrorText = null
                        passwordInput.text.clear()
                        screen = Screen.SHIFT_OFF
                        SignalPlayer.stop()
                        ShiftService.stop(this@MainActivity)
                        ShiftState.showShiftOff()
                        render()
                    }
                    PosClient.LoginResult.BadCredentials -> {
                        loginErrorText = getString(R.string.bad_credentials)
                        render()
                    }
                    PosClient.LoginResult.Network -> {
                        loginErrorText = getString(R.string.network_error)
                        render()
                    }
                }
            }
        }
    }

    private fun changeStatus(callId: Int, status: String) {
        if (ShiftState.pendingStatus.containsKey(callId)) return
        val current = ShiftState.calls.firstOrNull { it.id == callId }?.status
        if (current == null || current == status) return
        val epoch = SessionStore.epoch
        val failText = getString(R.string.status_failed)
        val expiredText = getString(R.string.session_expired)
        ShiftState.beginStatusChange(callId, status)
        thread(name = "status-$callId") {
            val result = PosClient.updateStatus(callId, status)
            if (epoch != SessionStore.epoch) return@thread
            when (result) {
                is PosClient.StatusResult.Ok -> {
                    ShiftService.applySavedStatus(callId, result.status, epoch)
                    if (epoch == SessionStore.epoch) ShiftState.finishStatusChange(callId, null)
                }
                PosClient.StatusResult.SessionExpired -> {
                    SignalPlayer.stop()
                    SessionStore.clearSession()
                    ShiftState.showLogin(expiredText)
                    ShiftService.stop(applicationContext)
                }
                PosClient.StatusResult.Failed -> {
                    if (epoch == SessionStore.epoch) ShiftState.finishStatusChange(callId, failText)
                }
            }
        }
    }

    private fun startShift() {
        if (SessionStore.isShiftOn()) return
        SessionStore.startShift()
        screen = Screen.CALLS
        ShiftState.showLoading()
        ShiftService.start(this)
        askNotifications()
        render()
    }

    private fun confirmEndShift() {
        if (endConfirm?.isShowing == true) return
        endConfirm = AlertDialog.Builder(this)
            .setMessage(R.string.end_shift_confirm)
            .setPositiveButton(R.string.end_shift) { _, _ -> endShift() }
            .setNegativeButton(R.string.cancel, null)
            .setOnDismissListener { endConfirm = null }
            .show()
    }

    private fun endShift() {
        SessionStore.endShift()
        screen = Screen.SHIFT_OFF
        ShiftState.showShiftOff()
        ShiftService.stop(this)
        SignalPlayer.stop()
        render()
    }

    private fun askNotifications() {
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQUEST_NOTIFICATIONS)
        }
    }

    private fun render() {
        val phase = ShiftState.phase
        val loggedIn = SessionStore.hasUsableSession()
        val shiftOn = SessionStore.isShiftOn()
        when {
            !loggedIn || phase == ShiftState.Phase.LOGIN -> screen = Screen.LOGIN
            phase == ShiftState.Phase.DENIED && shiftOn -> screen = Screen.DENIED
            !shiftOn -> screen = Screen.SHIFT_OFF
            screen == Screen.DENIED || screen == Screen.SHIFT_OFF || screen == Screen.LOGIN ->
                screen = Screen.CALLS
        }
        loginScreen.isVisible = screen == Screen.LOGIN
        shiftOffScreen.isVisible = screen == Screen.SHIFT_OFF
        callsScreen.isVisible = screen == Screen.CALLS
        settingsScreen.isVisible = screen == Screen.SETTINGS
        deniedScreen.isVisible = screen == Screen.DENIED

        val message = loginErrorText ?: if (phase == ShiftState.Phase.LOGIN) ShiftState.loginMessage else null
        loginError.isVisible = !message.isNullOrBlank()
        loginError.text = message.orEmpty()
        loginButton.isEnabled = !loginInFlight

        val calls = ShiftState.calls
        callsAdapter.submit(
            calls,
            canManage = ShiftState.canManage,
            errors = ShiftState.statusErrors,
            pending = ShiftState.pendingStatus.keys.toSet(),
        )
        callsList.isVisible = screen == Screen.CALLS && calls.isNotEmpty()
        callsMessage.isVisible = screen == Screen.CALLS && calls.isEmpty()
        callsMessage.text = when {
            phase == ShiftState.Phase.LOADING -> getString(R.string.loading)
            ShiftState.offline -> getString(R.string.offline)
            else -> getString(R.string.empty_calls)
        }
        callsOffline.isVisible = screen == Screen.CALLS && ShiftState.offline && calls.isNotEmpty()

        val notificationsOff = !NotificationManagerCompat.from(this).areNotificationsEnabled()
        callsNotifications.isVisible = screen == Screen.CALLS && notificationsOff

        ignoreSwitch = true
        soundSwitch.isChecked = SessionStore.soundEnabled()
        vibrationSwitch.isChecked = SessionStore.vibrationEnabled()
        startShiftSwitch.isChecked = false
        ignoreSwitch = false
    }

    companion object {
        private const val KEY_SCREEN = "screen"
        private const val REQUEST_NOTIFICATIONS = 1
    }
}
