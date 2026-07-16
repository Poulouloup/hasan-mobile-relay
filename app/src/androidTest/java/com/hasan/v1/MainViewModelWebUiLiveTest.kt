package com.hasan.v1

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.hasan.v1.webui.WebUiClientHolder
import com.hasan.v1.webui.models.WebUiLoginResult
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Test manuel contre un VRAI serveur hermes-webui (pas de mock) — vérifie
 * le tour de chat complet via MainViewModel après la bascule de l'étape 3
 * (sendToHermes réécrit sur WebUiRestClient/WebUiChatStream au lieu de
 * ChatStreamHandler). Simule un pairing déjà réussi (webUiServerUrl +
 * login) plutôt que de scanner un vrai QR, qui n'est pas automatisable
 * depuis ce test.
 *
 *   adb shell am instrument -w \
 *     -e webUiUrl "https://<ip>" \
 *     -e webUiPassword "<password>" \
 *     -e class com.hasan.v1.MainViewModelWebUiLiveTest \
 *     com.hasan.v1.test/androidx.test.runner.AndroidJUnitRunner
 */
@RunWith(AndroidJUnit4::class)
class MainViewModelWebUiLiveTest {

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

    @Test
    fun newSessionThenTextMessageStreamsToCompletion() = runBlocking {
        requireArgsOrSkip()
        val app = InstrumentationRegistry.getInstrumentation().targetContext
            .applicationContext as android.app.Application

        // Simule un pairing déjà réussi : login webui direct (même chemin que
        // MainViewModel.pairFromQr après un scan QR réussi), sans passer par
        // le flow QR lui-même (non automatisable ici).
        val settings = SettingsManager(app)
        settings.webUiServerUrl = webUiUrl!!
        val restClient = WebUiClientHolder.get(app)
        val loginResult = restClient.login(webUiPassword!!)
        assertTrue("login échoué: $loginResult", loginResult is WebUiLoginResult.Ok)

        val viewModel = MainViewModel(app)

        // startPendingSession() crée la session côté serveur immédiatement
        // (voir étape 3 — plus de création paresseuse) — laisse le temps à
        // la coroutine viewModelScope.launch de la matérialiser en Room.
        viewModel.startPendingSession()
        val sessionId = withTimeout(15_000) {
            kotlinx.coroutines.flow.flow {
                while (true) {
                    val id = settings.activeSessionId
                    if (id != null) { emit(id); break }
                    kotlinx.coroutines.delay(200)
                }
            }.first()
        }
        assertNotNull("session_id jamais peuplé après startPendingSession", sessionId)

        viewModel.sendTextMessage("Réponds uniquement par le mot OK, rien d'autre.")

        // Attend que le tour se termine (sttStatus repasse à IDLE après Done/Error).
        withTimeout(180_000) {
            viewModel.uiState.first { it.sttStatus == SttStatus.IDLE && it.response.isNotBlank() }
        }

        val finalState = viewModel.uiState.value
        assertTrue("aucune réponse reçue, errorMessage=${finalState.errorMessage}", finalState.response.isNotBlank())
        assertTrue("errorType inattendu: ${finalState.errorType}", finalState.errorType == null)
    }
}
