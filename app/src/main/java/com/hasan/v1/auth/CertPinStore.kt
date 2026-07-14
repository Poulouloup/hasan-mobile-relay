package com.hasan.v1.auth

import android.util.Log
import com.hasan.v1.SettingsManager
import java.security.MessageDigest
import java.security.cert.X509Certificate
import javax.net.ssl.X509TrustManager

/**
 * TOFU (Trust On First Use) partagé entre tous les clients réseau de l'app
 * (Hermes, relay server, tout futur client). Auparavant dupliqué à
 * l'identique dans [com.hasan.v1.HermesApiClient] et
 * [com.hasan.v1.network.ConnectionManager] — centralisé ici pour n'avoir
 * qu'une seule implémentation à faire évoluer.
 *
 * Le handshake TLS est toujours autorisé (on ne peut pas bloquer au niveau
 * du handshake sans perdre la chaîne de certificats) ; la décision de
 * confiance est prise après coup par l'appelant sur la base du
 * [CertCheckResult] observé.
 */
class CertPinStore(private val settings: SettingsManager) {

    /** Résultat de la vérification du certificat serveur après handshake TLS. */
    sealed class CertCheckResult {
        /** Certificat CA valide — aucune action requise. */
        object TrustedBySystem : CertCheckResult()
        /** Certificat déjà connu et fingerprint identique — OK silencieux. */
        object KnownAndMatch : CertCheckResult()
        /** Premier contact avec ce serveur — fingerprint à présenter à l'utilisateur. */
        data class NewCertificate(val fingerprint: String) : CertCheckResult()
        /** Certificat changé — alerte bloquante. */
        data class FingerprintMismatch(val stored: String, val received: String) : CertCheckResult()
    }

    /**
     * TrustManager TOFU pour un serveur donné, identifié par [serverKey]
     * (clé de stockage stable — voir [storageKeyFor]). Une instance par
     * client réseau (chaque serveur a son propre fingerprint attendu).
     */
    inner class TofuTrustManager(private val serverKey: String) : X509TrustManager {
        var lastCheckResult: CertCheckResult = CertCheckResult.TrustedBySystem
            private set

        override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {}

        override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
            if (chain.isEmpty()) return

            val fingerprint = sha256Fingerprint(chain[0])
            val stored = settings.getTrustedCertFingerprint(serverKey)

            lastCheckResult = when {
                isTrustedBySystem(chain, authType) -> CertCheckResult.TrustedBySystem
                stored == null -> CertCheckResult.NewCertificate(fingerprint)
                stored == fingerprint -> CertCheckResult.KnownAndMatch
                else -> CertCheckResult.FingerprintMismatch(stored, fingerprint)
            }
        }

        override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()

        private fun isTrustedBySystem(chain: Array<X509Certificate>, authType: String): Boolean {
            return try {
                val tmf = javax.net.ssl.TrustManagerFactory.getInstance(
                    javax.net.ssl.TrustManagerFactory.getDefaultAlgorithm()
                )
                tmf.init(null as java.security.KeyStore?)
                val systemTm = tmf.trustManagers
                    .filterIsInstance<X509TrustManager>()
                    .firstOrNull() ?: return false
                systemTm.checkServerTrusted(chain, authType)
                true
            } catch (_: Exception) {
                false
            }
        }
    }

    /** Crée un TrustManager TOFU pour le serveur identifié par [serverKey]. */
    fun newTrustManager(serverKey: String): TofuTrustManager = TofuTrustManager(serverKey)

    /** Enregistre le fingerprint comme approuvé pour ce serveur. */
    fun trustCertificate(serverKey: String, fingerprint: String) {
        settings.setTrustedCertFingerprint(serverKey, fingerprint)
        Log.d(TAG, "Certificat approuvé pour $serverKey : $fingerprint")
    }

    /** Supprime la confiance accordée à ce serveur. */
    fun revokeTrust(serverKey: String) {
        settings.removeTrustedCertFingerprint(serverKey)
        Log.d(TAG, "Confiance révoquée pour $serverKey")
    }

    companion object {
        private const val TAG = "CertPinStore"

        /**
         * Clé de stockage stable pour un serveur, à partir de son URL racine
         * déjà normalisée (scheme+host+port — voir
         * [com.hasan.v1.HermesApiClient.Companion.buildRootUrl] ou
         * [com.hasan.v1.network.RelayUrlDeriver.httpBaseUrl]).
         *
         * [namespace] distingue les serveurs de nature différente (ex: "relay")
         * pour qu'un même host sur deux rôles différents n'entre pas en
         * collision de fingerprint. Namespace vide = format historique
         * "trusted_cert_<md5>" utilisé par Hermes avant la factorisation —
         * conservé tel quel pour ne pas invalider les fingerprints déjà
         * approuvés par les utilisateurs existants (sinon re-prompt TOFU
         * au premier lancement après mise à jour).
         */
        fun storageKeyFor(namespace: String, rootUrl: String): String {
            val hash = MessageDigest.getInstance("MD5")
                .digest(rootUrl.toByteArray())
                .joinToString("") { "%02x".format(it) }
            val prefix = if (namespace.isEmpty()) "trusted_cert_" else "trusted_cert_${namespace}_"
            return "$prefix$hash"
        }

        fun sha256Fingerprint(cert: X509Certificate): String {
            val digest = MessageDigest.getInstance("SHA-256")
            val hash = digest.digest(cert.encoded)
            return hash.joinToString(":") { "%02X".format(it) }
        }
    }
}
