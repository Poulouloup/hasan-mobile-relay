package com.hasan.v1.webui

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.hasan.v1.SettingsManager
import com.hasan.v1.webui.models.WebUiLoginResult
import com.hasan.v1.webui.models.WebUiStreamEvent
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Test manuel contre un VRAI hermes-webui (pas de mock) — vérifie le client
 * REST/SSE de bout en bout pour l'étape 2 de la migration hermes-webui. Ne
 * fait PAS partie de la suite CI habituelle — même principe que
 * [com.hasan.v1.network.ConnectionManagerLiveTest].
 *
 * Lancer avec les arguments d'instrumentation (ne jamais committer de vraie
 * URL/mot de passe en dur ici) :
 *
 *   adb shell am instrument -w \
 *     -e webUiUrl "https://<ip>" \
 *     -e webUiPassword "<password>" \
 *     -e class com.hasan.v1.webui.WebUiRestClientLiveTest \
 *     com.hasan.v1.test/androidx.test.runner.AndroidJUnitRunner
 *
 * Si webUiUrl/webUiPassword ne sont pas fournis, les tests sont skippés
 * (assumeTrue) plutôt que d'échouer.
 */
@RunWith(AndroidJUnit4::class)
class WebUiRestClientLiveTest {

    private val webUiUrl: String? by lazy {
        InstrumentationRegistry.getArguments().getString("webUiUrl")
    }
    private val webUiPassword: String? by lazy {
        InstrumentationRegistry.getArguments().getString("webUiPassword")
    }

    private fun requireArgsOrSkip() {
        assumeTrue(
            "Test skippé : passer -e webUiUrl <url> -e webUiPassword <password> à l'instrumentation",
            !webUiUrl.isNullOrBlank() && !webUiPassword.isNullOrBlank()
        )
    }

    private fun newSettings(): SettingsManager {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        return SettingsManager(context).apply {
            webUiServerUrl = webUiUrl!!
            webUiSessionCookie = null
        }
    }

    @Test
    fun loginWithBadPasswordIsRejected() = runBlocking {
        requireArgsOrSkip()
        val settings = newSettings()
        val authStore = WebUiAuthStore(settings)
        val client = WebUiRestClient(settings, authStore)

        val result = client.login("definitely-wrong-password-12345")
        assertTrue("mot de passe erroné accepté !", result is WebUiLoginResult.InvalidPassword)
        assertTrue(authStore.currentCookie.isNullOrBlank())
    }

    @Test
    fun loginThenListSessionsThenFullChatTurn() = runBlocking {
        requireArgsOrSkip()
        val settings = newSettings()
        val authStore = WebUiAuthStore(settings)
        val client = WebUiRestClient(settings, authStore)

        val loginResult = client.login(webUiPassword!!)
        assertTrue("login a échoué: $loginResult", loginResult is WebUiLoginResult.Ok)
        assertTrue(authStore.isLoggedIn)

        // Liste des sessions — ne doit pas planter même si vide.
        val sessions = client.listSessions()
        assertNotNull(sessions)

        // Crée une session dédiée au test pour ne pas polluer une session existante.
        val sessionId = client.createSession()
        assertNotNull("createSession a retourné null", sessionId)

        val streamId = client.startChat(sessionId!!, "Réponds uniquement par le mot OK, rien d'autre.")
        assertNotNull("startChat a retourné null", streamId)

        val chatStream = WebUiChatStream(client)
        var sawToken = false
        var sawDone = false
        // 180s : le VPS de test a des MCP servers lents/en échec (whatsapp,
        // home-assistant) qui retardent chaque run agent de plusieurs
        // dizaines de secondes avant le premier token — problème
        // d'infrastructure hors périmètre du client, pas du timing SSE
        // normal. À réduire une fois ces MCP assainis côté serveur.
        withTimeout(180_000) {
            chatStream.stream(streamId!!, sessionId)
                .onEach { event ->
                    when (event) {
                        is WebUiStreamEvent.Token -> sawToken = true
                        is WebUiStreamEvent.Done -> sawDone = true
                        is WebUiStreamEvent.AppError -> throw AssertionError("stream error: ${event.message}")
                        else -> { /* tool/approval possibles, pas d'assertion dessus ici */ }
                    }
                }
                // Arrête la collecte juste après Done — takeWhile inclut l'élément
                // testé puis s'arrête, contrairement à un throw dans collect qui
                // remonterait comme une vraie CancellationException jusqu'à
                // withTimeout (et serait à tort interprétée comme un timeout).
                .takeWhile { it !is WebUiStreamEvent.Done }
                .collect()
        }
        assertTrue("aucun token reçu", sawToken)
        assertTrue("event done jamais reçu", sawDone)
    }
}
