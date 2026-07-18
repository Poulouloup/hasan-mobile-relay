package com.hasan.v1.webui

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.hasan.v1.SettingsManager
import com.hasan.v1.webui.models.WebUiLoginResult
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Test manuel contre un VRAI hermes-webui (pas de mock) — vérifie le client
 * REST de Memory & Insights (étape 4.5 migration webui, écran Memory lecture
 * seule) de bout en bout. Même principe que [WebUiSkillsClientLiveTest].
 * Purement lecture — ne modifie rien côté serveur, rien à nettoyer après coup.
 *
 *   adb shell am instrument -w \
 *     -e webUiUrl "https://<ip>" \
 *     -e webUiPassword "<password>" \
 *     -e class com.hasan.v1.webui.WebUiMemoryClientLiveTest \
 *     com.hasan.v1.test/androidx.test.runner.AndroidJUnitRunner
 */
@RunWith(AndroidJUnit4::class)
class WebUiMemoryClientLiveTest {

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

    private suspend fun newLoggedInMemoryClient(): WebUiMemoryClient {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val settings = SettingsManager(context).apply { webUiServerUrl = webUiUrl!! }
        val authStore = WebUiAuthStore(settings)
        val restClient = WebUiRestClient(settings, authStore)
        val loginResult = restClient.login(webUiPassword!!)
        assertTrue("login échoué: $loginResult", loginResult is WebUiLoginResult.Ok)
        return WebUiMemoryClient(restClient)
    }

    @Test
    fun getMemoryReturnsRealFiles() = runBlocking {
        requireArgsOrSkip()
        val memoryClient = newLoggedInMemoryClient()

        val memory = memoryClient.getMemory()
        assertNotNull(memory)
        assertTrue(
            "au moins un des 3 fichiers (memory/user/soul) devrait être non vide",
            memory!!.memory.isNotBlank() || memory.user.isNotBlank() || memory.soul.isNotBlank()
        )
    }

    @Test
    fun getInsightsReturnsAggregatedStats() = runBlocking {
        requireArgsOrSkip()
        val memoryClient = newLoggedInMemoryClient()

        val insights = memoryClient.getInsights()
        assertNotNull(insights)
        assertEquals(30, insights!!.periodDays)
    }
}
