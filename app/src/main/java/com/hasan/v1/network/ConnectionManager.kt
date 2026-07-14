package com.hasan.v1.network

import android.util.Log
import com.hasan.v1.SettingsManager
import com.hasan.v1.network.models.Envelope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.security.MessageDigest
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

/** État de la connexion WebSocket au relay server. */
enum class RelayConnectionStatus { DISCONNECTED, CONNECTING, CONNECTED, RECONNECTING }

/**
 * Client WebSocket vers le relay server (server/relay/server.py), avec
 * reconnexion automatique à backoff exponentiel, certificate pinning TOFU,
 * et démultiplexage des enveloppes reçues via [ChannelMultiplexer].
 *
 * Même modèle TOFU que [com.hasan.v1.HermesApiClient] : le handshake TLS
 * est toujours autorisé, la décision de confiance est prise après coup par
 * l'appelant sur la base du fingerprint observé.
 */
class ConnectionManager(
    private val settings: SettingsManager,
    val multiplexer: ChannelMultiplexer = ChannelMultiplexer()
) {

    companion object {
        private const val TAG = "ConnectionManager"

        private const val BACKOFF_INITIAL_MS = 1_000L
        private const val BACKOFF_MAX_MS = 5 * 60_000L
        private const val BACKOFF_MAX_ATTEMPTS_BEFORE_CAP = 20
    }

    /** Résultat de la vérification du certificat serveur après handshake TLS. */
    sealed class CertCheckResult {
        object TrustedBySystem : CertCheckResult()
        object KnownAndMatch : CertCheckResult()
        data class NewCertificate(val fingerprint: String) : CertCheckResult()
        data class FingerprintMismatch(val stored: String, val received: String) : CertCheckResult()
    }

    inner class TofuTrustManager : X509TrustManager {
        var lastCheckResult: CertCheckResult = CertCheckResult.TrustedBySystem
            private set

        override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {}

        override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
            if (chain.isEmpty()) return
            val cert = chain[0]
            val fingerprint = sha256Fingerprint(cert)
            val serverKey = settings.let {
                val root = RelayUrlDeriver.httpBaseUrl(it.relayServerUrl)
                "trusted_relay_cert_" + MessageDigest.getInstance("MD5")
                    .digest(root.toByteArray())
                    .joinToString("") { b -> "%02x".format(b) }
            }
            val stored = settings.getTrustedCertFingerprint(serverKey)

            lastCheckResult = when {
                isTrustedBySystem(chain, authType) -> CertCheckResult.TrustedBySystem
                stored == null -> CertCheckResult.NewCertificate(fingerprint)
                stored == fingerprint -> CertCheckResult.KnownAndMatch
                else -> CertCheckResult.FingerprintMismatch(stored, fingerprint)
            }
        }

        override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()

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

        private fun sha256Fingerprint(cert: X509Certificate): String {
            val digest = MessageDigest.getInstance("SHA-256")
            val hash = digest.digest(cert.encoded)
            return hash.joinToString(":") { "%02X".format(it) }
        }
    }

    private val tofuTrustManager = TofuTrustManager()

    private val sslContext = SSLContext.getInstance("TLS").apply {
        init(null, arrayOf<TrustManager>(tofuTrustManager), java.security.SecureRandom())
    }

    private val httpClient = OkHttpClient.Builder()
        .sslSocketFactory(sslContext.socketFactory, tofuTrustManager)
        .hostnameVerifier { _, _ -> true }
        .pingInterval(30, java.util.concurrent.TimeUnit.SECONDS)
        .build()

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var reconnectJob: Job? = null
    private var webSocket: WebSocket? = null
    private var attemptCount = 0
    private var manuallyDisconnected = false

    private val _connectionStatus = MutableStateFlow(RelayConnectionStatus.DISCONNECTED)
    val connectionStatus: StateFlow<RelayConnectionStatus> = _connectionStatus.asStateFlow()

    private val _certCheckEvents = MutableStateFlow<CertCheckResult?>(null)
    val certCheckEvents: StateFlow<CertCheckResult?> = _certCheckEvents.asStateFlow()

    /** Démarre la connexion. Sans effet si déjà connecté/en cours de connexion. */
    fun connect() {
        if (_connectionStatus.value == RelayConnectionStatus.CONNECTED ||
            _connectionStatus.value == RelayConnectionStatus.CONNECTING
        ) return

        manuallyDisconnected = false
        attemptCount = 0
        openSocket()
    }

    /** Ferme la connexion et annule toute reconnexion planifiée. */
    fun disconnect() {
        manuallyDisconnected = true
        reconnectJob?.cancel()
        reconnectJob = null
        webSocket?.close(1000, "client_disconnect")
        webSocket = null
        _connectionStatus.value = RelayConnectionStatus.DISCONNECTED
    }

    /** Envoie une enveloppe si la connexion est active. Retourne false si non connecté. */
    fun send(envelope: Envelope): Boolean {
        val ws = webSocket ?: return false
        return ws.send(envelope.toString())
    }

    fun trustCertificate(fingerprint: String) {
        val key = certStorageKey()
        settings.setTrustedCertFingerprint(key, fingerprint)
        Log.d(TAG, "Certificat relay approuvé : $fingerprint")
    }

    fun revokeTrust() {
        settings.removeTrustedCertFingerprint(certStorageKey())
    }

    private fun certStorageKey(): String {
        val root = RelayUrlDeriver.httpBaseUrl(settings.relayServerUrl)
        val hash = MessageDigest.getInstance("MD5")
            .digest(root.toByteArray())
            .joinToString("") { "%02x".format(it) }
        return "trusted_relay_cert_$hash"
    }

    private fun openSocket() {
        val sessionToken = settings.relaySessionToken
        if (settings.relayServerUrl.isBlank() || sessionToken.isNullOrBlank()) {
            Log.w(TAG, "relayServerUrl ou relaySessionToken non configuré — connexion annulée")
            _connectionStatus.value = RelayConnectionStatus.DISCONNECTED
            return
        }

        _connectionStatus.value = if (attemptCount == 0) RelayConnectionStatus.CONNECTING else RelayConnectionStatus.RECONNECTING

        val url = RelayUrlDeriver.webSocketUrl(settings.relayServerUrl, sessionToken)
        val request = Request.Builder().url(url).build()

        webSocket = httpClient.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.i(TAG, "WS connecté")
                attemptCount = 0
                _connectionStatus.value = RelayConnectionStatus.CONNECTED

                val certResult = tofuTrustManager.lastCheckResult
                if (certResult is CertCheckResult.NewCertificate || certResult is CertCheckResult.FingerprintMismatch) {
                    _certCheckEvents.value = certResult
                }
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                val envelope = Envelope.fromJson(text)
                if (envelope == null) {
                    Log.w(TAG, "Enveloppe invalide reçue, ignorée: ${text.take(200)}")
                    return
                }
                multiplexer.dispatch(envelope)
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                Log.i(TAG, "WS closing code=$code reason=$reason")
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                Log.i(TAG, "WS closed code=$code reason=$reason")
                this@ConnectionManager.webSocket = null
                if (!manuallyDisconnected) scheduleReconnect()
                else _connectionStatus.value = RelayConnectionStatus.DISCONNECTED
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.w(TAG, "WS échec: ${t.message}")
                this@ConnectionManager.webSocket = null
                if (!manuallyDisconnected) scheduleReconnect()
                else _connectionStatus.value = RelayConnectionStatus.DISCONNECTED
            }
        })
    }

    private fun scheduleReconnect() {
        if (manuallyDisconnected) return
        _connectionStatus.value = RelayConnectionStatus.RECONNECTING

        val delayMs = if (attemptCount >= BACKOFF_MAX_ATTEMPTS_BEFORE_CAP) {
            BACKOFF_MAX_MS
        } else {
            (BACKOFF_INITIAL_MS shl attemptCount).coerceAtMost(BACKOFF_MAX_MS)
        }
        attemptCount++

        Log.i(TAG, "Reconnexion dans ${delayMs}ms (tentative $attemptCount)")

        reconnectJob?.cancel()
        reconnectJob = scope.launch {
            delay(delayMs)
            if (!manuallyDisconnected) openSocket()
        }
    }
}
