package com.evdev.androidcontrol.signaling

import android.accounts.Account
import android.accounts.AccountManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import com.evdev.androidcontrol.model.SessionPayload
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

data class HttpResponse(
    val statusCode: Int,
    val body: String?,
    val isSuccess: Boolean = statusCode in 200..299
)

data class FirebaseAuthToken(
    val idToken: String,
    val refreshToken: String,
    val expiresInSeconds: Long,
    val expiresAtMs: Long,
    val email: String,
    val emailVerified: Boolean,
    val uid: String = ""
)

class FirebaseSignalingClient(private val context: Context) {

    companion object {
        const val PREFS_AUTH = "auth"
        const val PREFS_CONFIG = "firebase_config"
    }

    val databaseUrl: String
        get() = context.getSharedPreferences(PREFS_CONFIG, Context.MODE_PRIVATE)
            .getString("database_url", com.evdev.androidcontrol.BuildConfig.FIREBASE_DB_URL)
            ?: com.evdev.androidcontrol.BuildConfig.FIREBASE_DB_URL

    val apiKey: String
        get() = context.getSharedPreferences(PREFS_CONFIG, Context.MODE_PRIVATE)
            .getString("api_key", com.evdev.androidcontrol.BuildConfig.FIREBASE_API_KEY)
            ?: com.evdev.androidcontrol.BuildConfig.FIREBASE_API_KEY

    val webClientId: String
        get() = context.getSharedPreferences(PREFS_CONFIG, Context.MODE_PRIVATE)
            .getString("web_client_id", com.evdev.androidcontrol.BuildConfig.FIREBASE_WEB_CLIENT_ID)
            ?: com.evdev.androidcontrol.BuildConfig.FIREBASE_WEB_CLIENT_ID

    val idTokenScope: String
        get() = "audience:server:client_id:$webClientId"

    val currentUid: String?
        get() = context.getSharedPreferences(PREFS_AUTH, Context.MODE_PRIVATE)
            .getString("user_uid", null)

    private val tokenMutex = Mutex()

    sealed class AuthResult {
        data class Success(val email: String, val firebaseToken: String) : AuthResult()
        data class ConsentRequired(val intent: Intent, val email: String) : AuthResult()
        data class Error(val message: String) : AuthResult()
    }

    val deviceId: String
        get() = Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
            ?: "pixel-11-pro"

    val deviceName: String
        get() = "${Build.MANUFACTURER} ${Build.MODEL}".trim().ifEmpty { "Pixel 11 Pro" }

    suspend fun authenticateAccount(email: String): AuthResult = withContext(Dispatchers.IO) {
        try {
            val am = AccountManager.get(context)
            val account = Account(email, "com.google")

            // 1. Request Google ID token scoped to our Firebase Web Client ID
            var fetchResult = fetchGoogleIdToken(am, account)
            if (fetchResult.consentIntent != null) {
                return@withContext AuthResult.ConsentRequired(fetchResult.consentIntent, email)
            }
            var googleIdToken = fetchResult.token
            if (googleIdToken.isNullOrBlank()) {
                val detail = fetchResult.error ?: "token is empty"
                return@withContext AuthResult.Error("Failed to obtain Google ID token: $detail")
            }

            // 2. Exchange Google ID token for Firebase Auth credential
            var exchangeResult = exchangeForFirebaseToken(googleIdToken)

            // S3: If exchange fails, invalidate cached Google token and retry once
            if (exchangeResult == null) {
                am.invalidateAuthToken("com.google", googleIdToken)
                val retry = fetchGoogleIdToken(am, account)
                if (retry.consentIntent != null) {
                    return@withContext AuthResult.ConsentRequired(retry.consentIntent, email)
                }
                val retriedIdToken = retry.token
                if (!retriedIdToken.isNullOrBlank()) {
                    googleIdToken = retriedIdToken
                    exchangeResult = exchangeForFirebaseToken(googleIdToken)
                }
            }

            // S2: No invalid token fallback
            if (exchangeResult == null) {
                return@withContext AuthResult.Error("Firebase token exchange failed for $email")
            }

            // S4: Verify directly via signInWithIdp response (email + emailVerified)
            if (!exchangeResult.emailVerified) {
                return@withContext AuthResult.Error("Google account email $email is not verified")
            }
            if (!exchangeResult.email.equals(email, ignoreCase = true)) {
                return@withContext AuthResult.Error("Account mismatch: expected $email, got ${exchangeResult.email}")
            }

            // Store credentials securely in auth SharedPreferences
            saveAuthSession(exchangeResult, googleIdToken)

            AuthResult.Success(exchangeResult.email, exchangeResult.idToken)
        } catch (e: Exception) {
            AuthResult.Error(e.localizedMessage ?: "Authentication failed")
        }
    }

    data class IdTokenResult(
        val token: String? = null,
        val consentIntent: Intent? = null,
        val error: String? = null
    )

    private fun fetchGoogleIdToken(am: AccountManager, account: Account): IdTokenResult {
        return try {
            val future = am.getAuthToken(account, idTokenScope, Bundle(), false, null, null)
            val bundle = future.result
            val consentIntent = extractConsentIntent(bundle)
            if (consentIntent != null) {
                IdTokenResult(consentIntent = consentIntent)
            } else {
                val token = bundle.getString(AccountManager.KEY_AUTHTOKEN)
                if (token.isNullOrBlank()) {
                    val errMsg = bundle.getString(AccountManager.KEY_ERROR_MESSAGE)
                        ?: bundle.getString("errorMessage")
                        ?: "Empty token. Keys: ${bundle.keySet()}"
                    android.util.Log.e("AndroidControl", "fetchGoogleIdToken bundle error: $errMsg")
                    IdTokenResult(error = errMsg)
                } else {
                    IdTokenResult(token = token)
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("AndroidControl", "fetchGoogleIdToken exception for scope $idTokenScope", e)
            IdTokenResult(error = "${e.javaClass.simpleName}: ${e.message}")
        }
    }

    private fun extractConsentIntent(bundle: Bundle): Intent? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            bundle.getParcelable(AccountManager.KEY_INTENT, Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            bundle.getParcelable(AccountManager.KEY_INTENT)
        }
    }

    fun exchangeForFirebaseToken(idToken: String): FirebaseAuthToken? {
        return runCatching {
            val postBody = "id_token=$idToken&providerId=google.com"
            val payload = JSONObject().apply {
                put("postBody", postBody)
                put("requestUri", "http://localhost")
                put("returnIdpCredential", true)
                put("returnSecureToken", true)
            }.toString()

            val url = URL("https://identitytoolkit.googleapis.com/v1/accounts:signInWithIdp?key=$apiKey")
            val conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
                connectTimeout = 5000
                readTimeout = 5000
                doOutput = true
            }
            OutputStreamWriter(conn.outputStream, Charsets.UTF_8).use { it.write(payload) }
            if (conn.responseCode !in 200..299) return@runCatching null
            val resp = conn.inputStream.bufferedReader().use { it.readText() }
            val obj = JSONObject(resp)
            val fbToken = obj.optString("idToken")
            val refreshToken = obj.optString("refreshToken")
            val expiresIn = obj.optString("expiresIn", "3600").toLongOrNull() ?: 3600L
            val email = obj.optString("email")
            val emailVerified = obj.optBoolean("emailVerified", false)
            val uid = obj.optString("localId")

            if (fbToken.isBlank() || refreshToken.isBlank()) return@runCatching null

            FirebaseAuthToken(
                idToken = fbToken,
                refreshToken = refreshToken,
                expiresInSeconds = expiresIn,
                expiresAtMs = System.currentTimeMillis() + (expiresIn * 1000L),
                email = email,
                emailVerified = emailVerified,
                uid = uid
            )
        }.getOrNull()
    }

    fun refreshFirebaseToken(refreshToken: String): FirebaseAuthToken? {
        return runCatching {
            val postData = "grant_type=refresh_token&refresh_token=" + URLEncoder.encode(refreshToken, "UTF-8")
            val url = URL("https://securetoken.googleapis.com/v1/token?key=$apiKey")
            val conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=utf-8")
                connectTimeout = 5000
                readTimeout = 5000
                doOutput = true
            }
            OutputStreamWriter(conn.outputStream, Charsets.UTF_8).use { it.write(postData) }
            if (conn.responseCode !in 200..299) return@runCatching null
            val resp = conn.inputStream.bufferedReader().use { it.readText() }
            val obj = JSONObject(resp)
            val idToken = obj.optString("id_token")
            val newRefreshToken = obj.optString("refresh_token", refreshToken)
            val expiresIn = obj.optString("expires_in", "3600").toLongOrNull() ?: 3600L

            if (idToken.isBlank()) return@runCatching null

            val prefs = context.getSharedPreferences(PREFS_AUTH, Context.MODE_PRIVATE)
            val email = prefs.getString("user_email", "") ?: ""
            val uid = obj.optString("user_id").ifEmpty {
                prefs.getString("user_uid", "") ?: ""
            }

            val tokenObj = FirebaseAuthToken(
                idToken = idToken,
                refreshToken = newRefreshToken,
                expiresInSeconds = expiresIn,
                expiresAtMs = System.currentTimeMillis() + (expiresIn * 1000L),
                email = email,
                emailVerified = true,
                uid = uid
            )
            saveAuthSession(tokenObj, prefs.getString("cached_google_token", null))
            tokenObj
        }.getOrNull()
    }

    suspend fun getValidToken(): String? = withContext(Dispatchers.IO) {
        tokenMutex.withLock {
            val prefs = context.getSharedPreferences(PREFS_AUTH, Context.MODE_PRIVATE)
            val idToken = prefs.getString("firebase_token", null)
            val refreshToken = prefs.getString("refresh_token", null)
            val expiresAtMs = prefs.getLong("expires_at_ms", 0L)
            val now = System.currentTimeMillis()

            if (!idToken.isNullOrBlank() && !refreshToken.isNullOrBlank()) {
                // S1: Refresh when < 5 minutes left
                if (expiresAtMs - now > 300_000L) {
                    return@withLock idToken
                }
                val refreshed = refreshFirebaseToken(refreshToken)
                if (refreshed != null) {
                    return@withLock refreshed.idToken
                }
            }
            null
        }
    }

    private fun saveAuthSession(token: FirebaseAuthToken, googleToken: String?) {
        val prefs = context.getSharedPreferences(PREFS_AUTH, Context.MODE_PRIVATE)
        prefs.edit()
            .putString("firebase_token", token.idToken)
            .putString("refresh_token", token.refreshToken)
            .putLong("expires_at_ms", token.expiresAtMs)
            .putString("user_email", token.email)
            .apply {
                if (token.uid.isNotBlank()) {
                    putString("user_uid", token.uid)
                }
                if (!googleToken.isNullOrBlank()) {
                    putString("cached_google_token", googleToken)
                }
            }
            .apply()
    }

    fun clearAuth() {
        val prefs = context.getSharedPreferences(PREFS_AUTH, Context.MODE_PRIVATE)
        val googleToken = prefs.getString("cached_google_token", null)
        if (!googleToken.isNullOrBlank()) {
            try {
                AccountManager.get(context).invalidateAuthToken("com.google", googleToken)
            } catch (_: Exception) {}
        }
        prefs.edit().clear().apply()
    }

    suspend fun httpRequest(
        method: String,
        path: String,
        body: String? = null,
        explicitToken: String? = null,
        allowRetryAuth: Boolean = true
    ): HttpResponse = withContext(Dispatchers.IO) {
        val token = explicitToken ?: getValidToken()
        if (token.isNullOrBlank()) {
            return@withContext HttpResponse(401, null)
        }

        val url = URL("$databaseUrl$path?auth=$token")
        var conn: HttpURLConnection? = null
        try {
            conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = method
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
                connectTimeout = 5000
                readTimeout = 5000
                if (body != null) {
                    doOutput = true
                }
            }
            if (body != null) {
                OutputStreamWriter(conn.outputStream, Charsets.UTF_8).use { it.write(body) }
            }
            val code = conn.responseCode
            if (code == 401 && allowRetryAuth) {
                val prefs = context.getSharedPreferences(PREFS_AUTH, Context.MODE_PRIVATE)
                val refreshToken = prefs.getString("refresh_token", null)
                if (!refreshToken.isNullOrBlank()) {
                    val refreshed = refreshFirebaseToken(refreshToken)
                    if (refreshed != null) {
                        return@withContext httpRequest(
                            method = method,
                            path = path,
                            body = body,
                            explicitToken = refreshed.idToken,
                            allowRetryAuth = false
                        )
                    }
                }
            }
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val respBody = stream?.bufferedReader()?.use { it.readText() }
            HttpResponse(code, respBody)
        } catch (e: Exception) {
            HttpResponse(-1, e.message)
        } finally {
            conn?.disconnect()
        }
    }

    suspend fun registerDevice(email: String, mode: String, explicitToken: String? = null): Boolean =
        withContext(Dispatchers.IO) {
            runCatching {
                val uid = currentUid
                val payload = JSONObject().apply {
                    put("device_id", deviceId)
                    put("device_name", deviceName)
                    put("account", email)
                    put("mode", mode)
                    put("updated_at", System.currentTimeMillis())
                    if (!uid.isNullOrBlank()) {
                        put("uid", uid)
                    }
                }.toString()

                var success = false
                // 1. User-partitioned path (primary)
                if (!uid.isNullOrBlank()) {
                    val resUser = httpRequest(
                        method = "PUT",
                        path = "/users/$uid/devices/$deviceId.json",
                        body = payload,
                        explicitToken = explicitToken
                    )
                    success = resUser.isSuccess
                }

                // 2. Global path (backward-compatibility fallback)
                val resGlobal = httpRequest(
                    method = "PUT",
                    path = "/android_control/devices/$deviceId.json",
                    body = payload,
                    explicitToken = explicitToken
                )
                success || resGlobal.isSuccess
            }.getOrDefault(false)
        }

    suspend fun unregisterDevice(explicitToken: String? = null) = withContext(Dispatchers.IO) {
        runCatching {
            val uid = currentUid
            if (!uid.isNullOrBlank()) {
                httpRequest(
                    method = "DELETE",
                    path = "/users/$uid/devices/$deviceId.json",
                    explicitToken = explicitToken
                )
            }
            httpRequest(
                method = "DELETE",
                path = "/android_control/devices/$deviceId.json",
                explicitToken = explicitToken
            )
        }
    }

    suspend fun pollPendingOffer(explicitToken: String? = null): Result<SessionPayload?> = withContext(Dispatchers.IO) {
        runCatching {
            val uid = currentUid
            var inboxPath = if (!uid.isNullOrBlank()) {
                "/users/$uid/devices/$deviceId/inbox.json"
            } else {
                "/android_control/devices/$deviceId/inbox.json"
            }

            var resp = httpRequest(
                method = "GET",
                path = inboxPath,
                explicitToken = explicitToken
            )

            // Fallback to legacy path if not found in user inbox or user inbox access denied
            if ((!resp.isSuccess || resp.body.isNullOrBlank() || resp.body == "null") && !uid.isNullOrBlank()) {
                val fallbackResp = httpRequest(
                    method = "GET",
                    path = "/android_control/devices/$deviceId/inbox.json",
                    explicitToken = explicitToken
                )
                if (fallbackResp.isSuccess && !fallbackResp.body.isNullOrBlank() && fallbackResp.body != "null") {
                    resp = fallbackResp
                    inboxPath = "/android_control/devices/$deviceId/inbox.json"
                }
            }

            if (resp.statusCode == 401 || resp.statusCode == 403) {
                throw IllegalStateException("Authentication failed (${resp.statusCode})")
            }
            if (!resp.isSuccess || resp.body.isNullOrBlank() || resp.body == "null") {
                return@runCatching null
            }

            val obj = JSONObject(resp.body)
            val ts = obj.optLong("timestamp_ms", 0L)
            if (ts > 0 && System.currentTimeMillis() - ts > 300_000L) {
                httpRequest("DELETE", inboxPath, explicitToken = explicitToken)
                return@runCatching null
            }

            val offer = SessionPayload.parse(resp.body).getOrNull()
            if (offer != null && offer.sdp.isNotBlank()) {
                httpRequest("DELETE", inboxPath, explicitToken = explicitToken)
                offer
            } else {
                null
            }
        }
    }

    suspend fun publishAnswer(answer: SessionPayload, explicitToken: String? = null): Boolean =
        withContext(Dispatchers.IO) {
            runCatching {
                val uid = currentUid
                var ok = false
                if (!uid.isNullOrBlank()) {
                    val resUser = httpRequest(
                        method = "PUT",
                        path = "/users/$uid/sessions/${answer.sessionId}/answer.json",
                        body = answer.toJson(),
                        explicitToken = explicitToken
                    )
                    ok = resUser.isSuccess
                }
                val resGlobal = httpRequest(
                    method = "PUT",
                    path = "/android_control/sessions/${answer.sessionId}/answer.json",
                    body = answer.toJson(),
                    explicitToken = explicitToken
                )
                ok || resGlobal.isSuccess
            }.getOrDefault(false)
        }
}
