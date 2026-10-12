package com.hasan.v1.network

import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.After
import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.BeforeClass
import org.junit.Test
import java.io.File
import java.io.IOException
import java.net.ServerSocket
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.security.KeyStore
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLPeerUnverifiedException

/**
 * Vérifie la règle TLS commune sur le client réellement utilisé en production
 * ([SecureHttp.newClientBuilder], sans aucun paramètre de test) face à un
 * serveur HTTPS local. L'autorité de test est installée comme magasin de
 * confiance de la JVM (propriétés javax.net.ssl.trustStore*) : elle joue le
 * rôle des autorités du système Android, sans ouvrir de porte dérobée dans le
 * code de l'app pour injecter un gestionnaire de confiance.
 */
class SecureHttpTest {

    companion object {
        private const val HOST = "localhost"
        private val TRUST_PROPS = listOf(
            "javax.net.ssl.trustStore", "javax.net.ssl.trustStorePassword", "javax.net.ssl.trustStoreType"
        )
        private val savedProps = mutableMapOf<String, String?>()
        private lateinit var trustStoreFile: File

        /** Autorité reconnue par le « système » (équivalent de Let's Encrypt dans ces tests). */
        private val trustedCa: HeldCertificate = HeldCertificate.Builder()
            .commonName("Autorite de test reconnue")
            .certificateAuthority(0)
            .build()

        @BeforeClass
        @JvmStatic
        fun installTrustedCa() {
            TRUST_PROPS.forEach { savedProps[it] = System.getProperty(it) }
            trustStoreFile = File.createTempFile("hasan-test-truststore", ".jks")
            val keyStore = KeyStore.getInstance("JKS").apply {
                load(null, null)
                setCertificateEntry("test-ca", trustedCa.certificate)
            }
            trustStoreFile.outputStream().use { keyStore.store(it, "changeit".toCharArray()) }
            System.setProperty("javax.net.ssl.trustStore", trustStoreFile.absolutePath)
            System.setProperty("javax.net.ssl.trustStorePassword", "changeit")
            System.setProperty("javax.net.ssl.trustStoreType", "JKS")
        }

        @AfterClass
        @JvmStatic
        fun restoreTrustStore() {
            savedProps.forEach { (key, value) ->
                if (value == null) System.clearProperty(key) else System.setProperty(key, value)
            }
            trustStoreFile.delete()
        }
    }

    private val servers = mutableListOf<MockWebServer>()

    @After
    fun shutdownServers() {
        servers.forEach { it.shutdown() }
    }

    /** Certificat feuille pour [hostname], signé par [issuer] (auto-signé si null). */
    private fun leaf(hostname: String, issuer: HeldCertificate?): HeldCertificate =
        HeldCertificate.Builder()
            .commonName(hostname)
            .addSubjectAlternativeName(hostname)
            .apply { if (issuer != null) signedBy(issuer) }
            .build()

    private fun httpsServer(certificate: HeldCertificate): MockWebServer {
        val handshake = HandshakeCertificates.Builder().heldCertificate(certificate).build()
        return MockWebServer().apply {
            useHttps(handshake.sslSocketFactory(), false)
            enqueue(MockResponse().setBody("""{"status":"ok"}"""))
            start()
            servers += this
        }
    }

    private fun healthRequest(server: MockWebServer): Request =
        Request.Builder().url("https://$HOST:${server.port}/health").build()

    private fun failureOf(server: MockWebServer): IOException {
        try {
            SecureHttp.newClientBuilder().build().newCall(healthRequest(server)).execute().close()
        } catch (e: IOException) {
            return e
        }
        fail("La connexion aurait dû être refusée")
        throw AssertionError()
    }

    @Test
    fun `certificat d'une autorite reconnue pour le bon nom d'hote - accepte`() {
        val server = httpsServer(leaf(HOST, trustedCa))

        SecureHttp.newClientBuilder().build().newCall(healthRequest(server)).execute().use { response ->
            assertEquals(200, response.code)
        }
    }

    @Test
    fun `certificat renouvele - accepte sans rien avoir enregistre`() {
        // Deux certificats distincts (clé et numéro de série différents) pour le
        // même nom, comme avant/après un renouvellement Let's Encrypt.
        val before = httpsServer(leaf(HOST, trustedCa))
        val after = httpsServer(leaf(HOST, trustedCa))
        val client = SecureHttp.newClientBuilder().build()

        client.newCall(healthRequest(before)).execute().use { assertEquals(200, it.code) }
        client.newCall(healthRequest(after)).execute().use { assertEquals(200, it.code) }
    }

    @Test
    fun `certificat auto-signe - refuse avec une erreur claire`() {
        val server = httpsServer(leaf(HOST, issuer = null))

        val failure = failureOf(server)

        assertTrue("attendu SSLHandshakeException, reçu $failure", failure is SSLHandshakeException)
        val message = SecureHttp.describeFailure(failure)
        assertTrue(message, message.contains("non reconnu"))
        assertFalse("un certificat refusé n'est pas un problème de Tailscale", message.contains("Tailscale"))
        assertEquals("aucune requête ne doit partir sur une connexion refusée", 0, server.requestCount)
    }

    @Test
    fun `certificat valide mais pour un autre nom d'hote - refuse`() {
        val server = httpsServer(leaf("hasan.exemple.net", trustedCa))

        val failure = failureOf(server)

        assertTrue("attendu SSLPeerUnverifiedException, reçu $failure", failure is SSLPeerUnverifiedException)
        val message = SecureHttp.describeFailure(failure)
        assertTrue(message, message.contains("nom d'hôte"))
        assertFalse(message, message.contains("Tailscale"))
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `serveur injoignable - le message suggere Tailscale`() {
        val closedPort = ServerSocket(0).use { it.localPort }
        val request = Request.Builder().url("https://$HOST:$closedPort/health").build()

        val failure = try {
            SecureHttp.newClientBuilder().build().newCall(request).execute().close()
            null
        } catch (e: IOException) {
            e
        }

        val message = SecureHttp.describeFailure(failure ?: throw AssertionError("connexion inattendue"))
        assertTrue(message, message.contains(SecureHttp.TAILSCALE_HINT))
    }

    @Test
    fun `nom introuvable ou delai depasse - le message suggere Tailscale`() {
        listOf(
            UnknownHostException("hasan.maison.dynv6.net"),
            SocketTimeoutException("timeout")
        ).forEach { failure ->
            val message = SecureHttp.describeFailure(failure)
            assertTrue(message, message.startsWith("Serveur injoignable"))
            assertTrue(message, message.endsWith(SecureHttp.TAILSCALE_HINT))
        }
    }
}
