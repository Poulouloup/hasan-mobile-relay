package com.hasan.v1

import android.util.Log
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

/**
 * Client HTTP vers l'orchestrateur. Certificat auto-signé (comme Hermes) →
 * confiance totale (TrustManager permissif), pas de validation de chaîne.
 * Le long-polling de [pollCommands] nécessite un readTimeout supérieur au
 * timeout demandé côté serveur.
 */
class OrchestratorApiClient(private val settings: SettingsManager) {

    private val trustAll = object : X509TrustManager {
        override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {}
        override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {}
        override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
    }

    private val httpClient: OkHttpClient = run {
        val sslCtx = SSLContext.getInstance("TLS").apply {
            init(null, arrayOf<TrustManager>(trustAll), SecureRandom())
        }
        OkHttpClient.Builder()
            .sslSocketFactory(sslCtx.socketFactory, trustAll)
            .hostnameVerifier { _, _ -> true }
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(65, TimeUnit.SECONDS)
            .writeTimeout(10, TimeUnit.SECONDS)
            .build()
    }

    private val rootUrl: String get() = buildRootUrl(settings.orchestratorUrl)

    private fun authHeader(builder: Request.Builder) {
        settings.orchestratorSessionToken?.let {
            builder.addHeader("Authorization", "Bearer $it")
        }
    }

    /** Convertit `{nom: enabled}` vers le format attendu par le serveur `{nom: {enabled, auth_required}}`. */
    private fun capabilitiesToJson(capabilities: Map<String, Boolean>): JSONObject =
        JSONObject().apply {
            capabilities.forEach { (name, enabled) ->
                val default = CAPABILITIES_REQUIRING_AUTH.contains(name)
                put(name, JSONObject().apply {
                    put("enabled", enabled)
                    put("auth_required", settings.isCapabilityAuthRequired(name, default))
                })
            }
        }

    // ─── Enregistrement / configuration ────────────────────────────────────

    suspend fun register(deviceName: String, capabilities: Map<String, Boolean>): RegisterResult {
        val body = JSONObject().apply {
            put("device_hash", settings.orchestratorDeviceHash)
            put("device_name", deviceName)
            put("device_type", "mobile_agent")
            put("capabilities", capabilitiesToJson(capabilities))
        }
        val request = Request.Builder()
            .url("$rootUrl/register")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()
        return try {
            httpClient.newCall(request).execute().use { response ->
                val raw = response.body?.string()
                if (!response.isSuccessful || raw == null) {
                    return RegisterResult.ServerError(response.code)
                }
                val json = JSONObject(raw)
                RegisterResult.Ok(
                    sessionToken = json.optString("session_token").takeIf { it.isNotBlank() },
                    deviceHash = settings.orchestratorDeviceHash
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "register error: ${e.message}")
            RegisterResult.NetworkError
        }
    }

    suspend fun rename(newName: String): Boolean {
        val body = JSONObject().apply {
            put("device_hash", settings.orchestratorDeviceHash)
            put("new_name", newName)
        }
        val request = Request.Builder()
            .url("$rootUrl/rename")
            .apply { authHeader(this) }
            .patch(body.toString().toRequestBody("application/json".toMediaType()))
            .build()
        return try {
            httpClient.newCall(request).execute().use { it.isSuccessful }
        } catch (e: Exception) {
            Log.e(TAG, "rename error: ${e.message}")
            false
        }
    }

    suspend fun updateCapabilities(capabilities: Map<String, Boolean>, version: String): Boolean {
        val body = JSONObject().apply {
            put("device_hash", settings.orchestratorDeviceHash)
            put("capabilities", capabilitiesToJson(capabilities))
        }
        val request = Request.Builder()
            .url("$rootUrl/capabilities")
            .apply { authHeader(this) }
            .patch(body.toString().toRequestBody("application/json".toMediaType()))
            .build()
        return try {
            httpClient.newCall(request).execute().use { it.isSuccessful }
        } catch (e: Exception) {
            Log.e(TAG, "updateCapabilities error: ${e.message}")
            false
        }
    }

    // ─── Heartbeat ──────────────────────────────────────────────────────────

    suspend fun heartbeat(): HeartbeatResult {
        val body = JSONObject().apply {
            put("device_hash", settings.orchestratorDeviceHash)
            put("capabilities_version", settings.orchestratorCapabilitiesVersion)
        }
        val request = Request.Builder()
            .url("$rootUrl/heartbeat")
            .apply { authHeader(this) }
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()
        return try {
            httpClient.newCall(request).execute().use { response ->
                val raw = response.body?.string()
                if (!response.isSuccessful || raw == null) {
                    return HeartbeatResult.ServerError(response.code)
                }
                val json = JSONObject(raw)
                HeartbeatResult.Ok(
                    capabilitiesRefreshNeeded = json.optBoolean("capabilities_refresh_needed", false)
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "heartbeat error: ${e.message}")
            HeartbeatResult.NetworkError
        }
    }

    // ─── Commandes (long-polling) ──────────────────────────────────────────

    suspend fun pollCommands(timeoutSec: Int = 55): CommandResult {
        val request = Request.Builder()
            .url("$rootUrl/commands?device_hash=${settings.orchestratorDeviceHash}&timeout=$timeoutSec")
            .apply { authHeader(this) }
            .get()
            .build()
        return try {
            httpClient.newCall(request).execute().use { response ->
                val raw = response.body?.string()
                if (!response.isSuccessful || raw == null) {
                    return CommandResult.ServerError(response.code)
                }
                if (raw.isBlank()) return CommandResult.Empty
                val array = when {
                    raw.trimStart().startsWith("[") -> org.json.JSONArray(raw)
                    else -> {
                        val obj = JSONObject(raw)
                        if (!obj.has("command_id")) return CommandResult.Empty
                        org.json.JSONArray().put(obj)
                    }
                }
                if (array.length() == 0) return CommandResult.Empty
                val json = array.getJSONObject(0)
                val commandId = json.optString("command_id").takeIf { it.isNotBlank() }
                    ?: return CommandResult.Empty
                CommandResult.Ok(
                    commandId = commandId,
                    capability = json.optString("action"),
                    params = json.optJSONObject("params") ?: JSONObject(),
                    authRequired = json.optBoolean("confirm_required", false)
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "pollCommands error: ${e.message}")
            CommandResult.NetworkError
        }
    }

    suspend fun confirm(commandId: String, approved: Boolean): Boolean {
        val body = JSONObject().apply {
            put("command_id", commandId)
            put("approved", approved)
        }
        val request = Request.Builder()
            .url("$rootUrl/confirm")
            .apply { authHeader(this) }
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()
        return try {
            httpClient.newCall(request).execute().use { response ->
                Log.d(TAG, "confirm($commandId, $approved) → HTTP ${response.code}")
                response.isSuccessful
            }
        } catch (e: Exception) {
            Log.e(TAG, "confirm error: ${e.message}")
            false
        }
    }

    suspend fun postResult(commandId: String, status: String, data: JSONObject? = null, error: String? = null): Boolean {
        val body = JSONObject().apply {
            put("command_id", commandId)
            put("status", status)
            put("data", data)
            put("error", error)
        }
        val request = Request.Builder()
            .url("$rootUrl/results")
            .apply { authHeader(this) }
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()
        return try {
            httpClient.newCall(request).execute().use { response ->
                Log.d(TAG, "postResult($commandId, $status) → HTTP ${response.code}")
                response.isSuccessful
            }
        } catch (e: Exception) {
            Log.e(TAG, "postResult error: ${e.message}")
            false
        }
    }

    companion object {
        private const val TAG = "OrchestratorApi"

        /** Capabilities nécessitant une confirmation utilisateur avant exécution. */
        private val CAPABILITIES_REQUIRING_AUTH = setOf("send_sms", "get_location")

        /** Normalise l'URL de base (scheme + host + port), comme HermesApiClient.buildRootUrl. */
        fun buildRootUrl(baseUrl: String): String {
            val trimmed = baseUrl.trimEnd('/')
            return try {
                val uri = java.net.URI(trimmed)
                val port = if (uri.port != -1) ":${uri.port}" else ""
                "${uri.scheme}://${uri.host}$port"
            } catch (_: Exception) {
                trimmed
            }
        }
    }
}

// ─── Résultats ──────────────────────────────────────────────────────────────

sealed class RegisterResult {
    data class Ok(val sessionToken: String?, val deviceHash: String) : RegisterResult()
    data class ServerError(val code: Int) : RegisterResult()
    object NetworkError : RegisterResult()
}

sealed class HeartbeatResult {
    data class Ok(val capabilitiesRefreshNeeded: Boolean) : HeartbeatResult()
    data class ServerError(val code: Int) : HeartbeatResult()
    object NetworkError : HeartbeatResult()
}

sealed class CommandResult {
    data class Ok(
        val commandId: String,
        val capability: String,
        val params: JSONObject,
        val authRequired: Boolean
    ) : CommandResult()
    object Empty : CommandResult()
    data class ServerError(val code: Int) : CommandResult()
    object NetworkError : CommandResult()
}
