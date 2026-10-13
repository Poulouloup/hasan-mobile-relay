package com.hasan.v1

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Nettoyage des empreintes laissées par l'ancien mode « confiance au premier usage » lors d'une mise à jour de l'app. */
class SettingsManagerLegacyCertPinsTest {

    @Test
    fun `les trois formats d'empreintes historiques sont purges`() {
        val keys = setOf(
            "trusted_cert_0123456789abcdef0123456789abcdef",
            "trusted_cert_relay_0123456789abcdef0123456789abcdef",
            "trusted_cert_webui_0123456789abcdef0123456789abcdef"
        )

        assertEquals(keys, SettingsManager.legacyCertPinKeys(keys).toSet())
    }

    @Test
    fun `les autres secrets ne sont pas touches`() {
        val keys = setOf(
            "relay_server_url", "relay_session_token", "relay_refresh_token", "relay_device_hash",
            "webui_server_url", "webui_session_cookie", "room_db_passphrase", "orchestrator_capabilities"
        )

        assertTrue(SettingsManager.legacyCertPinKeys(keys).isEmpty())
    }
}
