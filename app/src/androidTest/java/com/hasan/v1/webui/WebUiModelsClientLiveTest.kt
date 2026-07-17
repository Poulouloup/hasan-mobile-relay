package com.hasan.v1.webui

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.hasan.v1.SettingsManager
import com.hasan.v1.webui.models.WebUiLoginResult
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Test manuel contre un VRAI hermes-webui (pas de mock) — vérifie le client
 * REST du catalogue de modèles (étape 4.3 migration webui, sélecteur de
 * modèle) de bout en bout. Même principe que [WebUiSkillsClientLiveTest].
 * Purement lecture — ne modifie rien côté serveur.
 *
 *   adb shell am instrument -w \
 *     -e webUiUrl "https://<ip>" \
 *     -e webUiPassword "<password>" \
 *     -e class com.hasan.v1.webui.WebUiModelsClientLiveTest \
 *     com.hasan.v1.test/androidx.test.runner.AndroidJUnitRunner
 */
@RunWith(AndroidJUnit4::class)
class WebUiModelsClientLiveTest {

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

    private suspend fun newLoggedInModelsClient(): WebUiModelsClient {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val settings = SettingsManager(context).apply { webUiServerUrl = webUiUrl!! }
        val authStore = WebUiAuthStore(settings)
        val restClient = WebUiRestClient(settings, authStore)
        val loginResult = restClient.login(webUiPassword!!)
        assertTrue("login échoué: $loginResult", loginResult is WebUiLoginResult.Ok)
        return WebUiModelsClient(restClient)
    }

    @Test
    fun getModelsReturnsCoherentCatalog() = runBlocking {
        requireArgsOrSkip()
        val modelsClient = newLoggedInModelsClient()

        val catalog = modelsClient.getModels()
        assertNotNull("getModels() ne devrait pas échouer contre un serveur joignable", catalog)
        requireNotNull(catalog)

        // Pas d'assertion sur le nombre de groupes/modèles — dépend de la config
        // serveur. Le vrai test est que l'appel réussit et parse sans exception.
        if (catalog.defaultModel != null) {
            val allModelIds = catalog.groups.flatMap { it.models }.map { it.id }
            assertTrue(
                "default_model (${catalog.defaultModel}) devrait figurer parmi les modèles listés",
                allModelIds.contains(catalog.defaultModel)
            )
        }
    }
}
