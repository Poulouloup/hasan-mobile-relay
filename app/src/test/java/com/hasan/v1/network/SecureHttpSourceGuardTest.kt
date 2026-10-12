package com.hasan.v1.network

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Garde-fou : tous les clients réseau de l'app doivent sortir de
 * [SecureHttp.newClientBuilder], et personne (SecureHttp compris) ne doit
 * réintroduire un gestionnaire de confiance ou un vérificateur de nom d'hôte
 * maison. Les clients eux-mêmes (ConnectionManager, PairingManager,
 * WebUiRestClient, service FCM) dépendent du framework Android et ne sont pas
 * instanciables dans un test JVM — on vérifie donc la règle sur le code source.
 */
class SecureHttpSourceGuardTest {

    private val sourceRoot = File("src/main/java")

    /** Lignes de code hors commentaires — un commentaire a le droit de nommer ce qui est interdit. */
    private fun codeLines(file: File): List<String> =
        file.readLines().map { it.trim() }.filterNot { it.startsWith("//") || it.startsWith("*") || it.startsWith("/*") }

    private fun kotlinSources(): List<File> {
        assertTrue("sources introuvables depuis ${File(".").absolutePath}", sourceRoot.isDirectory)
        return sourceRoot.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
    }

    @Test
    fun `aucun gestionnaire de confiance ni verificateur de nom d'hote maison`() {
        val forbidden = listOf(
            "hostnameVerifier", "HostnameVerifier", "sslSocketFactory", "TrustManager",
            "SSLContext", "HttpsURLConnection", "CertificatePinner"
        )
        val offenders = kotlinSources().flatMap { file ->
            codeLines(file).filter { line -> forbidden.any { it in line } }.map { "${file.name}: $it" }
        }
        assertTrue("TLS personnalisé interdit, voir SecureHttp :\n${offenders.joinToString("\n")}", offenders.isEmpty())
    }

    @Test
    fun `tous les clients OkHttp sont construits par SecureHttp`() {
        val offenders = kotlinSources()
            .filterNot { it.name == "SecureHttp.kt" }
            .flatMap { file ->
                codeLines(file)
                    .filter { "OkHttpClient.Builder(" in it || "OkHttpClient()" in it }
                    .map { "${file.name}: $it" }
            }
        assertTrue("Client construit hors de SecureHttp :\n${offenders.joinToString("\n")}", offenders.isEmpty())
    }
}
