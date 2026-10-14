package com.smg.pos

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.OffsetDateTime
import java.time.format.DateTimeParseException
import java.util.concurrent.TimeUnit

object PosClient {
    private const val BASE = "https://pos-api.smg-international.com"
    private val jsonType = "application/json; charset=utf-8".toMediaType()
    private val http = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    private val refreshLock = Any()

    sealed class LoginResult {
        data class Ok(val access: String, val refresh: String) : LoginResult()
        data object BadCredentials : LoginResult()
        data object Network : LoginResult()
    }

    sealed class CallsResult {
        data class Ok(val calls: List<WaiterCall>) : CallsResult()
        data object Forbidden : CallsResult()
        data object SessionExpired : CallsResult()
        data object Network : CallsResult()
    }

    fun login(email: String, password: String): LoginResult {
        val body = JSONObject()
            .put("email", email)
            .put("password", password)
            .toString()
        val request = Request.Builder()
            .url("$BASE/api/auth/login")
            .post(body.toRequestBody(jsonType))
            .build()
        return try {
            http.newCall(request).execute().use { response ->
                when (response.code) {
                    200 -> {
                        val json = JSONObject(response.body?.string().orEmpty())
                        val access = json.optString("access_token")
                        val refresh = json.optString("refresh_token")
                        if (access.isBlank() || refresh.isBlank()) LoginResult.Network
                        else LoginResult.Ok(access, refresh)
                    }
                    401, 422 -> LoginResult.BadCredentials
                    else -> LoginResult.Network
                }
            }
        } catch (_: Exception) {
            LoginResult.Network
        }
    }

    fun fetchCalls(): CallsResult {
        if (!SessionStore.hasUsableSession()) return CallsResult.SessionExpired
        if (SessionStore.accessNeedsRefresh()) {
            when (refresh(force = false)) {
                Refresh.Expired -> return CallsResult.SessionExpired
                Refresh.Network -> return CallsResult.Network
                Refresh.Ok -> Unit
            }
        }
        return when (val first = getCalls()) {
            CallsResult.SessionExpired -> when (refresh(force = true)) {
                Refresh.Expired -> CallsResult.SessionExpired
                Refresh.Network -> CallsResult.Network
                Refresh.Ok -> getCalls()
            }
            else -> first
        }
    }

    private fun getCalls(): CallsResult {
        val access = SessionStore.accessToken() ?: return CallsResult.SessionExpired
        val request = Request.Builder()
            .url("$BASE/api/admin/waiter-calls")
            .header("Authorization", "Bearer $access")
            .get()
            .build()
        return try {
            http.newCall(request).execute().use { response ->
                when (response.code) {
                    200 -> CallsResult.Ok(parseCalls(response.body?.string().orEmpty()))
                    401 -> CallsResult.SessionExpired
                    403 -> CallsResult.Forbidden
                    else -> CallsResult.Network
                }
            }
        } catch (_: Exception) {
            CallsResult.Network
        }
    }

    private enum class Refresh { Ok, Expired, Network }

    private fun refresh(force: Boolean): Refresh {
        synchronized(refreshLock) {
            if (!force && !SessionStore.accessNeedsRefresh() && SessionStore.accessToken() != null) {
                return Refresh.Ok
            }
            val refresh = SessionStore.refreshToken() ?: return Refresh.Expired
            if (!SessionStore.hasUsableSession()) return Refresh.Expired
            val body = JSONObject().put("refresh_token", refresh).toString()
            val request = Request.Builder()
                .url("$BASE/api/auth/refresh")
                .post(body.toRequestBody(jsonType))
                .build()
            return try {
                http.newCall(request).execute().use { response ->
                    when (response.code) {
                        200 -> {
                            val json = JSONObject(response.body?.string().orEmpty())
                            val access = json.optString("access_token")
                            val nextRefresh = json.optString("refresh_token")
                            if (access.isBlank() || nextRefresh.isBlank()) Refresh.Network
                            else {
                                SessionStore.saveTokens(access, nextRefresh)
                                Refresh.Ok
                            }
                        }
                        401 -> Refresh.Expired
                        else -> Refresh.Network
                    }
                }
            } catch (_: Exception) {
                Refresh.Network
            }
        }
    }

    fun parseCalls(body: String): List<WaiterCall> {
        val array = JSONArray(body)
        val calls = ArrayList<WaiterCall>(array.length())
        for (i in 0 until array.length()) {
            val item = array.getJSONObject(i)
            val device = item.optJSONObject("device")
            calls.add(
                WaiterCall(
                    id = item.getInt("id"),
                    tableNumber = device?.optString("table_number").orEmpty(),
                    reason = item.optString("reason"),
                    status = item.optString("status"),
                    createdAt = parseInstant(item.optString("created_at")),
                ),
            )
        }
        return calls.sortedByDescending { it.createdAt ?: Instant.EPOCH }
    }

    private fun parseInstant(raw: String): Instant? {
        if (raw.isBlank()) return null
        return try {
            OffsetDateTime.parse(raw).toInstant()
        } catch (_: DateTimeParseException) {
            try {
                Instant.parse(raw)
            } catch (_: DateTimeParseException) {
                null
            }
        }
    }
}
