package com.hasan.v1.webui

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.hasan.v1.SettingsManager
import com.hasan.v1.buildMessageWithAttachments
import com.hasan.v1.webui.models.WebUiLoginResult
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Test manuel contre un VRAI hermes-webui (pas de mock) — vérifie
 * POST /api/upload (étape 6 migration webui, pièces jointes) de bout en
 * bout : upload d'un petit fichier texte vers une session réelle, puis
 * vérification du chemin retourné. Même principe que
 * [WebUiSkillsClientLiveTest]. Crée une session dédiée pour ne pas polluer
 * une session existante — rien à nettoyer côté serveur ensuite (l'inbox de
 * pièces jointes suit le cycle de vie de la session).
 *
 *   adb shell am instrument -w \
 *     -e webUiUrl "https://<ip>" \
 *     -e webUiPassword "<password>" \
 *     -e class com.hasan.v1.webui.WebUiUploadLiveTest \
 *     com.hasan.v1.test/androidx.test.runner.AndroidJUnitRunner
 */
@RunWith(AndroidJUnit4::class)
class WebUiUploadLiveTest {

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

    private suspend fun newLoggedInClient(): WebUiRestClient {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val settings = SettingsManager(context).apply { webUiServerUrl = webUiUrl!! }
        val authStore = WebUiAuthStore(settings)
        val restClient = WebUiRestClient(settings, authStore)
        val loginResult = restClient.login(webUiPassword!!)
        assertTrue("login échoué: $loginResult", loginResult is WebUiLoginResult.Ok)
        return restClient
    }

    @Test
    fun uploadFileThenReferenceInStartChatPayload() = runBlocking {
        requireArgsOrSkip()
        val client = newLoggedInClient()

        val sessionId = client.createSession()
        assertNotNull("createSession a retourné null", sessionId)

        val bytes = "Ceci est un fichier de test pour la pièce jointe Hasan.".toByteArray()
        val attachment = client.uploadFile(sessionId!!, "hasan-live-test.txt", "text/plain", bytes)
        assertNotNull("uploadFile a retourné null", attachment)
        assertTrue(
            "le chemin retourné devrait pointer vers l'inbox de pièces jointes de la session",
            attachment!!.path.contains(sessionId)
        )
        assertEquals("hasan-live-test.txt", attachment.name)
        assertEquals(bytes.size.toLong(), attachment.size)

        // Le serveur n'embarque le fichier dans le contexte du modèle que via le
        // texte du message (voir buildMessageWithAttachments) — vérifie que la
        // fonction produit bien le suffixe attendu avant de l'envoyer en vrai.
        val augmented = buildMessageWithAttachments("Que contient ce fichier ?", listOf(attachment))
        assertTrue(augmented.contains(attachment.path))
    }
}
