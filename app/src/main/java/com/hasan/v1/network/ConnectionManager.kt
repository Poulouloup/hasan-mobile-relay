package com.hasan.v1.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.util.Log
import com.hasan.v1.SettingsManager
import com.hasan.v1.auth.CertPinStore
import com.hasan.v1.auth.SessionTokenStore
import com.hasan.v1.network.models.Envelope
import com.hasan.v1.utils.LatencyLog
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
 * (voir [CertPinStore]), et démultiplexage des enveloppes reçues via
 * [ChannelMultiplexer].
 */
class ConnectionManager(
    private val context: Context,
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
        // 30s s'est révélé trop agressif sur liaison mobile réelle : OkHttp exige un
        // pong dans le même délai que l'intervalle de ping, et la moindre latence/gigue
        // suffisait à déclencher onFailure ("didn't receive pong"), observé en pratique
        // comme un cycle de reconnexion permanent toutes les 30-55s (voir latency.log).
        // 45s (volontairement différent des 60s du heartbeat serveur, server.py
        // handle_ws — désynchronisé pour éviter que les deux horloges de ping
        // n'échouent au même instant) laisse une marge large sans retarder
        // excessivement la détection d'une vraie coupure.
        .pingInterval(45, java.util.concurrent.TimeUnit.SECONDS)
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

    /**
     * Démarre la connexion. Sans effet si déjà connecté/en cours de connexion, ou si une
     * reconnexion est déjà planifiée ([RECONNECTING]) — sans ce dernier cas, un appel
     * externe (retour au premier plan, HassanWakeWordService.ensureConnection()) pendant
     * le backoff d'un reconnectJob en attente traversait le garde et ouvrait un second
     * WebSocket en parallèle de celui que reconnectJob ouvrira à l'expiration du délai :
     * deux sockets se disputent alors la référence webSocket, l'une devenant fantôme et
     * pouvant fermer par erreur la connexion tout juste rétablie par l'autre.
     * Si le session_token est probablement expiré ([SessionTokenStore.isLikelyExpired]),
     * tente d'abord un renouvellement silencieux via le refresh_token avant
     * d'ouvrir le socket — évite un aller-retour raté (WS ouvert puis fermé
     * en 4401) quand un simple refresh HTTP aurait suffi.
     */
    fun connect() {
        if (_connectionStatus.value == RelayConnectionStatus.CONNECTED ||
            _connectionStatus.value == RelayConnectionStatus.CONNECTING ||
            _connectionStatus.value == RelayConnectionStatus.RECONNECTING
        ) return

        manuallyDisconnected = false
        attemptCount = 0
        registerNetworkCallback()

        if (sessionTokenStore.isLikelyExpired() && sessionTokenStore.canRefresh) {
            _connectionStatus.value = RelayConnectionStatus.CONNECTING
            scope.launch {
                sessionTokenStore.tryRefresh()
                // Que le refresh ait réussi ou non, openSocket() relit
                // settings.relaySessionToken à jour — un refresh échoué laisse
                // l'ancien token, dont l'échec (4401) sera géré normalement.
                openSocket()
            }
        } else {
            openSocket()
        }
    }

    /** Ferme la connexion et annule toute reconnexion planifiée. */
    fun disconnect() {
        manuallyDisconnected = true
        unregisterNetworkCallback()
        reconnectJob?.cancel()
        reconnectJob = null
        webSocket?.close(1000, "client_disconnect")
        webSocket = null
        _connectionStatus.value = RelayConnectionStatus.DISCONNECTED
    }

    // ─────────────────────────── Détection changement réseau ───────────────
    // OkHttp ne détecte un changement d'interface (WiFi↔4G) que quand le socket
    // finit par échouer ("Software caused connection abort"), typiquement après
    // plusieurs dizaines de secondes (observé : 82s en usage réel). NetworkCallback
    // permet de réagir immédiatement à la bascule plutôt que d'attendre cet échec.
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var lastActiveNetwork: Network? = null

    private fun registerNetworkCallback() {
        if (networkCallback != null) return
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return
        lastActiveNetwork = cm.activeNetwork
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                // Bascule d'interface (ex: WiFi → 4G) détectée alors qu'on était déjà
                // connecté sur une autre interface — le socket existant est très
                // probablement mort ou sur le point de l'être, mieux vaut reconnecter
                // proactivement que d'attendre l'échec du ping/pong ou un "connection abort".
                val previous = lastActiveNetwork
                lastActiveNetwork = network
                if (previous != null && previous != network &&
                    _connectionStatus.value == RelayConnectionStatus.CONNECTED
                ) {
                    LatencyLog.mark("NETWORK_CHANGED", "connection", "network=$network reconnexion proactive")
                    webSocket?.close(1000, "network_changed")
                    webSocket = null
                    if (!manuallyDisconnected) scheduleReconnect(immediate = true)
                }
            }

            override fun onLost(network: Network) {
                if (network == lastActiveNetwork) lastActiveNetwork = null
                LatencyLog.mark("NETWORK_LOST", "connection", "network=$network")
            }
        }
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        cm.registerNetworkCallback(request, callback)
        networkCallback = callback
    }

    private fun unregisterNetworkCallback() {
        val callback = networkCallback ?: return
        val cm = context.getSystemService(ConnectivityManager::class.java)
        try {
            cm?.unregisterNetworkCallback(callback)
        } catch (_: IllegalArgumentException) {
            // Déjà désenregistré — pas d'état à vérifier avant coup côté API Android.
        }
        networkCallback = null
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

        val url = RelayUrlDeriver.webSocketUrl(settings.relayServerUrl)
        val request = Request.Builder().url(url).build()

        webSocket = httpClient.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.i(TAG, "WS ouvert — envoi de l'authentification")
                // Le token part dans le premier message applicatif, jamais dans l'URL
                // (voir RelayUrlDeriver.webSocketUrl et server/relay/server.py handle_ws).
                val authEnvelope = Envelope(
                    channel = "system",
                    type = "auth",
                    payload = JSONObject().apply { put("session_token", sessionToken) }
                )
                webSocket.send(authEnvelope.toString())

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
                if (envelope.channel == "chat") {
                    // turn = session_id pour les tours de conversation (stable sur toute la
                    // conversation, pas par tour comme MainViewModel.streamStartTime — sert
                    // seulement à observer le rythme des frames WS brutes reçues, pas à
                    // corréler précisément avec SEND/DB_FLUSH). Pour les opérations ponctuelles
                    // sans session_id (chat/health_result), utiliser envelope.id — même clé de
                    // corrélation que ChatStreamHandler.sendAndAwait côté requête — plutôt que
                    // "unknown" qui rendait tous les health checks indiscernables entre eux.
                    val sid = envelope.payload.optString("session_id").ifBlank { "health:${envelope.id}" }
                    LatencyLog.mark("WS_RECV", sid, envelope.type)
                }
                multiplexer.dispatch(envelope)
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                Log.i(TAG, "WS closing code=$code reason=$reason")
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                Log.i(TAG, "WS closed code=$code reason=$reason")
                LatencyLog.mark("WS_CLOSED", "connection", "code=$code reason=$reason")
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
                LatencyLog.mark("WS_FAILURE", "connection", t.message ?: t.javaClass.simpleName)
                this@ConnectionManager.webSocket = null
                if (!manuallyDisconnected) scheduleReconnect()
                else _connectionStatus.value = RelayConnectionStatus.DISCONNECTED
            }
        })
    }

    /**
     * [immediate] court-circuite le backoff — utilisé uniquement pour une bascule réseau
     * détectée proactivement par [registerNetworkCallback] : le socket vient d'être fermé
     * volontairement suite à un changement d'interface, pas suite à un vrai échec, donc
     * pas de raison d'attendre un backoff pensé pour laisser une instabilité se calmer.
     */
    private fun scheduleReconnect(immediate: Boolean = false) {
        if (manuallyDisconnected) return
        _connectionStatus.value = RelayConnectionStatus.RECONNECTING

        val delayMs = if (immediate) {
            0L
        } else if (attemptCount >= BACKOFF_MAX_ATTEMPTS_BEFORE_CAP) {
            BACKOFF_MAX_MS
        } else {
            (BACKOFF_INITIAL_MS shl attemptCount).coerceAtMost(BACKOFF_MAX_MS)
        }
        if (!immediate) attemptCount++

        Log.i(TAG, "Reconnexion dans ${delayMs}ms (tentative $attemptCount)")
        LatencyLog.mark("WS_RECONNECT_SCHEDULED", "connection", "attempt=$attemptCount delay=${delayMs}ms immediate=$immediate")

        reconnectJob?.cancel()
        reconnectJob = scope.launch {
            delay(delayMs)
            if (!manuallyDisconnected) openSocket()
        }
    }
}
