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
    private const val MANAGE_STATUS = "waiter_calls.manage_status"
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

    sealed class ManageResult {
        data class Known(val allowed: Boolean) : ManageResult()
        data object SessionExpired : ManageResult()
        data object Network : ManageResult()
    }

    sealed class StatusResult {
        data class Ok(val status: String) : StatusResult()
        data object Failed : StatusResult()
        data object SessionExpired : StatusResult()
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

    fun fetchCalls(): CallsResult = authorized(
        expired = CallsResult.SessionExpired,
        network = CallsResult.Network,
        call = { getCalls() },
        isExpired = { it is CallsResult.SessionExpired },
    )

    fun fetchManagePermission(): ManageResult = authorized(
        expired = ManageResult.SessionExpired,
        network = ManageResult.Network,
        call = { getManagePermission() },
        isExpired = { it is ManageResult.SessionExpired },
    )

    fun updateStatus(id: Int, status: String): StatusResult = authorized(
        expired = StatusResult.SessionExpired,
        network = StatusResult.Failed,
        call = { putStatus(id, status) },
        isExpired = { it is StatusResult.SessionExpired },
    )

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

    private fun getManagePermission(): ManageResult {
        val access = SessionStore.accessToken() ?: return ManageResult.SessionExpired
        val request = Request.Builder()
            .url("$BASE/api/auth/me")
            .header("Authorization", "Bearer $access")
            .get()
            .build()
        return try {
            http.newCall(request).execute().use { response ->
                when (response.code) {
                    200 -> ManageResult.Known(canManageStatus(response.body?.string().orEmpty()))
                    401 -> ManageResult.SessionExpired
                    else -> ManageResult.Network
                }
            }
        } catch (_: Exception) {
            ManageResult.Network
        }
    }

    private fun putStatus(id: Int, status: String): StatusResult {
        val access = SessionStore.accessToken() ?: return StatusResult.SessionExpired
        val body = JSONObject().put("status", status).toString()
        val request = Request.Builder()
            .url("$BASE/api/admin/waiter-calls/$id/status")
            .header("Authorization", "Bearer $access")
            .put(body.toRequestBody(jsonType))
            .build()
        return try {
            http.newCall(request).execute().use { response ->
                when (response.code) {
                    200 -> {
                        val confirmed = runCatching {
                            JSONObject(response.body?.string().orEmpty()).optString("status")
                        }.getOrNull().orEmpty().ifBlank { status }
                        StatusResult.Ok(confirmed)
                    }
                    401 -> StatusResult.SessionExpired
                    else -> StatusResult.Failed
                }
            }
        } catch (_: Exception) {
            StatusResult.Failed
        }
    }

    private fun <T> authorized(
        expired: T,
        network: T,
        call: () -> T,
        isExpired: (T) -> Boolean,
    ): T {
        if (!SessionStore.hasUsableSession()) return expired
        if (SessionStore.accessNeedsRefresh()) {
            when (refresh(force = false)) {
                Refresh.Expired -> return expired
                Refresh.Network -> return network
                Refresh.Ok -> Unit
            }
        }
        val first = call()
        if (!isExpired(first)) return first
        return when (refresh(force = true)) {
            Refresh.Expired -> expired
            Refresh.Network -> network
            Refresh.Ok -> call()
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
            calls.add(parseCall(array.getJSONObject(i)))
        }
        return calls.sortedByDescending { it.createdAt ?: Instant.EPOCH }
    }

    private fun parseCall(item: JSONObject): WaiterCall {
        val device = item.optJSONObject("device")
        return WaiterCall(
            id = item.getInt("id"),
            tableNumber = device?.optString("table_number").orEmpty(),
            reason = item.optString("reason"),
            status = item.optString("status"),
            createdAt = parseInstant(item.optString("created_at")),
        )
    }

    private fun canManageStatus(body: String): Boolean {
        val permissions = JSONObject(body).optJSONObject("permissions") ?: return false
        val global = permissions.optJSONArray("global") ?: permissions.optJSONArray("global_")
        if (permissions.optBoolean("all_restaurants") && contains(global, MANAGE_STATUS)) return true
        val restaurants = permissions.optJSONObject("restaurants") ?: return false
        val keys = restaurants.keys()
        while (keys.hasNext()) {
            if (contains(restaurants.optJSONArray(keys.next()), MANAGE_STATUS)) return true
        }
        return false
    }

    private fun contains(array: JSONArray?, value: String): Boolean {
        if (array == null) return false
        for (i in 0 until array.length()) {
            if (array.optString(i) == value) return true
        }
        return false
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
