package com.hasan.v1.network

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.hasan.v1.SettingsManager
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Test manuel contre un VRAI relay server (pas de mock, pas de serveur
 * local) — vérifie la connexion WebSocket de bout en bout pour l'étape 4
 * du portage. Ne fait PAS partie de la suite CI habituelle : nécessite un
 * relay server joignable depuis le device de test.
 *
 * Lancer avec les arguments d'instrumentation (ne jamais committer de
 * vraie URL/token en dur ici) :
 *
 *   adb shell am instrument -w \
 *     -e relayUrl "http://<ip>:8767" \
 *     -e sessionToken "<token>" \
 *     -e class com.hasan.v1.network.ConnectionManagerLiveTest \
 *     com.hasan.v1.test/androidx.test.runner.AndroidJUnitRunner
 *
 * Si relayUrl/sessionToken ne sont pas fournis, les tests sont skippés
 * (assumeTrue) plutôt que d'échouer — pour ne pas casser un lancement de
 * suite complète sans ces arguments.
 */
@RunWith(AndroidJUnit4::class)
class ConnectionManagerLiveTest {

    private val relayUrl: String? by lazy {
        InstrumentationRegistry.getArguments().getString("relayUrl")
    }
    private val sessionToken: String? by lazy {
        InstrumentationRegistry.getArguments().getString("sessionToken")
    }

    private fun requireArgsOrSkip() {
        assumeTrue(
            "Test skippé : passer -e relayUrl <url> -e sessionToken <token> à l'instrumentation",
            !relayUrl.isNullOrBlank() && !sessionToken.isNullOrBlank()
        )
    }

    private fun newSettings(): SettingsManager {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        return SettingsManager(context).apply {
            relayServerUrl = relayUrl!!
            relaySessionToken = sessionToken!!
        }
    }

    @Test
    fun connectReachesConnectedState() = runBlocking {
        requireArgsOrSkip()
        val cm = ConnectionManager(newSettings())
        try {
            cm.connect()
            val status = withTimeout(15_000) {
                cm.connectionStatus.first { it == RelayConnectionStatus.CONNECTED }
            }
            assertEquals(RelayConnectionStatus.CONNECTED, status)
        } finally {
            cm.disconnect()
        }
    }

    @Test
    fun systemPingReceivesPong() = runBlocking {
        requireArgsOrSkip()
        val cm = ConnectionManager(newSettings())
        try {
            cm.connect()
            withTimeout(15_000) {
                cm.connectionStatus.first { it == RelayConnectionStatus.CONNECTED }
            }

            val sent = cm.send(
                com.hasan.v1.network.models.Envelope(
                    channel = "system",
                    type = "ping",
                    payload = JSONObject()
                )
            )
            assertTrue("send() a échoué alors que le statut est CONNECTED", sent)

            val pong = withTimeout(10_000) {
                cm.multiplexer.system.first { it.type == "pong" }
            }
            assertEquals("system", pong.channel)
            assertEquals("pong", pong.type)
        } finally {
            cm.disconnect()
        }
    }

    @Test
    fun disconnectStopsReconnectLoop() = runBlocking {
        requireArgsOrSkip()
        val cm = ConnectionManager(newSettings())
        cm.connect()
        withTimeout(15_000) {
            cm.connectionStatus.first { it == RelayConnectionStatus.CONNECTED }
        }

        cm.disconnect()

        // Laisse le temps à un éventuel reconnect-loop fantôme de se manifester.
        delay(3_000)
        assertEquals(RelayConnectionStatus.DISCONNECTED, cm.connectionStatus.value)
    }
}
