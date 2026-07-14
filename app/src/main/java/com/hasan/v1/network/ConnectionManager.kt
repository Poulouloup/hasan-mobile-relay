package com.hasan.v1.network

import android.util.Log
import com.hasan.v1.SettingsManager
import com.hasan.v1.auth.CertPinStore
import com.hasan.v1.auth.SessionTokenStore
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
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager

/** État de la connexion WebSocket au relay server. */
enum class RelayConnectionStatus { DISCONNECTED, CONNECTING, CONNECTED, RECONNECTING }

/** Alias vers [CertPinStore.CertCheckResult] — conservé pour la compatibilité du code appelant de [ConnectionManager]. */
typealias RelayCertCheckResult = CertPinStore.CertCheckResult

/**
 * Client WebSocket vers le relay server (server/relay/server.py), avec
 * reconnexion automatique à backoff exponentiel, certificate pinning TOFU
 * (voir [CertPinStore], partagé avec [com.hasan.v1.HermesApiClient]), et
 * démultiplexage des enveloppes reçues via [ChannelMultiplexer].
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

        // Voir server/relay/server.py handle_ws — fermeture explicite sur token invalide/expiré.
        private const val WS_CLOSE_CODE_INVALID_SESSION = 4401
    }

    private val certPinStore = CertPinStore(settings)
    private val sessionTokenStore = SessionTokenStore(settings)

    private fun certStorageKey(): String =
        CertPinStore.storageKeyFor("relay", RelayUrlDeriver.httpBaseUrl(settings.relayServerUrl))

    private val tofuTrustManager = certPinStore.newTrustManager(certStorageKey())

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

    private val _certCheckEvents = MutableStateFlow<RelayCertCheckResult?>(null)
    val certCheckEvents: StateFlow<RelayCertCheckResult?> = _certCheckEvents.asStateFlow()

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
        certPinStore.trustCertificate(certStorageKey(), fingerprint)
    }

    fun revokeTrust() {
        certPinStore.revokeTrust(certStorageKey())
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
                sessionTokenStore.markRenewed()

                val certResult = tofuTrustManager.lastCheckResult
                if (certResult is CertPinStore.CertCheckResult.NewCertificate ||
                    certResult is CertPinStore.CertCheckResult.FingerprintMismatch
                ) {
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
                if (code == WS_CLOSE_CODE_INVALID_SESSION) {
                    // Le serveur a explicitement rejeté ce token (voir server/relay/server.py
                    // handle_ws) — inutile de retenter avec le même token, l'app doit re-pairer.
                    Log.w(TAG, "Session invalide/expirée confirmée par le serveur — token effacé")
                    sessionTokenStore.clear()
                    manuallyDisconnected = true
                    _connectionStatus.value = RelayConnectionStatus.DISCONNECTED
                } else if (!manuallyDisconnected) {
                    scheduleReconnect()
                } else {
                    _connectionStatus.value = RelayConnectionStatus.DISCONNECTED
                }
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
