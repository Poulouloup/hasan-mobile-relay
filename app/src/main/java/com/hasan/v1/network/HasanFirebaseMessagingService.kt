package com.hasan.v1.network

import android.util.Log
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import com.hasan.v1.SettingsManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

private const val TAG = "HasanFCM"
private const val JSON_MEDIA_TYPE = "application/json; charset=utf-8"

/**
 * Réveil FCM data-only des notifications proactives — voir
 * server/relay/server.py::_send_fcm_wake et docs/ARCHITECTURE.md pour le
 * flux complet. Ce service tourne dans un contexte d'exécution séparé
 * (peut être invoqué même app tuée), volontairement autonome : pas de
 * dépendance à [ConnectionManager]/[ChannelMultiplexer] (qui supposent un
 * WebSocket vivant), juste des appels HTTP simples vers le relay.
 *
 * Sécurité en profondeur : [onMessageReceived] ignore tout contenu du
 * payload FCM lui-même — même si le serveur envoie strictement data-only
 * par construction, ce service ne doit jamais afficher un texte qui
 * proviendrait directement de Google. Seul le contenu récupéré ensuite via
 * HTTP (canal privé TLS vers le propre relay de l'utilisateur) est digne de
 * confiance et affichable.
 */
class HasanFirebaseMessagingService : FirebaseMessagingService() {

    override fun onNewToken(token: String) {
        super.onNewToken(token)
        val settings = SettingsManager(applicationContext)
        settings.relayFcmToken = token
        // Best-effort, fire-and-forget : si offline maintenant, le filet de
        // sécurité dans ConnectionManager.openSocket().onOpen() retentera à
        // la prochaine connexion WS — pas de queue de retry dédiée pour un
        // token qui change rarement (rotation Firebase peu fréquente).
        CoroutineScope(Dispatchers.IO).launch {
            syncFcmTokenToRelay(settings, token)
        }
    }

    override fun onMessageReceived(message: RemoteMessage) {
        super.onMessageReceived(message)
        if (message.data["type"] != "wake") return

        val settings = SettingsManager(applicationContext)
        val sessionToken = settings.relaySessionToken
        if (settings.relayServerUrl.isBlank() || sessionToken.isNullOrBlank()) return

        CoroutineScope(Dispatchers.IO).launch {
            drainPendingAndNotify(applicationContext, settings)
        }
    }
}

/** Appels ponctuels (synchro de token, drainage) — même règle TLS que les autres clients, délais plus courts. */
private val httpClient by lazy {
    SecureHttp.newClientBuilder()
        .readTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
        .writeTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
        .build()
}

/** Publique — réutilisée par PairingManager.pair() pour pousser un token FCM
 * déjà connu immédiatement après un pairing réussi (voir sa doc). */
fun syncFcmTokenToRelay(settings: SettingsManager, token: String) {
    val sessionToken = settings.relaySessionToken
    if (settings.relayServerUrl.isBlank() || sessionToken.isNullOrBlank()) return

    try {
        val body = JSONObject().put("fcm_token", token).toString()
            .toRequestBody(JSON_MEDIA_TYPE.toMediaType())
        val request = Request.Builder()
            .url("${RelayUrlDeriver.httpBaseUrl(settings.relayServerUrl)}/fcm-token")
            .addHeader("Authorization", "Bearer $sessionToken")
            .post(body)
            .build()
        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                Log.w(TAG, "Échec synchro token FCM: HTTP ${response.code}")
            }
        }
    } catch (e: Exception) {
        Log.w(TAG, "Erreur réseau synchro token FCM: ${e.message}")
    }
}

private fun drainPendingAndNotify(context: android.content.Context, settings: SettingsManager) {
    val sessionToken = settings.relaySessionToken ?: return
    try {
        val request = Request.Builder()
            .url("${RelayUrlDeriver.httpBaseUrl(settings.relayServerUrl)}/phone/pending")
            .addHeader("Authorization", "Bearer $sessionToken")
            .get()
            .build()
        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                Log.w(TAG, "Échec drainage /phone/pending: HTTP ${response.code}")
                return
            }
            val body = response.body?.string() ?: return
            val messages = JSONObject(body).optJSONArray("messages") ?: return
            for (i in 0 until messages.length()) {
                val envelope = messages.optJSONObject(i) ?: continue
                if (envelope.optString("channel") != "proactive" || envelope.optString("type") != "message") continue
                val text = envelope.optJSONObject("payload")?.optString("text")?.takeIf { it.isNotBlank() } ?: continue
                ProactiveNotifier.show(context, text)
            }
        }
    } catch (e: Exception) {
        Log.w(TAG, "Erreur réseau drainage /phone/pending: ${e.message}")
    }
}
