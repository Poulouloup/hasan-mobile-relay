package com.hasan.v1

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
import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

/**
 * Client HTTPS pour l'API Hermes (format compatible OpenAI).
 *
 * En dev : TrustManager permissif pour accepter le certificat auto-signé.
 * TODO migration prod : remplacer par un certificat signé et supprimer le TrustManager permissif.
 */
class HermesApiClient(private val config: HermesConfig) {

    // TrustManager qui accepte tous les certificats (dev uniquement)
    private val unsafeTrustManager = object : X509TrustManager {
        override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {}
        override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {}
        override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
    }

    private val sslContext = SSLContext.getInstance("TLS").apply {
        init(null, arrayOf<TrustManager>(unsafeTrustManager), SecureRandom())
    }

    private val httpClient = OkHttpClient.Builder()
        .sslSocketFactory(sslContext.socketFactory, unsafeTrustManager)
        .hostnameVerifier { _, _ -> true }
        .build()

    /**
     * Envoie un seul message utilisateur sans historique.
     * Compatibilité avec le flux wake word → STT → Hermes simple.
     */
    fun streamCompletion(userText: String): Flow<StreamEvent> =
        streamCompletionWithHistory(listOf(ChatMessage("user", userText)))

    /**
     * Envoie l'historique complet de la conversation + le dernier message utilisateur.
     * Permet de reprendre une conversation depuis l'historique.
     */
    fun streamCompletionWithHistory(messages: List<ChatMessage>): Flow<StreamEvent> = flow {
        val body = buildRequestBody(messages)
        val request = Request.Builder()
            .url("${config.baseUrl}/v1/chat/completions")
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

        if (!response.isSuccessful) {
            emit(StreamEvent.Error("Erreur serveur : ${response.code}"))
            response.close()
            return@flow
        }

        emit(StreamEvent.Connected)

        val source = response.body?.source() ?: run {
            emit(StreamEvent.Error("Réponse vide"))
            return@flow
        }

        try {
            while (!source.exhausted()) {
                val line = source.readUtf8Line() ?: break
                when {
                    line == "data: [DONE]" -> {
                        emit(StreamEvent.Done)
                        break
                    }
                    line.startsWith("data: ") -> {
                        val json = line.removePrefix("data: ")
                        val token = parseTokenFromJson(json)
                        if (token != null) emit(StreamEvent.Token(token))
                    }
                }
            }
        } catch (e: Exception) {
            emit(StreamEvent.Error("Erreur lecture flux : ${e.message}"))
        } finally {
            response.close()
        }
    }.flowOn(Dispatchers.IO)

    /** Vérifie que le serveur répond (ping /health). */
    suspend fun checkHealth(): Boolean {
        return try {
            val request = Request.Builder()
                .url("${config.baseUrl}/health")
                .get()
                .build()
            val response = httpClient.newCall(request).execute()
            val ok = response.isSuccessful
            response.close()
            ok
        } catch (e: Exception) {
            false
        }
    }

    private fun buildRequestBody(messages: List<ChatMessage>): String {
        return JSONObject().apply {
            put("model", config.model)
            put("stream", true)
            put("messages", JSONArray().apply {
                messages.forEach { msg ->
                    put(JSONObject().apply {
                        put("role", msg.role)
                        put("content", msg.content)
                    })
                }
            })
        }.toString()
    }

    private fun parseTokenFromJson(json: String): String? {
        return try {
            val obj = JSONObject(json)
            obj.getJSONArray("choices")
                .getJSONObject(0)
                .getJSONObject("delta")
                .optString("content")
                .takeIf { it.isNotEmpty() }
        } catch (e: Exception) {
            null
        }
    }
}

/** Configuration de la connexion au serveur Hermes. */
data class HermesConfig(
    val baseUrl: String,
    val authToken: String,
    val model: String = "hermes-agent"
)

/** Message au format OpenAI pour l'envoi de l'historique. */
data class ChatMessage(val role: String, val content: String)

/** Événements du flux SSE. */
sealed class StreamEvent {
    object Connecting : StreamEvent()
    object Connected : StreamEvent()
    data class Token(val text: String) : StreamEvent()
    object Done : StreamEvent()
    data class Error(val message: String) : StreamEvent()
}
