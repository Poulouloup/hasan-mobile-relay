package com.hasan.v1.auth

import android.util.Log
import com.hasan.v1.SettingsManager
import com.hasan.v1.network.RelayUrlDeriver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager

/**
 * Logique de pairing avec le relay server : parse le contenu d'un QR déjà
 * décodé (le scan caméra lui-même — CameraX + ML Kit — est un sujet UI
 * séparé, câblé plus tard dans MainActivity), calcule le device_hash, et
 * échange le pairing code contre un session_token via POST /pairing/register.
 *
 * Utilise le même TOFU que [com.hasan.v1.network.ConnectionManager] (namespace
 * "relay" partagé — le fingerprint approuvé ici sera celui vérifié aux
 * connexions WebSocket suivantes). Sans ça, un certificat auto-signé (cas
 * typique d'un relay auto-hébergé) ferait échouer ce tout premier appel HTTPS
 * avant même d'avoir pu proposer le TOFU à l'utilisateur.
 *
 * Format attendu du contenu QR (généré côté admin/relay via
 * POST /pairing/create) :
 *   {"relay_url": "https://host:port", "code": "ABC123"}
 */
class PairingManager(private val settings: SettingsManager) {

    sealed class PairingResult {
        data class Success(val relayUrl: String, val sessionToken: String) : PairingResult()
        data class InvalidQrContent(val reason: String) : PairingResult()
        /** Certificat inconnu ou changé — l'appelant doit présenter [certCheck] à l'utilisateur avant de retenter. */
        data class CertificateCheckRequired(
            val certCheck: CertPinStore.CertCheckResult,
            val relayUrl: String,
            val code: String
        ) : PairingResult()
        data class ServerRejected(val httpCode: Int, val error: String?) : PairingResult()
        data class NetworkError(val message: String) : PairingResult()
    }

    private val certPinStore = CertPinStore(settings)

    /**
     * Parse le contenu texte d'un QR pairing. Retourne null si le format
     * est invalide (pas de JSON, ou champs requis manquants/vides).
     */
    fun parseQrContent(rawText: String): QrPairingPayload? {
        return try {
            val obj = JSONObject(rawText)
            val relayUrl = obj.optString("relay_url").takeIf { it.isNotBlank() } ?: return null
            val code = obj.optString("code").takeIf { it.isNotBlank() } ?: return null
            QrPairingPayload(relayUrl = relayUrl, code = code)
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Échange le contenu d'un QR pairing contre un session_token, et
     * persiste [SettingsManager.relayServerUrl] / [SettingsManager.relaySessionToken]
     * en cas de succès.
     */
    suspend fun pairFromQrContent(rawText: String): PairingResult {
        val payload = parseQrContent(rawText)
            ?: return PairingResult.InvalidQrContent("QR illisible ou champs relay_url/code manquants")

        return pair(payload.relayUrl, payload.code)
    }

    /**
     * Échange un (relayUrl, code) déjà connus contre un session_token — utile
     * pour un flux sans QR (saisie manuelle). Si le certificat serveur est
     * inconnu ou a changé, retourne [PairingResult.CertificateCheckRequired]
     * sans persister quoi que ce soit — l'appelant doit faire confirmer le
     * fingerprint par l'utilisateur, appeler [trustCertificate], puis rappeler
     * [pair] pour terminer l'échange.
     */
    suspend fun pair(relayUrl: String, code: String): PairingResult = withContext(Dispatchers.IO) {
        val deviceHash = settings.relayDeviceHash
        val httpBaseUrl = RelayUrlDeriver.httpBaseUrl(relayUrl)
        val serverKey = CertPinStore.storageKeyFor("relay", httpBaseUrl)
        val trustManager = certPinStore.newTrustManager(serverKey)

        val sslContext = SSLContext.getInstance("TLS").apply {
            init(null, arrayOf<TrustManager>(trustManager), java.security.SecureRandom())
        }
        val httpClient = OkHttpClient.Builder()
            .sslSocketFactory(sslContext.socketFactory, trustManager)
            .hostnameVerifier { _, _ -> true }
            .build()

        val body = JSONObject().apply {
            put("code", code)
            put("device_hash", deviceHash)
        }.toString()

        val request = Request.Builder()
            .url("$httpBaseUrl/pairing/register")
            .post(body.toRequestBody("application/json".toMediaType()))
            .build()

        try {
            val response = httpClient.newCall(request).execute()
            val responseBody = response.body?.string()
            response.close()

            val certResult = trustManager.lastCheckResult
            if (certResult is CertPinStore.CertCheckResult.NewCertificate ||
                certResult is CertPinStore.CertCheckResult.FingerprintMismatch
            ) {
                return@withContext PairingResult.CertificateCheckRequired(certResult, httpBaseUrl, code)
            }

            if (!response.isSuccessful) {
                val error = responseBody?.let {
                    try { JSONObject(it).optString("error") } catch (_: Exception) { null }
                }
                Log.w(TAG, "Pairing refusé par le serveur : HTTP ${response.code} — $error")
                return@withContext PairingResult.ServerRejected(response.code, error)
            }

            val sessionToken = responseBody?.let {
                try { JSONObject(it).optString("session_token").takeIf { t -> t.isNotBlank() } }
                catch (_: Exception) { null }
            }
            if (sessionToken == null) {
                return@withContext PairingResult.ServerRejected(response.code, "session_token manquant dans la réponse")
            }

            settings.relayServerUrl = httpBaseUrl
            settings.relaySessionToken = sessionToken
            Log.i(TAG, "Pairing réussi avec $httpBaseUrl")
            PairingResult.Success(httpBaseUrl, sessionToken)
        } catch (e: Exception) {
            Log.e(TAG, "Erreur réseau pendant le pairing : ${e.message}")
            PairingResult.NetworkError(e.message ?: "Erreur inconnue")
        }
    }

    /** Approuve le fingerprint reçu après un [PairingResult.CertificateCheckRequired], avant de rappeler [pair]. */
    fun trustCertificate(relayUrl: String, fingerprint: String) {
        val serverKey = CertPinStore.storageKeyFor("relay", RelayUrlDeriver.httpBaseUrl(relayUrl))
        certPinStore.trustCertificate(serverKey, fingerprint)
    }

    companion object {
        private const val TAG = "PairingManager"
    }
}

/** Contenu décodé d'un QR de pairing. */
data class QrPairingPayload(val relayUrl: String, val code: String)
