package com.hasan.v1.network

import com.hasan.v1.webui.WebUiUrlDeriver
import org.junit.Assert.assertEquals
import org.junit.Test

/** Aucune adresse dérivée ne doit pouvoir sortir en clair, quelle que soit la saisie. */
class UrlDeriverTest {

    @Test
    fun `adresse https conservee`() {
        assertEquals("https://hasan.exemple.net", RelayUrlDeriver.httpBaseUrl("https://hasan.exemple.net/"))
        assertEquals("wss://hasan.exemple.net/ws", RelayUrlDeriver.webSocketUrl("https://hasan.exemple.net"))
        assertEquals("https://hermes.exemple.net", WebUiUrlDeriver.httpBaseUrl("https://hermes.exemple.net/"))
    }

    @Test
    fun `adresse sans schema - https par defaut`() {
        assertEquals("https://hasan.exemple.net", RelayUrlDeriver.httpBaseUrl("hasan.exemple.net"))
        assertEquals("wss://hasan.exemple.net/ws", RelayUrlDeriver.webSocketUrl("hasan.exemple.net"))
        assertEquals("https://hermes.exemple.net", WebUiUrlDeriver.httpBaseUrl("hermes.exemple.net"))
    }

    @Test
    fun `adresse saisie en clair - ramenee a https et wss`() {
        assertEquals("https://hasan.exemple.net", RelayUrlDeriver.httpBaseUrl("http://hasan.exemple.net"))
        assertEquals("wss://hasan.exemple.net/ws", RelayUrlDeriver.webSocketUrl("http://hasan.exemple.net"))
        assertEquals("wss://hasan.exemple.net/ws", RelayUrlDeriver.webSocketUrl("ws://hasan.exemple.net"))
        assertEquals("https://hermes.exemple.net", WebUiUrlDeriver.httpBaseUrl("http://hermes.exemple.net"))
    }

    @Test
    fun `port explicite conserve`() {
        assertEquals("https://hasan.exemple.net:8443", RelayUrlDeriver.httpBaseUrl("http://hasan.exemple.net:8443"))
        assertEquals("wss://hasan.exemple.net:8443/ws", RelayUrlDeriver.webSocketUrl("hasan.exemple.net:8443"))
    }
}
