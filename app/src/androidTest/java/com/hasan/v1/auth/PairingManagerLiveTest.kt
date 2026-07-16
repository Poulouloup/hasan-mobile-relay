package com.hasan.v1.auth

import android.util.Base64
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.hasan.v1.SettingsManager
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Test manuel contre un VRAI relay server (pas de mock) — vérifie le QR
 * pairing étendu (étape 3 de la migration webui : webui_url/webui_password
 * portés par le même QR que le pairing bridge). Même principe que
 * [com.hasan.v1.network.ConnectionManagerLiveTest] et
 * [com.hasan.v1.webui.WebUiRestClientLiveTest].
 *
 * Lancer avec les arguments d'instrumentation (ne jamais committer de vraie
 * URL/code en dur ici — le code de pairing est à usage unique, en générer un
 * nouveau via POST /pairing/create juste avant de lancer ce test). Le JSON
 * passe en base64 (qrPayloadB64) pour éviter les problèmes d'échappement
 * espaces/guillemets à travers adb shell -> shell distant :
 *
 *   QR_B64=$(curl ... | base64 -w0)
 *   adb shell am instrument -w \
 *     -e qrPayloadB64 "$QR_B64" \
 *     -e class com.hasan.v1.auth.PairingManagerLiveTest \
 *     com.hasan.v1.test/androidx.test.runner.AndroidJUnitRunner
 */
@RunWith(AndroidJUnit4::class)
class PairingManagerLiveTest {

    private val qrPayload: String? by lazy {
        InstrumentationRegistry.getArguments().getString("qrPayloadB64")
            ?.let { String(Base64.decode(it, Base64.DEFAULT)) }
    }

    private fun requireArgsOrSkip() {
        assumeTrue(
            "Test skippé : passer -e qrPayloadB64 <base64(json)> à l'instrumentation",
            !qrPayload.isNullOrBlank()
        )
    }

    private fun newSettings(): SettingsManager {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        return SettingsManager(context)
    }

    @Test
    fun pairFromQrContentExtractsWebUiFields() = runBlocking {
        requireArgsOrSkip()
        val settings = newSettings()
        val pairingManager = PairingManager(settings)

        // Vérifie d'abord que le JSON réel du serveur est bien parsé, y
        // compris les deux champs optionnels webui_url/webui_password.
        val parsed = pairingManager.parseQrContent(qrPayload!!)
        assertNotNull("parseQrContent a retourné null sur un payload réel", parsed)
        assertNotNull("webui_url absent du parsing", parsed!!.webUiUrl)
        assertNotNull("webui_password absent du parsing", parsed.webUiPassword)

        val result = pairingManager.pairFromQrContent(qrPayload!!)
        assertTrue("pairing a échoué: $result", result is PairingManager.PairingResult.Success)
        val success = result as PairingManager.PairingResult.Success
        assertEquals(parsed.webUiUrl, success.webUiUrl)
        assertEquals(parsed.webUiPassword, success.webUiPassword)
        assertNotNull(settings.relaySessionToken)
    }
}
