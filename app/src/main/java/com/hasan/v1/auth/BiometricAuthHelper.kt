package com.hasan.v1.auth

import android.util.Log
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * Authentification biométrique/PIN de l'appareil — utilisée pour protéger
 * l'activation du relay bridge (SMS, localisation, etc.), voir rework UI
 * des connexions. Accepte empreinte/visage OU code/schéma de l'appareil
 * (BIOMETRIC_WEAK or DEVICE_CREDENTIAL) : pas de dépendance à un capteur
 * biométrique présent, un simple verrou d'écran suffit.
 */
object BiometricAuthHelper {

    private const val TAG = "BiometricAuthHelper"
    private const val AUTHENTICATORS =
        BiometricManager.Authenticators.BIOMETRIC_WEAK or BiometricManager.Authenticators.DEVICE_CREDENTIAL

    /**
     * Retourne true si l'utilisateur s'est authentifié avec succès. Ne lève
     * jamais d'exception — un échec/annulation retourne false, à traiter
     * comme un refus par l'appelant (même style que CapabilityResult).
     * Si aucun authenticator n'est configuré sur l'appareil (émulateur sans
     * lockscreen, etc.), retourne true directement plutôt que de bloquer
     * l'activation — log un avertissement.
     */
    suspend fun authenticate(activity: FragmentActivity, title: String, subtitle: String): Boolean {
        val manager = BiometricManager.from(activity)
        if (manager.canAuthenticate(AUTHENTICATORS) != BiometricManager.BIOMETRIC_SUCCESS) {
            Log.w(TAG, "Aucun authenticator disponible sur cet appareil — activation autorisée sans prompt")
            return true
        }

        return suspendCancellableCoroutine { cont ->
            val prompt = BiometricPrompt(
                activity,
                ContextCompat.getMainExecutor(activity),
                object : BiometricPrompt.AuthenticationCallback() {
                    override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                        if (cont.isActive) cont.resume(true)
                    }

                    override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                        Log.w(TAG, "Authentification annulée/échouée : $errString")
                        if (cont.isActive) cont.resume(false)
                    }

                    override fun onAuthenticationFailed() {
                        // Tentative refusée (empreinte non reconnue) — le prompt reste ouvert,
                        // l'utilisateur peut réessayer ; pas de résolution ici.
                    }
                }
            )
            val promptInfo = BiometricPrompt.PromptInfo.Builder()
                .setTitle(title)
                .setSubtitle(subtitle)
                .setAllowedAuthenticators(AUTHENTICATORS)
                .build()

            cont.invokeOnCancellation { prompt.cancelAuthentication() }
            prompt.authenticate(promptInfo)
        }
    }
}
