package com.hasan.v1

import android.util.Log
import com.hasan.v1.auth.CertPinStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit
import org.json.JSONArray
import org.json.JSONObject
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager

/** Alias vers [CertPinStore.CertCheckResult] — conserve pour la compatibilite du code appelant de [HermesApiClient]. */
typealias CertCheckResult = CertPinStore.CertCheckResult

/**
 * Client HTTPS pour l'API Hermes (format compatible OpenAI).
 *
 * Securite TLS : systeme Trust On First Use (TOFU) — voir [CertPinStore]
 * pour l'implementation partagee avec les autres clients reseau de l'app.
 *   - Premiere connexion a un nouveau serveur : fingerprint SHA-256 affiche a l'utilisateur.
 *   - Si l'utilisateur accepte : fingerprint stocke dans EncryptedSharedPreferences.
 *   - Connexions suivantes : comparaison silencieuse du fingerprint.
 *   - Changement de certificat : alerte bloquante.
 *   - HTTP simple : connexion autorisee (pas de TLS a verifier).
 *   - Let's Encrypt / CA valide : accepte sans intervention utilisateur.
 */
class HermesApiClient(
    private val config: HermesConfig,
    private val settings: SettingsManager
) {

    // ─── TrustManager TOFU ────────────────────────────────────────────────────

    private val certPinStore = CertPinStore(settings)

    // Namespace vide : reproduit le format de cle historique de ce client
    // ("trusted_cert_<md5>"), pour ne pas invalider les fingerprints deja
    // approuves par les utilisateurs existants.
    private val tofuTrustManager = certPinStore.newTrustManager(certStorageKey(config.baseUrl))

    private val sslContext = SSLContext.getInstance("TLS").apply {
        init(null, arrayOf<TrustManager>(tofuTrustManager), java.security.SecureRandom())
    }

    // Le hostname n'est pas verifie par Java — la confiance est geree par TOFU
    private val httpClient = OkHttpClient.Builder()
        .sslSocketFactory(sslContext.socketFactory, tofuTrustManager)
        .hostnameVerifier { _, _ -> true }
        .build()

    // Client dedie au streaming SSE : pas de read timeout (les reponses avec tools
    // peuvent durer plusieurs minutes) et pas de compression (evite les erreurs de
    // decodage chunk SSE derriere certains proxys)
    private val streamingClient = httpClient.newBuilder()
        .readTimeout(0, TimeUnit.SECONDS)
        .build()

    // ─── Streaming SSE ────────────────────────────────────────────────────────

    /**
     * Envoie un message via POST /v1/responses avec SSE.
     * Inclut "conversation" pour le chainage automatique cote Hermes,
     * et "previous_response_id" pour la continuite explicite.
     */
    fun streamChat(sessionId: String, userText: String): Flow<StreamEvent> = flow {
        val previousResponseId = settings.getLastResponseId(sessionId)
        Log.d("HermesDebug", "=== streamChat START session=$sessionId prevRespId=$previousResponseId input=${userText.take(60)}")
        val body = JSONObject().apply {
            put("input", userText)
            put("stream", true)
            put("conversation", sessionId)
            // previous_response_id non supporté par ce Hermes — la continuité passe par "conversation"
        }.toString()
        Log.d("HermesDebug", "=== streamChat BODY=$body")
        val request = Request.Builder()
            .url("${buildRootUrl(config.baseUrl)}/v1/responses")
            .addHeader("Authorization", "Bearer ${config.authToken}")
            .addHeader("Content-Type", "application/json")
            .addHeader("Accept-Encoding", "identity")
            .post(body.toRequestBody("application/json".toMediaType()))
            .build()

        emit(StreamEvent.Connecting)

        val response = try {
            streamingClient.newCall(request).execute()
        } catch (e: java.net.SocketTimeoutException) {
            emit(StreamEvent.Error("Hermes ne répond pas (timeout)", ErrorType.TIMEOUT))
            return@flow
        } catch (e: java.net.ConnectException) {
            emit(StreamEvent.Error("Hermes inaccessible — vérifiez l'URL", ErrorType.HERMES_UNREACHABLE))
            return@flow
        } catch (e: java.net.UnknownHostException) {
            emit(StreamEvent.Error("Hermes inaccessible — DNS introuvable", ErrorType.HERMES_UNREACHABLE))
            return@flow
        } catch (e: Exception) {
            emit(StreamEvent.Error("Connexion impossible : ${e.message}", ErrorType.HERMES_UNREACHABLE))
            return@flow
        }

        val certEvent = buildCertEvent(tofuTrustManager.lastCheckResult)
        if (certEvent != null) {
            emit(certEvent)
            response.close()
            return@flow
        }

        if (!response.isSuccessful) {
            val body = try { response.body?.string() ?: "" } catch (_: Exception) { "" }
            response.close()
            val errorType = when {
                response.code == 400 && (body.contains("tool_calls", ignoreCase = true) || body.contains("invalid", ignoreCase = true)) -> ErrorType.INVALID_CONTEXT
                response.code in listOf(401, 403) -> ErrorType.AUTH_FAILED
                response.code in 500..599 -> ErrorType.SERVER_ERROR
                else -> ErrorType.SERVER_ERROR
            }
            val errorMsg = when (errorType) {
                ErrorType.INVALID_CONTEXT -> "Contexte invalide (${response.code})"
                ErrorType.AUTH_FAILED -> "Token invalide — vérifiez les paramètres"
                else -> "Erreur serveur Hermes (${response.code})"
            }
            Log.w("HermesSSE", "HTTP ${response.code} — $errorType — body: ${body.take(200)}")
            emit(StreamEvent.Error(errorMsg, errorType))
            return@flow
        }

        emit(StreamEvent.Connected)

        val source = response.body?.source() ?: run {
            emit(StreamEvent.Error("Reponse vide"))
            return@flow
        }

        // Lecture chunk-based (alignee sur le desktop Rust) : on lit les bytes
        // disponibles par blocs et on extrait les lignes completes d'un buffer.
        // Evite les blocages de readUtf8Line() quand le serveur envoie des chunks
        // incomplets (compression, proxy, tool calls longues).
        try {
            var pendingEvent: String? = null
            val sseBuffer = StringBuilder()
            var streamDone = false
            while (!streamDone) {
                if (!source.request(1)) break
                val available = source.buffer.size
                if (available == 0L) continue
                sseBuffer.append(source.buffer.readUtf8(available))

                while (true) {
                    val nlPos = sseBuffer.indexOf('\n')
                    if (nlPos < 0) break
                    val line = sseBuffer.substring(0, nlPos).trimEnd('\r')
                    sseBuffer.delete(0, nlPos + 1)

                    Log.d(TAG, "SSE: $line")
                    when {
                        line.startsWith("event: ") -> {
                            pendingEvent = line.removePrefix("event: ").trim()
                        }
                        pendingEvent == "response.completed" && line.startsWith("data: ") -> {
                    Log.d("HermesDebug", "=== response.completed raw=${line.take(200)}")
                            val (responseId, inputTok, outputTok) = try {
                                val obj = JSONObject(line.removePrefix("data: ").trim())
                                val respObj = obj.optJSONObject("response") ?: obj
                                val id = respObj.optString("id").takeIf { it.isNotEmpty() }
                                val usage = respObj.optJSONObject("usage")
                                Triple(id, usage?.optInt("input_tokens") ?: 0, usage?.optInt("output_tokens") ?: 0)
                            } catch (_: Exception) { Triple(null, 0, 0) }
                            Log.d(TAG, "response.completed — id=$responseId in=$inputTok out=$outputTok")
                            emit(StreamEvent.Done(responseId, inputTok, outputTok))
                        }
                        pendingEvent == "response.output_item.added" && line.startsWith("data: ") -> {
                            val toolName = try {
                                val obj = JSONObject(line.removePrefix("data: ").trim())
                                val item = obj.optJSONObject("item")
                                if (item?.optString("type") == "function_call") item.optString("name") else null
                            } catch (_: Exception) { null }
                            if (!toolName.isNullOrBlank()) {
                                emit(StreamEvent.Thinking(toolDisplayMessage(toolName)))
                                Log.d(TAG, "tool: $toolName")
                            }
                            pendingEvent = null
                        }
                        pendingEvent == "clarify.prompt" && line.startsWith("data: ") -> {
                            try {
                                val obj = JSONObject(line.removePrefix("data: ").trim())
                                val clarifyId = obj.optString("clarify_id")
                                val question  = obj.optString("question")
                                val choicesArr = obj.optJSONArray("choices")
                                val choices = if (choicesArr != null) {
                                    (0 until choicesArr.length()).map { choicesArr.getString(it) }
                                } else null
                                Log.d(TAG, "clarify: id=$clarifyId question=$question choices=$choices")
                                emit(StreamEvent.Clarify(clarifyId, question, choices))
                            } catch (_: Exception) {}
                            pendingEvent = null
                        }
                        pendingEvent == "ping" || pendingEvent == "clarify.heartbeat" -> {
                            // Heartbeat serveur — maintient la connexion SSE alive, rien à faire
                            pendingEvent = null
                        }
                        pendingEvent == "response.output_text.delta" && line.startsWith("data: ") -> {
                            val token = try {
                                JSONObject(line.removePrefix("data: ").trim()).optString("delta").takeIf { it.isNotEmpty() }
                            } catch (_: Exception) { null }
                            if (token != null) {
                                if (token.contains("400") || token.contains("tool_calls", ignoreCase = true) || token.contains("empty array", ignoreCase = true)) {
                                    Log.w("HermesDebug", "=== TOKEN SUSPECT: $token")
                                }
                                emit(StreamEvent.Token(token))
                            }
                            pendingEvent = null
                        }
                        line == "data: [DONE]" -> {
                            emit(StreamEvent.Done())
                            pendingEvent = null
                            streamDone = true
                            break
                        }
                        line.startsWith("data: ") && pendingEvent == null -> {
                            val json = line.removePrefix("data: ")
                            try {
                                val obj = JSONObject(json)
                                val delta = obj.optString("delta").takeIf { it.isNotEmpty() }
                                    ?: obj.optJSONArray("choices")?.getJSONObject(0)
                                        ?.getJSONObject("delta")?.optString("content")
                                if (delta != null) emit(StreamEvent.Token(delta))
                            } catch (_: Exception) {}
                        }
                        line.startsWith("data: ") && pendingEvent != null -> {
                            // Event SSE non géré — logué pour diagnostic
                            Log.d("HermesSSE", "event non géré: $pendingEvent | data: " + line.removePrefix("data: "))
                            pendingEvent = null
                        }
                        line.isBlank() -> pendingEvent = null
                    }
                }
            }
        } catch (e: Exception) {
            emit(StreamEvent.Error("Connexion interrompue", ErrorType.STREAM_INTERRUPTED))
        } finally {
            response.close()
        }
    }.flowOn(Dispatchers.IO)

    // ─── Health check ─────────────────────────────────────────────────────────

    /**
     * Verifie que le serveur repond sur GET /health.
     * Retourne un [HealthResult] detaille pour permettre a l'appelant
     * de gerer les cas TOFU (nouveau certificat, changement de certificat).
     */
    suspend fun checkHealth(): HealthResult {
        val healthUrl = buildHealthUrl(config.baseUrl)
        Log.d(TAG, "Health check -> $healthUrl")
        return try {
            val request = Request.Builder().url(healthUrl).get().build()
            val response = httpClient.newCall(request).execute()
            val code = response.code
            val isSuccessful = response.isSuccessful
            response.close()
            Log.d(TAG, "Health check <- HTTP $code")

            val certResult = tofuTrustManager.lastCheckResult
            Log.d(TAG, "Health check cert: $certResult")

            when (certResult) {
                is CertPinStore.CertCheckResult.NewCertificate ->
                    HealthResult.NeedsCertApproval(certResult.fingerprint)
                is CertPinStore.CertCheckResult.FingerprintMismatch ->
                    HealthResult.CertChanged(certResult.stored, certResult.received)
                else ->
                    if (isSuccessful) HealthResult.Ok else HealthResult.ServerError(code)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Health check ERREUR : ${e.javaClass.simpleName} : ${e.message}")
            HealthResult.NetworkError(e.message ?: "Erreur inconnue")
        }
    }

    // ─── Gestion de la confiance ──────────────────────────────────────────────

    /** Enregistre le fingerprint comme approuve pour ce serveur. */
    fun trustCertificate(fingerprint: String) {
        val key = certStorageKey(config.baseUrl)
        settings.setTrustedCertFingerprint(key, fingerprint)
        Log.d(TAG, "Certificat approuve : $fingerprint")
    }

    /** Supprime la confiance accordee au certificat de ce serveur. */
    fun revokeTrust() {
        val key = certStorageKey(config.baseUrl)
        settings.removeTrustedCertFingerprint(key)
        Log.d(TAG, "Confiance revoquee pour : ${config.baseUrl}")
    }

    // ─── Utilitaires prives ───────────────────────────────────────────────────

    /**
     * Cle de stockage unique pour le fingerprint d'un serveur.
     * Basee sur le hash MD5 de l'URL normalisee (scheme + host + port).
     */
    internal fun certStorageKey(baseUrl: String): String =
        CertPinStore.storageKeyFor("", buildRootUrl(baseUrl))

    /**
     * Convertit un [CertCheckResult] en [StreamEvent.CertificateCheck] si une
     * action utilisateur est requise, ou null si la connexion peut continuer.
     */
    private fun buildCertEvent(result: CertCheckResult): StreamEvent? = when (result) {
        is CertPinStore.CertCheckResult.NewCertificate ->
            StreamEvent.CertificateCheck(
                fingerprint = result.fingerprint,
                isChanged = false,
                storedFingerprint = null
            )
        is CertPinStore.CertCheckResult.FingerprintMismatch ->
            StreamEvent.CertificateCheck(
                fingerprint = result.received,
                isChanged = true,
                storedFingerprint = result.stored
            )
        else -> null
    }


    /**
     * Cree une nouvelle session Hermes. POST /api/sessions -> session_id
     */
    suspend fun createSession(title: String): String? {
        return try {
            val body = JSONObject().apply { put("title", title) }.toString()
            val request = Request.Builder()
                .url("${buildRootUrl(config.baseUrl)}/api/sessions")
                .addHeader("Authorization", "Bearer ${config.authToken}")
                .addHeader("Content-Type", "application/json")
                .post(body.toRequestBody("application/json".toMediaType()))
                .build()
            val response = httpClient.newCall(request).execute()
            if (!response.isSuccessful) { Log.w(TAG, "createSession HTTP ${response.code}"); return null }
            val json = JSONObject(response.body?.string() ?: return null)
            (json.optString("session_id").takeIf { it.isNotBlank() }
                ?: json.optString("id").takeIf { it.isNotBlank() })
                .also { Log.d(TAG, "Session cree : $it") }
        } catch (e: Exception) {
            Log.e(TAG, "createSession error: ${e.message}")
            null
        }
    }

    /**
     * Recupere l'historique d'une session. GET /api/sessions/{id}/messages
     */
    suspend fun getSessionMessages(sessionId: String): List<ChatMessage> {
        return try {
            val request = Request.Builder()
                .url("${buildRootUrl(config.baseUrl)}/api/sessions/$sessionId/messages")
                .addHeader("Authorization", "Bearer ${config.authToken}")
                .get().build()
            val response = httpClient.newCall(request).execute()
            if (!response.isSuccessful) return emptyList()
            val arr = org.json.JSONArray(response.body?.string() ?: return emptyList())
            (0 until arr.length()).mapNotNull { i ->
                val obj = arr.getJSONObject(i)
                val role = obj.optString("role").takeIf { it.isNotBlank() } ?: return@mapNotNull null
                ChatMessage(role, obj.optString("content"))
            }
        } catch (e: Exception) {
            Log.e(TAG, "getSessionMessages error: ${e.message}")
            emptyList()
        }
    }

    /**
     * Envoie la reponse a un clarify en attente. POST /api/sessions/{id}/clarify-response
     */
    suspend fun postClarifyResponse(sessionId: String, clarifyId: String, response: String): Boolean {
        return try {
            val body = JSONObject().apply {
                put("clarify_id", clarifyId)
                put("response", response)
            }.toString()
            val request = Request.Builder()
                .url("${buildRootUrl(config.baseUrl)}/api/sessions/$sessionId/clarify-response")
                .addHeader("Authorization", "Bearer ${config.authToken}")
                .addHeader("Content-Type", "application/json")
                .post(body.toRequestBody("application/json".toMediaType()))
                .build()
            val resp = httpClient.newCall(request).execute()
            resp.close()
            resp.isSuccessful.also { Log.d(TAG, "clarify-response $clarifyId -> HTTP ${resp.code}") }
        } catch (e: Exception) {
            Log.e(TAG, "clarify-response error: ${e.message}")
            false
        }
    }

    /**
     * Supprime une session Hermes. DELETE /api/sessions/{id}
     */
    suspend fun deleteSession(sessionId: String): Boolean {
        return try {
            val request = Request.Builder()
                .url("${buildRootUrl(config.baseUrl)}/api/sessions/$sessionId")
                .addHeader("Authorization", "Bearer ${config.authToken}")
                .addHeader("X-API-Key", config.authToken)
                .delete()
                .build()
            val response = httpClient.newCall(request).execute()
            response.close()
            response.isSuccessful.also { Log.d(TAG, "deleteSession $sessionId -> $it") }
        } catch (e: Exception) {
            Log.e(TAG, "deleteSession error: ${e.message}")
            false
        }
    }
    // ─── Companion ────────────────────────────────────────────────────────────

    companion object {
        private const val TAG = "HermesConnection"

        /** Convertit un nom d'outil Hermes en message lisible pour la bulle thinking. */
        fun toolDisplayMessage(toolName: String): String = when {
            toolName.contains("web_search", ignoreCase = true)  -> "Recherche web en cours..."
            toolName.contains("spotify",    ignoreCase = true)  -> "Spotify en cours..."
            toolName.contains("terminal",   ignoreCase = true)  -> "Execution en cours..."
            toolName.contains("memory",     ignoreCase = true)  -> "Memorisation..."
            toolName.contains("files",      ignoreCase = true)  -> "Fichiers en cours..."
            toolName.contains("todo",       ignoreCase = true)  -> "Tache en cours..."
            toolName.contains("navigate",   ignoreCase = true) ||
            toolName.contains("click",      ignoreCase = true)  -> "Navigation web..."
            else -> "$toolName en cours..."
        }

        /**
         * Extrait scheme + host + port d'une URL (supprime tout chemin).
         * "http://10.200.0.2:8642/v1" -> "http://10.200.0.2:8642"
         */
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

        internal fun certStorageKey(baseUrl: String): String {
            val root = buildRootUrl(baseUrl)
            val hash = java.security.MessageDigest.getInstance("MD5")
                .digest(root.toByteArray())
                .joinToString("") { "%02x".format(it) }
            return "trusted_cert_$hash"
        }

        /**
         * Construit l'URL du health check.
         * "http://10.200.0.2:8642/v1" -> "http://10.200.0.2:8642/health"
         */
        fun buildHealthUrl(baseUrl: String): String = "${buildRootUrl(baseUrl)}/health"
    }
}

// ─── Resultat du health check ─────────────────────────────────────────────────

/** Resultat detaille du health check, incluant les cas TOFU. */
sealed class HealthResult {
    /** Serveur accessible et certificat OK. */
    object Ok : HealthResult()
    /** Premier contact : l'utilisateur doit approuver le certificat. */
    data class NeedsCertApproval(val fingerprint: String) : HealthResult()
    /** Certificat change depuis la derniere connexion. */
    data class CertChanged(val stored: String, val received: String) : HealthResult()
    /** Serveur accessible mais retourne une erreur HTTP. */
    data class ServerError(val code: Int) : HealthResult()
    /** Connexion reseau impossible. */
    data class NetworkError(val message: String) : HealthResult()
}

// ─── Modeles de donnees ───────────────────────────────────────────────────────

/** Configuration de la connexion au serveur Hermes. */
data class HermesConfig(
    val baseUrl: String,
    val authToken: String,
    val model: String = "hermes-agent"
)

/** Message au format OpenAI pour l'envoi de l'historique. */
data class ChatMessage(val role: String, val content: String)

/** Types d'erreurs réseau distingués pour un affichage UI adapté. */
enum class ErrorType {
    NO_NETWORK,
    HERMES_UNREACHABLE,
    AUTH_FAILED,
    SERVER_ERROR,
    STREAM_INTERRUPTED,
    TIMEOUT,
    INVALID_CONTEXT
}

/** Evenements du flux SSE. */
sealed class StreamEvent {
    object Connecting : StreamEvent()
    object Connected : StreamEvent()
    data class Token(val text: String) : StreamEvent()
    /** Hermes execute un outil — message court a afficher dans une bulle thinking. */
    data class Thinking(val message: String) : StreamEvent()
    /** Hermes demande une clarification — SSE reste ouvert en attendant la reponse. */
    data class Clarify(
        val clarifyId: String,
        val question: String,
        val choices: List<String>?  // null = champ texte libre
    ) : StreamEvent()
    /**
     * Fin du stream.
     * @param responseId  ID de la reponse Hermes (ex: "resp_xxx"), null si non disponible.
     *                    Stocke par le ViewModel pour "previous_response_id" au prochain message.
     */
    data class Done(
        val responseId: String? = null,
        val inputTokens: Int = 0,
        val outputTokens: Int = 0
    ) : StreamEvent()
    data class Error(
        val message: String,
        val type: ErrorType = ErrorType.HERMES_UNREACHABLE
    ) : StreamEvent()
    /**
     * Certificat non reconnu ou modifie — l'UI doit presenter une dialog
     * avant de continuer.
     *
     * @param fingerprint        Fingerprint SHA-256 du certificat recu.
     * @param isChanged          true = certificat change, false = premier contact.
     * @param storedFingerprint  Ancien fingerprint connu (null si premier contact).
     */
    data class CertificateCheck(
        val fingerprint: String,
        val isChanged: Boolean,
        val storedFingerprint: String?
    ) : StreamEvent()
}
















