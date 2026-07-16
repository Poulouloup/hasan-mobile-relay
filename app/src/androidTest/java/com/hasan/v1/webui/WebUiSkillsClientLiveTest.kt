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
 * REST des skills (étape 4.2 migration webui, écran Skills lecture seule)
 * de bout en bout. Même principe que [WebUiCronClientLiveTest]. Purement
 * lecture — ne modifie rien côté serveur, rien à nettoyer après coup.
 *
 *   adb shell am instrument -w \
 *     -e webUiUrl "https://<ip>" \
 *     -e webUiPassword "<password>" \
 *     -e class com.hasan.v1.webui.WebUiSkillsClientLiveTest \
 *     com.hasan.v1.test/androidx.test.runner.AndroidJUnitRunner
 */
@RunWith(AndroidJUnit4::class)
class WebUiSkillsClientLiveTest {

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

    private suspend fun newLoggedInSkillsClient(): WebUiSkillsClient {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val settings = SettingsManager(context).apply { webUiServerUrl = webUiUrl!! }
        val authStore = WebUiAuthStore(settings)
        val restClient = WebUiRestClient(settings, authStore)
        val loginResult = restClient.login(webUiPassword!!)
        assertTrue("login échoué: $loginResult", loginResult is WebUiLoginResult.Ok)
        return WebUiSkillsClient(restClient)
    }

    @Test
    fun listSkillsThenLoadDetailAndUsage() = runBlocking {
        requireArgsOrSkip()
        val skillsClient = newLoggedInSkillsClient()

        val skills = skillsClient.listSkills()
        // Pas d'assertion sur la taille — l'installation peut n'avoir aucune skill.
        // Le vrai test est que l'appel réussit et parse sans exception.
        assertNotNull(skills)

        val usage = skillsClient.getUsage()
        assertNotNull(usage)

        if (skills.isNotEmpty()) {
            val first = skills.first()
            val detail = skillsClient.getSkillDetail(first.name)
            assertNotNull("getSkillDetail(${first.name}) a retourné null pour une skill listée", detail)
            assertTrue("le contenu de la skill ne devrait pas être vide", detail!!.content.isNotBlank())
        }

        // Une skill manifestement inexistante doit renvoyer null proprement (pas d'exception).
        val missing = skillsClient.getSkillDetail("this-skill-definitely-does-not-exist-hasan-test")
        assertTrue("une skill inexistante devrait renvoyer null", missing == null)
    }
}
