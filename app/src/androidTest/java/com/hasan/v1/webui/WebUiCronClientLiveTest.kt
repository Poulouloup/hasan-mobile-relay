package com.hasan.v1.webui

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.hasan.v1.SettingsManager
import com.hasan.v1.webui.models.CronOpResult
import com.hasan.v1.webui.models.WebUiLoginResult
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Test manuel contre un VRAI hermes-webui (pas de mock) — vérifie le client
 * REST des cron jobs (étape 4.1 migration webui, écran Tasks) de bout en
 * bout. Même principe que [WebUiRestClientLiveTest]. Crée un job de test
 * réel puis le supprime à la fin — ne doit jamais laisser de job orphelin
 * sur le serveur en cas de succès.
 *
 *   adb shell am instrument -w \
 *     -e webUiUrl "https://<ip>" \
 *     -e webUiPassword "<password>" \
 *     -e class com.hasan.v1.webui.WebUiCronClientLiveTest \
 *     com.hasan.v1.test/androidx.test.runner.AndroidJUnitRunner
 */
@RunWith(AndroidJUnit4::class)
class WebUiCronClientLiveTest {

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

    private suspend fun newLoggedInCronClient(): WebUiCronClient {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val settings = SettingsManager(context).apply { webUiServerUrl = webUiUrl!! }
        val authStore = WebUiAuthStore(settings)
        val restClient = WebUiRestClient(settings, authStore)
        val loginResult = restClient.login(webUiPassword!!)
        assertTrue("login échoué: $loginResult", loginResult is WebUiLoginResult.Ok)
        return WebUiCronClient(restClient)
    }

    @Test
    fun createListPauseResumeDeleteJobLifecycle() = runBlocking {
        requireArgsOrSkip()
        val cronClient = newLoggedInCronClient()

        // Nom distinctif pour identifier facilement ce job de test parmi les jobs réels.
        val testName = "hasan-android-test-${System.currentTimeMillis()}"
        val createResult = cronClient.createJob(
            prompt = "Ceci est un job de test créé par WebUiCronClientLiveTest — sans danger à supprimer.",
            schedule = "every 6h",
            name = testName,
            deliver = "local"
        )
        assertTrue("createJob a échoué: $createResult", createResult is CronOpResult.Ok)

        val jobs = cronClient.listJobs()
        val created = jobs.firstOrNull { it.name == testName }
        assertNotNull("job créé introuvable dans listJobs()", created)
        val jobId = created!!.id
        assertTrue("job créé devrait être enabled par défaut", created.enabled)

        try {
            val pauseResult = cronClient.pauseJob(jobId)
            assertTrue("pauseJob a échoué: $pauseResult", pauseResult is CronOpResult.Ok)

            val afterPause = cronClient.listJobs().firstOrNull { it.id == jobId }
            assertNotNull("job introuvable après pause", afterPause)
            assertTrue("job devrait être désactivé après pause", afterPause!!.enabled.not())

            val resumeResult = cronClient.resumeJob(jobId)
            assertTrue("resumeJob a échoué: $resumeResult", resumeResult is CronOpResult.Ok)

            val afterResume = cronClient.listJobs().firstOrNull { it.id == jobId }
            assertNotNull("job introuvable après resume", afterResume)
            assertTrue("job devrait être réactivé après resume", afterResume!!.enabled)

            val deliveryOptions = cronClient.deliveryOptions()
            assertTrue("deliveryOptions vide", deliveryOptions.isNotEmpty())
            assertTrue("'local' devrait être une option", deliveryOptions.any { it.value == "local" })
        } finally {
            val deleteResult = cronClient.deleteJob(jobId)
            assertTrue("deleteJob a échoué (job de test potentiellement orphelin sur le serveur !): $deleteResult", deleteResult is CronOpResult.Ok)
        }

        val jobsAfterDelete = cronClient.listJobs()
        assertTrue("job de test toujours présent après suppression", jobsAfterDelete.none { it.id == jobId })
    }
}
