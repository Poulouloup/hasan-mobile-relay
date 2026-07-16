package com.hasan.v1.webui

import android.content.Context
import com.hasan.v1.SettingsManager

/**
 * Instance unique de [WebUiRestClient]/[WebUiAuthStore], partagée entre
 * MainViewModel (premier plan) et HassanWakeWordService/WakeWordPipeline
 * (pipeline autonome en arrière-plan) — contrairement au bridge WSS
 * (ConnectionManager), qui garde deux connexions indépendantes par
 * composant, un client HTTP + cookie n'a pas besoin de cette isolation.
 *
 * Construction paresseuse sur [Context.getApplicationContext] pour être
 * accessible aussi bien depuis un Service (`this` est déjà un Context) que
 * depuis un ViewModel (`getApplication()`).
 */
object WebUiClientHolder {

    @Volatile
    private var restClient: WebUiRestClient? = null

    fun get(context: Context): WebUiRestClient {
        return restClient ?: synchronized(this) {
            restClient ?: run {
                val settings = SettingsManager(context.applicationContext)
                val authStore = WebUiAuthStore(settings)
                WebUiRestClient(settings, authStore).also { restClient = it }
            }
        }
    }
}
