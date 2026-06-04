package com.hasan.v1

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

/**
 * Client HTTPS pour l'API Hermes (format compatible OpenAI).
 *
 * Securite TLS : systeme Trust On First Use (TOFU).
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

    /** Resultat de la verification du certificat serveur apres handshake TLS. */
    sealed class CertCheckResult {
        /** Certificat CA valide — aucune action requise. */
        object TrustedBySystem : CertCheckResult()
        /** Certificat deja connu et fingerprint identique — OK silencieux. */
        object KnownAndMatch : CertCheckResult()
        /** Premier contact avec ce serveur — fingerprint a presenter a l'utilisateur. */
        data class NewCertificate(val fingerprint: String) : CertCheckResult()
        /** Certificat change — alerte obligatoire. */
        data class FingerprintMismatch(val stored: String, val received: String) : CertCheckResult()
    }

    /**
     * TrustManager TOFU : accepte tous les certificats au niveau TLS
     * mais enregistre le resultat pour que l'appelant puisse decider.
     *
     * On ne peut pas bloquer au niveau du handshake sans perdre la chaine
     * de certificats. On autorise donc le handshake et on coupe apres.
     */
    inner class TofuTrustManager : X509TrustManager {
        var lastCheckResult: CertCheckResult = CertCheckResult.TrustedBySystem
            private set

        override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {}

        override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
            if (chain.isEmpty()) return

            val cert = chain[0]
            val fingerprint = sha256Fingerprint(cert)
            val serverKey = certStorageKey(config.baseUrl)
            val stored = settings.getTrustedCertFingerprint(serverKey)

            lastCheckResult = when {
                // Certificat CA systeme valide : confiance implicite, pas d'action
                isTrustedBySystem(chain, authType) -> CertCheckResult.TrustedBySystem
                // Premier contact : l'utilisateur doit decider
                stored == null -> CertCheckResult.NewCertificate(fingerprint)
                // Fingerprint identique au stocke : OK silencieux
                stored == fingerprint -> CertCheckResult.KnownAndMatch
                // Fingerprint different : alerte bloquante
                else -> CertCheckResult.FingerprintMismatch(stored, fingerprint)
            }
        }

        override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()

        /** Tente de valider la chaine via le TrustManager systeme Android. */
        private fun isTrustedBySystem(chain: Array<X509Certificate>, authType: String): Boolean {
            return try {
                val tmf = javax.net.ssl.TrustManagerFactory.getInstance(
                    javax.net.ssl.TrustManagerFactory.getDefaultAlgorithm()
                )
                tmf.init(null as java.security.KeyStore?)
                val systemTm = tmf.trustManagers
                    .filterIsInstance<X509TrustManager>()
                    .firstOrNull() ?: return false
                systemTm.checkServerTrusted(chain, authType)
                true
            } catch (_: Exception) {
                false
            }
        }
    }

    private val tofuTrustManager = TofuTrustManager()

    private val sslContext = SSLContext.getInstance("TLS").apply {
        init(null, arrayOf<TrustManager>(tofuTrustManager), java.security.SecureRandom())
    }

    // Le hostname n'est pas verifie par Java — la confiance est geree par TOFU
    private val httpClient = OkHttpClient.Builder()
        .sslSocketFactory(sslContext.socketFactory, tofuTrustManager)
        .hostnameVerifier { _, _ -> true }
        .build()

    // ─── Streaming SSE ────────────────────────────────────────────────────────

    /** Envoie un seul message sans historique. */
    fun streamCompletion(userText: String): Flow<StreamEvent> =
        streamCompletionWithHistory(listOf(ChatMessage("user", userText)))

    /**
     * Envoie l'historique complet + le dernier message.
     * Emet [StreamEvent.CertificateCheck] si une action utilisateur est requise
     * avant de continuer le streaming.
     */
    fun streamCompletionWithHistory(messages: List<ChatMessage>): Flow<StreamEvent> = flow {
        val body = buildRequestBody(messages)
        val request = Request.Builder()
            .url("${config.baseUrl}/v1/responses")
            .addHeader("Authorization", "Bearer ${config.authToken}")
            .addHeader("Content-Type", "application/json")
            .post(body.toRequestBody("application/json".toMediaType()))
            .build()

        emit(StreamEvent.Connecting)

        val response = try {
            httpClient.newCall(request).execute()
        } catch (e: Exception) {
            emit(StreamEvent.Error("Connexion impossible : ${e.message}"))
            return@flow
        }

        // Verifie le resultat TOFU apres le handshake TLS
        val certEvent = buildCertEvent(tofuTrustManager.lastCheckResult)
        if (certEvent != null) {
            emit(certEvent)
            response.close()
            return@flow
        }

        if (!response.isSuccessful) {
            emit(StreamEvent.Error("Erreur serveur : ${response.code}"))
            response.close()
            return@flow
        }

        emit(StreamEvent.Connected)

        val source = response.body?.source() ?: run {
            emit(StreamEvent.Error("Reponse vide"))
            return@flow
        }

        try {
            var pendingEvent: String? = null
            while (!source.exhausted()) {
                val line = source.readUtf8Line() ?: break
                Log.d(TAG, "SSE: $line")
                when {
                    line.startsWith("event: ") -> {
                        pendingEvent = line.removePrefix("event: ").trim()
                    }
                    pendingEvent == "response.completed" && line.startsWith("data: ") -> {
                        val responseId = try {
                            val obj = org.json.JSONObject(line.removePrefix("data: ").trim())
                            obj.getJSONObject("response").optString("id").takeIf { it.isNotEmpty() }
                        } catch (_: Exception) { null }
                        Log.d(TAG, "response.completed — response_id=$responseId")
                        emit(StreamEvent.Done(responseId))
                        pendingEvent = null
                        break
                    }
                    pendingEvent == "response.output_item.added" && line.startsWith("data: ") -> {
                        val toolName = try {
                            val obj = org.json.JSONObject(line.removePrefix("data: ").trim())
                            obj.optJSONObject("item")?.optString("name")
                        } catch (_: Exception) { null }
                        if (!toolName.isNullOrBlank()) {
                            emit(StreamEvent.Thinking(toolDisplayMessage(toolName)))
                            Log.d(TAG, "tool started: $toolName")
                        }
                        pendingEvent = null
                    }
                    line == "data: [DONE]" -> {
                        emit(StreamEvent.Done())
                        pendingEvent = null
                        break
                    }
                    line.startsWith("data: ") -> {
                        val json = line.removePrefix("data: ")
                        val token = parseTokenFromJson(json)
                        if (token != null) emit(StreamEvent.Token(token))
                        pendingEvent = null
                    }
                    line.isBlank() -> pendingEvent = null
                }
            }
        } catch (e: Exception) {
            emit(StreamEvent.Error("Erreur lecture flux : ${e.message}"))
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
                is CertCheckResult.NewCertificate ->
                    HealthResult.NeedsCertApproval(certResult.fingerprint)
                is CertCheckResult.FingerprintMismatch ->
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
     * Calcule le fingerprint SHA-256 d'un certificat X.509,
     * formate en paires hexadecimales separees par ":".
     * Ex : "A3:4F:2B:..."
     */
    private fun sha256Fingerprint(cert: X509Certificate): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val hash = digest.digest(cert.encoded)
        return hash.joinToString(":") { "%02X".format(it) }
    }

    /**
     * Cle de stockage unique pour le fingerprint d'un serveur.
     * Basee sur le hash MD5 de l'URL normalisee (scheme + host + port).
     */
    private fun certStorageKey(baseUrl: String): String {
        val root = buildRootUrl(baseUrl)
        val hash = MessageDigest.getInstance("MD5")
            .digest(root.toByteArray())
            .joinToString("") { "%02x".format(it) }
        return "trusted_cert_$hash"
    }

    /**
     * Convertit un [CertCheckResult] en [StreamEvent.CertificateCheck] si une
     * action utilisateur est requise, ou null si la connexion peut continuer.
     */
    private fun buildCertEvent(result: CertCheckResult): StreamEvent? = when (result) {
        is CertCheckResult.NewCertificate ->
            StreamEvent.CertificateCheck(
                fingerprint = result.fingerprint,
                isChanged = false,
                storedFingerprint = null
            )
        is CertCheckResult.FingerprintMismatch ->
            StreamEvent.CertificateCheck(
                fingerprint = result.received,
                isChanged = true,
                storedFingerprint = result.stored
            )
        else -> null
    }

    private fun buildRequestBody(messages: List<ChatMessage>): String {
        // Format OpenAI Responses API (Hermes) :
        //   - "input" = dernier message utilisateur (String)
        //   - "conversation_id" = ID de session actif (UUID stable par session)
        //   - "previous_response_id" = ID de la reponse precedente pour la continuite
        val lastUserMessage = messages.lastOrNull { it.role == "user" }?.content ?: ""
        val sessionId = settings.activeSessionId ?: "hasan-mobile"
        val previousResponseId = settings.getLastResponseId(sessionId)

        return JSONObject().apply {
            put("model", config.model)
            put("stream", true)
            put("input", lastUserMessage)
            put("conversation_id", sessionId)
            // Continuite de session : reference la reponse precedente si elle existe
            if (previousResponseId != null) {
                put("previous_response_id", previousResponseId)
            }
        }.toString()
    }

    private fun parseTokenFromJson(json: String): String? {
        return try {
            val obj = JSONObject(json)
            val type = obj.optString("type")
            when {
                // Format OpenAI Responses API (Hermes) :
                // {"type":"response.output_text.delta","delta":"token","item_id":"..."}
                type == "response.output_text.delta" ->
                    obj.optString("delta").takeIf { it.isNotEmpty() }

                // Format OpenAI Chat Completions (simulateur server.py) :
                // {"choices":[{"delta":{"content":"token"}}]}
                obj.has("choices") ->
                    obj.getJSONArray("choices")
                        .getJSONObject(0)
                        .getJSONObject("delta")
                        .optString("content")
                        .takeIf { it.isNotEmpty() }

                else -> null
            }
        } catch (e: Exception) {
            null
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

/** Evenements du flux SSE. */
sealed class StreamEvent {
    object Connecting : StreamEvent()
    object Connected : StreamEvent()
    data class Token(val text: String) : StreamEvent()
    /** Hermes execute un outil — message court a afficher dans une bulle thinking. */
    data class Thinking(val message: String) : StreamEvent()
    /**
     * Fin du stream.
     * @param responseId  ID de la reponse Hermes (ex: "resp_xxx"), null si non disponible.
     *                    Stocke par le ViewModel pour "previous_response_id" au prochain message.
     */
    data class Done(val responseId: String? = null) : StreamEvent()
    data class Error(val message: String) : StreamEvent()
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





