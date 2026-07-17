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
 * REST des profils Hermes (étape 4.3 migration webui, sélecteur de profil)
 * de bout en bout. Même principe que [WebUiSkillsClientLiveTest].
 *
 * `switchProfile("default")` reconfirme simplement le profil déjà actif sur
 * le VPS de test (un seul profil existe) — cas trivial sûr à exécuter même
 * contre un serveur en usage réel, pas de bascule destructive.
 *
 *   adb shell am instrument -w \
 *     -e webUiUrl "https://<ip>" \
 *     -e webUiPassword "<password>" \
 *     -e class com.hasan.v1.webui.WebUiProfilesClientLiveTest \
 *     com.hasan.v1.test/androidx.test.runner.AndroidJUnitRunner
 */
@RunWith(AndroidJUnit4::class)
class WebUiProfilesClientLiveTest {

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

    private suspend fun newLoggedInProfilesClient(): WebUiProfilesClient {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val settings = SettingsManager(context).apply { webUiServerUrl = webUiUrl!! }
        val authStore = WebUiAuthStore(settings)
        val restClient = WebUiRestClient(settings, authStore)
        val loginResult = restClient.login(webUiPassword!!)
        assertTrue("login échoué: $loginResult", loginResult is WebUiLoginResult.Ok)
        return WebUiProfilesClient(restClient)
    }

    @Test
    fun listProfilesIncludesDefault() = runBlocking {
        requireArgsOrSkip()
        val profilesClient = newLoggedInProfilesClient()

        val profiles = profilesClient.listProfiles()
        assertNotNull(profiles)
        assertTrue(
            "au moins le profil \"default\" devrait exister",
            profiles.any { it.name == "default" }
        )
    }

    @Test
    fun switchToDefaultProfileSucceeds() = runBlocking {
        requireArgsOrSkip()
        val profilesClient = newLoggedInProfilesClient()

        val ok = profilesClient.switchProfile("default")
        assertTrue("switchProfile(\"default\") devrait réussir (profil déjà existant)", ok)
    }
}
