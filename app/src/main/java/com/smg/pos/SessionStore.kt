package com.smg.pos

import android.content.Context
import android.util.Base64
import org.json.JSONObject

object SessionStore {
    private const val PREFS = "shift_session"
    private const val KEY_ACCESS = "access_token"
    private const val KEY_REFRESH = "refresh_token"
    private const val KEY_SEEN = "seen_ids"
    private const val KEY_BASELINE = "baseline_done"
    private const val KEY_SOUND = "sound_enabled"
    private const val KEY_VIBRATION = "vibration_enabled"

    @Volatile
    var epoch: Long = 0L
        private set

    private lateinit var prefs: android.content.SharedPreferences

    fun init(context: Context) {
        prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    }

    fun hasUsableSession(): Boolean {
        val refresh = refreshToken() ?: return false
        val exp = jwtExpMillis(refresh) ?: return true
        return exp > System.currentTimeMillis()
    }

    fun accessToken(): String? = prefs.getString(KEY_ACCESS, null)?.takeIf { it.isNotEmpty() }

    fun refreshToken(): String? = prefs.getString(KEY_REFRESH, null)?.takeIf { it.isNotEmpty() }

    fun accessNeedsRefresh(): Boolean {
        val access = accessToken() ?: return true
        val exp = jwtExpMillis(access) ?: return true
        return exp - System.currentTimeMillis() < 120_000L
    }

    fun saveTokens(access: String, refresh: String) {
        prefs.edit()
            .putString(KEY_ACCESS, access)
            .putString(KEY_REFRESH, refresh)
            .commit()
    }

    fun beginSession(access: String, refresh: String) {
        epoch += 1
        prefs.edit()
            .putString(KEY_ACCESS, access)
            .putString(KEY_REFRESH, refresh)
            .remove(KEY_SEEN)
            .remove(KEY_BASELINE)
            .commit()
    }

    fun isBaselineDone(): Boolean = prefs.getBoolean(KEY_BASELINE, false)

    fun seenIds(): Set<Int> {
        return prefs.getString(KEY_SEEN, "").orEmpty()
            .split(',')
            .mapNotNull { it.toIntOrNull() }
            .toSet()
    }

    fun markBaseline(ids: Collection<Int>) {
        val merged = seenIds().toMutableSet()
        merged.addAll(ids)
        prefs.edit()
            .putString(KEY_SEEN, merged.joinToString(","))
            .putBoolean(KEY_BASELINE, true)
            .commit()
    }

    fun consumeNew(calls: List<WaiterCall>): List<WaiterCall> {
        val seen = seenIds()
        val fresh = calls.filter { it.id !in seen }.sortedBy { it.createdAt ?: java.time.Instant.EPOCH }
        if (fresh.isNotEmpty()) {
            val merged = seen.toMutableSet()
            merged.addAll(fresh.map { it.id })
            prefs.edit().putString(KEY_SEEN, merged.joinToString(",")).commit()
        }
        return fresh
    }

    fun soundEnabled(): Boolean = prefs.getBoolean(KEY_SOUND, true)

    fun vibrationEnabled(): Boolean = prefs.getBoolean(KEY_VIBRATION, true)

    fun setSoundEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_SOUND, enabled).commit()
    }

    fun setVibrationEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_VIBRATION, enabled).commit()
    }

    fun clearSession() {
        epoch += 1
        prefs.edit()
            .remove(KEY_ACCESS)
            .remove(KEY_REFRESH)
            .remove(KEY_SEEN)
            .remove(KEY_BASELINE)
            .commit()
    }

    fun jwtExpMillis(jwt: String): Long? {
        val parts = jwt.split('.')
        if (parts.size < 2) return null
        return try {
            val payload = parts[1]
            val pad = (4 - payload.length % 4) % 4
            val normalized = payload.replace('-', '+').replace('_', '/') + "=".repeat(pad)
            val json = JSONObject(String(Base64.decode(normalized, Base64.DEFAULT), Charsets.UTF_8))
            val exp = json.optLong("exp", 0L)
            if (exp <= 0L) null else exp * 1000L
        } catch (_: Exception) {
            null
        }
    }
}
