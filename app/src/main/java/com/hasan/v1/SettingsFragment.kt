package com.hasan.v1

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.ComposeView
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.hasan.v1.auth.CertPinStore
import com.hasan.v1.network.RelayConnectionStatus
import com.hasan.v1.webui.WebUiClientHolder
import com.hasan.v1.webui.WebUiProfilesClient
import com.hasan.v1.webui.models.HermesProfile
import com.hasan.v1.webui.models.WebUiHealthResult
import com.hasan.v1.ui.screens.ConnectionStatusUi
import com.hasan.v1.ui.screens.SettingsCallbacks
import com.hasan.v1.ui.screens.SettingsScreen
import com.hasan.v1.ui.screens.SettingsUiState
import com.hasan.v1.ui.screens.TtsEngineOption
import com.hasan.v1.ui.theme.HasanTheme
import com.hasan.v1.utils.HasanDialog
import kotlinx.coroutines.launch

/**
 * Fragment Paramètres — lecture/écriture via SettingsManager (EncryptedSharedPreferences).
 * Accessible depuis le drawer (voir HasanDrawer.kt), sans bouton retour.
 *
 * L'UI est un unique ComposeView (SettingsScreen) — ce Fragment reste le seul
 * responsable de la logique métier (persistance SettingsManager, TOFU) et pilote
 * l'état Compose via des mutableStateOf recomposés à chaque changement, exactement
 * comme l'ancien binding impératif mais sans vues XML. La gestion des sessions
 * (création/renommage/suppression/activation) vit désormais dans MainActivity,
 * pilotée depuis le drawer.
 */
class SettingsFragment : Fragment() {

    private val viewModel: MainViewModel by activityViewModels()
    private val settings get() = viewModel.settings
    private val webUiRestClient by lazy { WebUiClientHolder.get(requireContext()) }
    private val profilesClient by lazy { WebUiProfilesClient(webUiRestClient) }

    // ─────────────────────────── État Compose ──────────────────────────────
    // mutableStateOf plutôt que StateFlow ici : SettingsManager (SharedPreferences)
    // n'est pas observable nativement, ce Fragment reste la seule source de vérité
    // qui pousse les changements vers l'état Compose après chaque action utilisateur.

    private var serverUrlState by mutableStateOf("")
    private var authTokenState by mutableStateOf("")
    private var connectionStatusState by mutableStateOf<ConnectionStatusUi?>(null)

    private var ttsProviderState by mutableStateOf(SettingsManager.DEFAULT_TTS_PROVIDER)
    private var ttsSubOptionsState by mutableStateOf<List<Pair<String, String>>>(emptyList())
    private var ttsSelectedSubOptionState by mutableStateOf("")
    private var nativeEnginesState by mutableStateOf<List<TtsEngineOption>>(emptyList())
    private var nativeSelectedEngineState by mutableStateOf("")
    private var ttsEnabledState by mutableStateOf(SettingsManager.DEFAULT_TTS_ENABLED)
    private var ttsSpeedState by mutableStateOf(SettingsManager.DEFAULT_SPEED)
    private var ttsVolumeState by mutableStateOf(SettingsManager.DEFAULT_VOLUME)

    private var wakeWordEnabledState by mutableStateOf(SettingsManager.DEFAULT_WAKE_ENABLED)
    private var wakeWordSensitivityState by mutableStateOf(SettingsManager.DEFAULT_SENSITIVITY)
    private var wakeWordModelState by mutableStateOf(SettingsManager.DEFAULT_WAKE_WORD_MODEL)

    private var hermesProfilesState by mutableStateOf<List<HermesProfile>>(emptyList())

    private var webUiServerUrlState by mutableStateOf("")
    private var webUiPasswordState by mutableStateOf("")
    private var webUiLoggedInState by mutableStateOf(false)
    private var webUiConnectionStatusState by mutableStateOf<ConnectionStatusUi?>(null)

    // État pairing/relay — reflète directement viewModel.uiState (StateFlow), observé
    // via repeatOnLifecycle dans onViewCreated (voir observeRelayState()).
    private var relayPairedState by mutableStateOf(false)
    private var relayConnectionStatusState by mutableStateOf(RelayConnectionStatus.DISCONNECTED)

    /** Empêche de rouvrir le dialog cert relay en boucle tant que relayCertCheck reste non-null. */
    private var relayCertDialogShown = false

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        return ComposeView(requireContext()).apply {
            loadCurrentValues()
            populateTtsSubSelector(ttsProviderState)
            observeRelayState()
            loadHermesProfiles()
            setContent {
                HasanTheme {
                    SettingsScreen(
                        state = SettingsUiState(
                            serverUrl = serverUrlState,
                            authToken = authTokenState,
                            connectionStatus = connectionStatusState,
                            relayPaired = relayPairedState,
                            relayConnectionStatus = relayConnectionStatusState,
                            ttsProvider = ttsProviderState,
                            ttsProviderSubOptions = ttsSubOptionsState,
                            ttsSelectedSubOption = ttsSelectedSubOptionState,
                            nativeEngines = nativeEnginesState,
                            nativeSelectedEngine = nativeSelectedEngineState,
                            showNativeEngineSelector = ttsProviderState == SettingsManager.TTS_PROVIDER_NATIVE,
                            ttsEnabled = ttsEnabledState,
                            ttsSpeed = ttsSpeedState,
                            ttsVolume = ttsVolumeState,
                            wakeWordEnabled = wakeWordEnabledState,
                            wakeWordSensitivity = wakeWordSensitivityState,
                            wakeWordModels = SettingsManager.WAKE_WORD_MODELS,
                            wakeWordSelectedModel = wakeWordModelState,
                            hermesProfiles = hermesProfilesState,
                            webUiServerUrl = webUiServerUrlState,
                            webUiPassword = webUiPasswordState,
                            webUiLoggedIn = webUiLoggedInState,
                            webUiConnectionStatus = webUiConnectionStatusState,
                            aboutVersion = getString(R.string.settings_about_version),
                            aboutSubtitle = getString(R.string.settings_about_subtitle),
                            aboutWakeWord = getString(R.string.settings_about_wakeword),
                            aboutSttTts = getString(R.string.settings_about_stt_tts),
                            aboutFeatures = getString(R.string.settings_about_features)
                        ),
                        callbacks = SettingsCallbacks(
                            onServerUrlChange = { url ->
                                serverUrlState = url
                                settings.serverUrl = url
                            },
                            onAuthTokenChange = { token ->
                                authTokenState = token
                                settings.authToken = token
                            },
                            onTestConnection = { testConnection() },
                            onManageCerts = { showTrustedCertsDialog() },
                            onScanQrPairing = { (activity as? MainActivity)?.scanQrForPairing() },
                            onOpenToolsPermissions = { (activity as? MainActivity)?.openToolsPermissions() },
                            onTtsProviderChange = { provider ->
                                ttsProviderState = provider
                                viewModel.changeTtsProvider(provider)
                                populateTtsSubSelector(provider)
                            },
                            onTtsSubOptionChange = { value -> onTtsSubOptionSelected(value) },
                            onNativeEngineChange = { enginePkg -> onNativeEngineSelected(enginePkg) },
                            onTtsEnabledChange = { enabled ->
                                ttsEnabledState = enabled
                                settings.ttsEnabled = enabled
                            },
                            onTtsSpeedChange = { speed ->
                                ttsSpeedState = speed
                                viewModel.setTtsSpeed(speed)
                            },
                            onTtsVolumeChange = { volume ->
                                ttsVolumeState = volume
                                viewModel.setTtsVolume(volume)
                            },
                            onWakeWordEnabledChange = { enabled ->
                                wakeWordEnabledState = enabled
                                settings.wakeWordEnabled = enabled
                                if (enabled) viewModel.sendWakeWordIntent(HassanWakeWordService.ACTION_RESUME)
                                else viewModel.sendWakeWordIntent(HassanWakeWordService.ACTION_PAUSE)
                            },
                            onWakeWordSensitivityChange = { value ->
                                wakeWordSensitivityState = value
                                viewModel.setWakeWordSensitivity(value)
                            },
                            onWakeWordModelChange = { modelPath ->
                                wakeWordModelState = modelPath
                                viewModel.swapWakeWordModel(modelPath)
                            },
                            onProfileSelect = { profileName -> switchHermesProfile(profileName) },
                            onWebUiServerUrlChange = { url ->
                                webUiServerUrlState = url
                                settings.webUiServerUrl = url
                            },
                            onWebUiPasswordChange = { password -> webUiPasswordState = password },
                            onWebUiConnect = { connectToWebUi() },
                            onQuit = { (activity as? MainActivity)?.confirmQuit() },
                            onMenuClick = { (activity as? MainActivity)?.openDrawer() }
                        )
                    )
                }
            }
        }
    }

    // ─────────────────────────── Chargement des valeurs ───────────────────

    private fun loadCurrentValues() {
        wakeWordEnabledState = settings.wakeWordEnabled
        wakeWordSensitivityState = settings.wakeWordSensitivity
        wakeWordModelState = settings.wakeWordModel

        ttsEnabledState = settings.ttsEnabled
        ttsVolumeState = settings.ttsVolume
        ttsSpeedState = settings.ttsSpeed

        serverUrlState = settings.serverUrl
        authTokenState = settings.authToken

        webUiServerUrlState = settings.webUiServerUrl
        webUiLoggedInState = !settings.webUiSessionCookie.isNullOrBlank()

        ttsProviderState = settings.ttsProvider.ifBlank { viewModel.getCurrentTtsProvider() }
    }

    // ─────────────────────────── Voix TTS (natif Android / Edge TTS) ───────

    /** Reproduit populateProviderSubSelector/populateEdgeTtsVoiceSelector/populateNativeEngineSelector en Compose réactif. */
    private fun populateTtsSubSelector(provider: String) {
        if (provider == SettingsManager.TTS_PROVIDER_EDGE) {
            populateEdgeTtsVoiceOptions()
            nativeEnginesState = emptyList()
        } else {
            populateNativeEngineOptions()
        }
    }

    private fun populateEdgeTtsVoiceOptions() {
        val voices = SettingsManager.EDGE_TTS_VOICES
        val currentVoice = settings.ttsVoice.ifBlank { EdgeTtsEngine.DEFAULT_VOICE }
        ttsSubOptionsState = voices.map { it to buildEdgeVoiceLabel(it) }
        ttsSelectedSubOptionState = if (voices.contains(currentVoice)) currentVoice else voices.firstOrNull().orEmpty()
    }

    private fun buildEdgeVoiceLabel(voiceName: String): String =
        voiceName
            .substringAfter("-", voiceName)
            .substringAfter("-")
            .removeSuffix("Neural")
            .removeSuffix("Multilingual")
            .ifBlank { voiceName }

    private fun populateNativeEngineOptions() {
        val engines = viewModel.getAvailableTtsEngines()
        if (engines.isEmpty()) {
            nativeEnginesState = emptyList()
            ttsSubOptionsState = emptyList()
            return
        }
        val currentEngine = settings.ttsEngine.ifBlank { viewModel.getCurrentTtsEngine() }
        nativeEnginesState = engines.map { TtsEngineOption(it.name, it.label) }
        nativeSelectedEngineState = if (engines.any { it.name == currentEngine }) currentEngine else engines.first().name
        populateNativeVoiceOptions()
    }

    private fun populateNativeVoiceOptions() {
        val voices = viewModel.getAvailableTtsVoices()
        if (voices.isEmpty()) {
            ttsSubOptionsState = emptyList()
            return
        }
        val currentVoice = settings.ttsVoice
        ttsSubOptionsState = voices.map { it to buildNativeVoiceLabel(it) }
        if (voices.contains(currentVoice)) {
            ttsSelectedSubOptionState = currentVoice
        } else {
            val firstVoice = voices.first()
            ttsSelectedSubOptionState = firstVoice
            viewModel.changeTtsVoice(firstVoice)
        }
    }

    private fun buildNativeVoiceLabel(voiceName: String): String =
        voiceName
            .substringAfterLast("-x-")
            .replace("-", " ")
            .replaceFirstChar { it.uppercase() }
            .ifBlank { voiceName }

    private fun onTtsSubOptionSelected(value: String) {
        ttsSelectedSubOptionState = value
        viewModel.changeTtsVoice(value)
    }

    private fun onNativeEngineSelected(enginePackage: String) {
        nativeSelectedEngineState = enginePackage
        viewModel.changeTtsEngine(enginePackage)
        // Laisse le temps au moteur natif de changer avant de recharger ses voix
        // (même délai que l'ancien reloadNativeVoices() posté sur ttsEngineContainer).
        view?.postDelayed({ populateNativeVoiceOptions() }, 1500)
    }

    // ─────────────────────────── Profil Hermes ─────────────────────────────

    private fun loadHermesProfiles() {
        lifecycleScope.launch {
            hermesProfilesState = profilesClient.listProfiles()
        }
    }

    private fun switchHermesProfile(name: String) {
        lifecycleScope.launch {
            if (profilesClient.switchProfile(name)) {
                loadHermesProfiles()
            }
        }
    }

    // ─────────────────────────── hermes-webui (chat) ───────────────────────

    /**
     * Connexion manuelle à hermes-webui — alternative au pairing QR
     * (MainViewModel.pairFromQr) quand le scan n'est pas disponible ou
     * échoue. settings.webUiServerUrl est déjà à jour via
     * onWebUiServerUrlChange (écrit à chaque frappe, comme URL du serveur
     * relay) ; seul le mot de passe transite ici, jamais persisté par
     * SettingsManager (voir WebUiRestClient.login — seul le cookie de
     * session résultant est stocké).
     */
    private fun connectToWebUi() {
        val password = webUiPasswordState
        if (settings.webUiServerUrl.isBlank() || password.isBlank()) {
            webUiConnectionStatusState = ConnectionStatusUi(ok = false, message = "URL et mot de passe requis")
            return
        }
        webUiConnectionStatusState = ConnectionStatusUi(ok = false, message = "Connexion en cours…")
        lifecycleScope.launch {
            when (val result = webUiRestClient.login(password)) {
                is com.hasan.v1.webui.models.WebUiLoginResult.Ok -> {
                    webUiLoggedInState = true
                    webUiConnectionStatusState = ConnectionStatusUi(ok = true, message = "Connecté")
                    webUiPasswordState = ""
                    loadHermesProfiles()
                    viewModel.refreshWebUiLoginState()
                }
                is com.hasan.v1.webui.models.WebUiLoginResult.InvalidPassword ->
                    webUiConnectionStatusState = ConnectionStatusUi(ok = false, message = "Mot de passe incorrect")
                is com.hasan.v1.webui.models.WebUiLoginResult.RateLimited ->
                    webUiConnectionStatusState = ConnectionStatusUi(ok = false, message = "Trop de tentatives — réessayer plus tard")
                is com.hasan.v1.webui.models.WebUiLoginResult.NetworkError ->
                    webUiConnectionStatusState = ConnectionStatusUi(ok = false, message = "Connexion impossible : ${result.message}")
            }
        }
    }

    // ─────────────────────────── Pairing / relay (WebSocket) ──────────────

    /**
     * Reflète viewModel.uiState.relayPaired / relayConnectionStatus / relayCertCheck
     * dans l'état Compose local de ce Fragment (même pattern que la collecte
     * repeatOnLifecycle utilisée côté ConversationFragment.observeUiState()).
     * Le dialog cert relay reprend le style de showCertChangedDialog() /
     * showNewCertDialog() ci-dessous (dialog TOFU HTTP existant), pour le
     * nouveau flux relay (trustRelayCertAndRetry / dismissRelayCertCheck).
     */
    private fun observeRelayState() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uiState.collect { state ->
                    relayPairedState = state.relayPaired
                    relayConnectionStatusState = state.relayConnectionStatus

                    val certCheck = state.relayCertCheck
                    if (certCheck != null && !relayCertDialogShown) {
                        relayCertDialogShown = true
                        showRelayCertCheckDialog(certCheck)
                    } else if (certCheck == null) {
                        relayCertDialogShown = false
                    }
                }
            }
        }
    }

    /** Dialog TOFU pour le relay WebSocket — seul TOFU restant depuis le passage à 100% WSS. */
    private fun showRelayCertCheckDialog(certCheck: CertPinStore.CertCheckResult) {
        val rootUrl = com.hasan.v1.network.models.buildRootUrl(settings.serverUrl)
        when (certCheck) {
            is CertPinStore.CertCheckResult.NewCertificate -> {
                val formatted = certCheck.fingerprint.chunked(24).joinToString("\n")
                HasanDialog.confirm(
                    context = requireContext(),
                    title = "Certificat relay non reconnu",
                    message = "Relay : $rootUrl\n\nEmpreinte SHA-256 :\n$formatted\n\nFaire confiance à ce serveur relay ?",
                    confirmLabel = "Faire confiance",
                    cancelLabel = "Annuler",
                    onConfirm = { viewModel.trustRelayCertAndRetry(certCheck.fingerprint); relayCertDialogShown = false },
                    onCancel = { viewModel.dismissRelayCertCheck(); relayCertDialogShown = false }
                )
            }
            is CertPinStore.CertCheckResult.FingerprintMismatch -> {
                val storedFmt = certCheck.stored.chunked(24).joinToString("\n")
                val newFmt = certCheck.received.chunked(24).joinToString("\n")
                HasanDialog.confirm(
                    context = requireContext(),
                    title = "⚠ Certificat relay modifié",
                    message = "Le certificat du relay $rootUrl a changé.\n\nAncienne empreinte :\n$storedFmt\n\nNouvelle empreinte :\n$newFmt\n\nCela peut indiquer une attaque. Réinitialiser la confiance ?",
                    confirmLabel = "Faire confiance",
                    cancelLabel = "Bloquer",
                    destructive = true,
                    onConfirm = { viewModel.trustRelayCertAndRetry(certCheck.received); relayCertDialogShown = false },
                    onCancel = { viewModel.dismissRelayCertCheck(); relayCertDialogShown = false }
                )
            }
            // TrustedBySystem / KnownAndMatch ne déclenchent normalement pas cet état
            // (relayCertCheck n'est mis à jour que sur les cas nécessitant une action —
            // voir MainViewModel.certCheckEvents.collect), mais on ferme proprement si reçu.
            else -> {
                viewModel.dismissRelayCertCheck()
                relayCertDialogShown = false
            }
        }
    }

    // ─────────────────────────── Connexion ────────────────────────────────

    private fun testConnection() {
        connectionStatusState = ConnectionStatusUi(ok = false, message = "Test en cours (hermes-webui)…")

        lifecycleScope.launch {
            val result = viewModel.checkHealthViaRelay()
            handleHealthResult(result)
        }
    }

    /** Traite le résultat du health check hermes-webui (GET /health) et met à jour l'UI. */
    private fun handleHealthResult(result: WebUiHealthResult) {
        when (result) {
            is WebUiHealthResult.Ok -> {
                showConnectionStatus(ok = true, message = getString(R.string.settings_connection_ok))
            }
            is WebUiHealthResult.NetworkError -> {
                showConnectionStatus(ok = false, message = "${getString(R.string.settings_connection_fail)} : ${result.message}")
            }
            is WebUiHealthResult.ServerError -> {
                showConnectionStatus(ok = false, message = "${getString(R.string.settings_connection_fail)} : HTTP ${result.code}")
            }
        }
    }

    /** Met à jour le dot et le texte de statut de connexion. */
    private fun showConnectionStatus(ok: Boolean, message: String) {
        connectionStatusState = ConnectionStatusUi(ok = ok, message = message)
    }

    /**
     * Dialog de gestion des certificats de confiance.
     * Liste tous les serveurs approuvés avec leur fingerprint tronqué.
     * Bouton "Supprimer" sur chaque entrée pour révoquer la confiance.
     * Bouton "Tout effacer" en bas pour réinitialiser.
     */
    private fun showTrustedCertsDialog() {
        val certs = settings.getAllTrustedCerts()

        if (certs.isEmpty()) {
            HasanDialog.confirm(
                context = requireContext(),
                title = "Certificats de confiance",
                message = "Aucun certificat enregistré.\n\nLes certificats sont ajoutés automatiquement lors du premier test de connexion.",
                confirmLabel = "Fermer",
                cancelLabel = "Fermer",
                onConfirm = {},
                onCancel = {}
            )
            return
        }

        val entries = certs.entries.toList()
        val labels = entries.map { (_, fingerprint) ->
            val parts = fingerprint.split(":")
            if (parts.size > 8)
                "${parts.take(3).joinToString(":")}:…:${parts.takeLast(3).joinToString(":")}"
            else fingerprint
        }

        // Affiche la liste, puis au tap : dialog de révocation
        val listItems = labels + listOf("Tout effacer")
        HasanDialog.list(
            context = requireContext(),
            title = "Certificats de confiance (${certs.size})",
            items = listItems,
            onSelect = { index ->
                if (index == entries.size) {
                    HasanDialog.confirm(
                        context = requireContext(),
                        title = "Tout effacer",
                        message = "Supprimer tous les certificats de confiance ? Toutes les connexions demanderont une nouvelle approbation.",
                        confirmLabel = "Effacer",
                        cancelLabel = "Annuler",
                        destructive = true,
                        onConfirm = { settings.clearAllTrustedCerts() }
                    )
                } else {
                    val key = entries[index].key
                    val fingerprint = entries[index].value
                    HasanDialog.confirm(
                        context = requireContext(),
                        title = "Révoquer ce certificat ?",
                        message = "Empreinte : $fingerprint\n\nLa prochaine connexion à ce serveur demandera une nouvelle approbation.",
                        confirmLabel = "Supprimer",
                        cancelLabel = "Annuler",
                        destructive = true,
                        onConfirm = { settings.removeTrustedCertFingerprint(key) }
                    )
                }
            }
        )
    }

}
