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
import com.hasan.v1.network.RelayConnectionStatus
import com.hasan.v1.webui.WebUiCallResult
import com.hasan.v1.webui.WebUiClientHolder
import com.hasan.v1.webui.WebUiMcpClient
import com.hasan.v1.webui.WebUiProfilesClient
import com.hasan.v1.webui.WebUiSkillsClient
import com.hasan.v1.webui.models.HermesProfile
import com.hasan.v1.webui.models.McpServer
import com.hasan.v1.ui.screens.ConnectionStatusUi
import com.hasan.v1.ui.screens.SettingsCallbacks
import com.hasan.v1.ui.screens.SettingsScreen
import com.hasan.v1.ui.screens.SettingsUiState
import com.hasan.v1.ui.screens.TtsEngineOption
import com.hasan.v1.ui.theme.HasanTheme
import com.hasan.v1.utils.BatteryOptimizationUtils
import kotlinx.coroutines.launch

/**
 * Fragment Paramètres — lecture/écriture via SettingsManager (EncryptedSharedPreferences).
 * Accessible depuis le drawer (voir HasanDrawer.kt), sans bouton retour.
 *
 * L'UI est un unique ComposeView (SettingsScreen) — ce Fragment reste le seul
 * responsable de la logique métier (persistance SettingsManager) et pilote
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
    private val mcpClient by lazy { WebUiMcpClient(webUiRestClient) }
    private val skillsClient by lazy { WebUiSkillsClient(webUiRestClient) }

    // ─────────────────────────── État Compose ──────────────────────────────
    // mutableStateOf plutôt que StateFlow ici : SettingsManager (SharedPreferences)
    // n'est pas observable nativement, ce Fragment reste la seule source de vérité
    // qui pousse les changements vers l'état Compose après chaque action utilisateur.


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
    private var batteryOptimizationIgnoredState by mutableStateOf(false)

    private var hermesProfilesState by mutableStateOf<List<HermesProfile>>(emptyList())
    private var mcpServersState by mutableStateOf<List<McpServer>>(emptyList())

    private var webUiServerUrlState by mutableStateOf("")
    private var webUiPasswordState by mutableStateOf("")
    private var webUiLoggedInState by mutableStateOf(false)
    private var webUiConnectionStatusState by mutableStateOf<ConnectionStatusUi?>(null)

    // État pairing/relay — reflète directement viewModel.uiState (StateFlow), observé
    // via repeatOnLifecycle dans onViewCreated (voir observeRelayState()).
    private var relayPairedState by mutableStateOf(false)
    private var relayEnabledState by mutableStateOf(true)
    private var relayConnectionStatusState by mutableStateOf(RelayConnectionStatus.DISCONNECTED)
    private var relayManualUrlState by mutableStateOf("")
    private var relayManualCodeState by mutableStateOf("")
    private var relayErrorMessageState by mutableStateOf<String?>(null)
    private var relayConnectionErrorState by mutableStateOf<String?>(null)
    private var webUiHealthErrorState by mutableStateOf<String?>(null)
    private var relayDeviceLabelState by mutableStateOf("")

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        return ComposeView(requireContext()).apply {
            loadCurrentValues()
            populateTtsSubSelector(ttsProviderState)
            observeRelayState()
            loadHermesProfiles()
            loadMcpServers()
            setContent {
                HasanTheme {
                    SettingsScreen(
                        state = SettingsUiState(
                            relayPaired = relayPairedState,
                            relayEnabled = relayEnabledState,
                            relayConnectionStatus = relayConnectionStatusState,
                            relayManualUrl = relayManualUrlState,
                            relayManualCode = relayManualCodeState,
                            relayErrorMessage = relayErrorMessageState,
                            relayConnectionError = relayConnectionErrorState,
                            webUiHealthError = webUiHealthErrorState,
                            relayDeviceLabel = relayDeviceLabelState,
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
                            batteryOptimizationIgnored = batteryOptimizationIgnoredState,
                            hermesProfiles = hermesProfilesState,
                            mcpServers = mcpServersState,
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
                            onScanQrPairing = { (activity as? MainActivity)?.scanQrForPairing() },
                            onRelayManualUrlChange = { url -> relayManualUrlState = url },
                            onRelayManualCodeChange = { code -> relayManualCodeState = code },
                            onRelayDeviceLabelChange = { label ->
                                relayDeviceLabelState = label
                                settings.relayDeviceLabel = label
                            },
                            onDismissRelayError = { viewModel.clearError() },
                            onRelayToggle = { enabled -> onRelayToggle(enabled) },
                            onDisconnectWebUi = { viewModel.disconnectWebUi() },
                            onAuthRequiredForSecretEdit = { onSuccess -> requestSecretEditAuth(onSuccess) },
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
                            onRequestBatteryExemption = {
                                startActivity(BatteryOptimizationUtils.buildRequestExemptionIntent(requireContext()))
                            },
                            onProfileSelect = { profileName -> switchHermesProfile(profileName) },
                            onMcpToggle = { name, enabled -> toggleMcpServer(name, enabled) },
                            onWebUiServerUrlChange = { url ->
                                webUiServerUrlState = url
                                settings.webUiServerUrl = url
                            },
                            onWebUiPasswordChange = { password -> webUiPasswordState = password },
                            onWebUiConnect = { connectToWebUi() },
                            onOpenLogs = { (activity as? MainActivity)?.openLogs() },
                            onMenuClick = { (activity as? MainActivity)?.openDrawer() }
                        )
                    )
                }
            }
        }
    }

    // ─────────────────────────── Chargement des valeurs ───────────────────

    override fun onResume() {
        super.onResume()
        // Rafraîchit l'état batterie au retour d'un intent système (exemption accordée/refusée)
        // ou d'un aller-retour manuel dans les paramètres Android.
        batteryOptimizationIgnoredState = BatteryOptimizationUtils.isIgnoringBatteryOptimizations(requireContext())
    }

    private fun loadCurrentValues() {
        wakeWordEnabledState = settings.wakeWordEnabled
        wakeWordSensitivityState = settings.wakeWordSensitivity
        wakeWordModelState = settings.wakeWordModel
        batteryOptimizationIgnoredState = BatteryOptimizationUtils.isIgnoringBatteryOptimizations(requireContext())

        ttsEnabledState = settings.ttsEnabled
        ttsVolumeState = settings.ttsVolume
        ttsSpeedState = settings.ttsSpeed

        webUiServerUrlState = settings.webUiServerUrl
        webUiLoggedInState = !settings.webUiSessionCookie.isNullOrBlank()

        relayDeviceLabelState = settings.relayDeviceLabel

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

    /**
     * Charge les profils, puis corrige le `skill_count` du profil actif à
     * partir de GET /api/skills.
     *
     * Contournement d'un bug serveur (hermes-webui) : le `skill_count` de
     * GET /api/profiles est calculé par `_compute_profile_skills_stats`
     * (api/profiles.py), qui ne scanne que `<profil>/skills` et ignore les
     * `external_dirs` déclarés dans config.yaml. GET /api/skills, lui,
     * parcourt bien tous les répertoires de recherche — les deux écrans
     * affichaient donc des nombres incompatibles pour le même profil
     * (11 en Réglages contre 834 dans Skills, mesuré sur le VPS).
     *
     * Ne corrige que le profil ACTIF : /api/skills est relatif au profil
     * courant côté serveur, son total ne dit rien des autres profils. Les
     * profils inactifs gardent la valeur serveur, faute de mieux — un
     * nombre sous-évalué reste préférable à un nombre emprunté à un autre
     * profil. En cas d'échec de l'appel, on conserve simplement la valeur
     * serveur (l'écran reste fonctionnel, cf. issue #4).
     */
    private fun loadHermesProfiles() {
        lifecycleScope.launch {
            val profiles = profilesClient.listProfiles()
            hermesProfilesState = profiles
            if (profiles.none { it.isActive }) return@launch
            val realCount = (skillsClient.listSkills() as? WebUiCallResult.Ok)?.value?.size
                ?: return@launch
            hermesProfilesState = profiles.map {
                if (it.isActive) it.copy(skillCount = realCount) else it
            }
        }
    }

    // ─────────────────────────── Serveurs MCP ──────────────────────────────

    private fun loadMcpServers() {
        lifecycleScope.launch {
            mcpServersState = mcpClient.listServers()
        }
    }

    /** Optimiste (bascule locale immédiate) — resynchronisé depuis le serveur en cas d'échec. */
    private fun toggleMcpServer(name: String, enabled: Boolean) {
        mcpServersState = mcpServersState.map { if (it.name == name) it.copy(enabled = enabled) else it }
        lifecycleScope.launch {
            if (!mcpClient.setEnabled(name, enabled)) {
                loadMcpServers()
            }
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
     *
     * Gère aussi le pairing du relay depuis les mêmes champs de la config
     * manuelle (URL + code) si le relay n'est pas déjà appairé — un seul
     * bouton "Se connecter" pour les deux connexions, plus besoin du bouton
     * "Appairer manuellement" séparé (retiré de l'accordéon). Les deux
     * connexions sont indépendantes (systèmes distincts, pas de dépendance
     * d'ordre) : le pairing relay ne bloque jamais la tentative webui, et
     * inversement.
     */
    private fun connectToWebUi() {
        if (!relayPairedState && relayManualUrlState.isNotBlank() && relayManualCodeState.isNotBlank()) {
            viewModel.pairManually(relayManualUrlState.trim(), relayManualCodeState.trim())
        }

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

    /**
     * Switch relay (bloc statut connexions) — OFF est libre (pause simple,
     * pas de dépairing, voir MainViewModel.setRelayEnabled). ON exige une
     * authentification biométrique/PIN de l'appareil AVANT de reconnecter :
     * le relay donne accès à des capacités sensibles (SMS, localisation).
     * Le switch Compose est contrôlé par relayEnabledState (StateFlow), donc
     * en cas d'échec/annulation il retombe naturellement à OFF sans action
     * supplémentaire ici.
     */
    private fun onRelayToggle(enabled: Boolean) {
        if (!enabled) {
            viewModel.setRelayEnabled(false)
            return
        }
        lifecycleScope.launch {
            val ok = com.hasan.v1.auth.BiometricAuthHelper.authenticate(
                activity = requireActivity() as MainActivity,
                title = "Activer le relay",
                subtitle = "Actions téléphone (SMS, localisation)"
            )
            if (ok) viewModel.confirmRelayEnable()
            // Sinon : rien à faire, relayEnabledState reste à false (jamais mis à jour côté ViewModel).
        }
    }

    /**
     * Protège l'édition des champs secrets de la config manuelle (mot de passe
     * webui, code de pairing) — valeurs jusqu'ici visibles en clair au clic du
     * crayon. Même authentification que le switch relay ; [onSuccess] fait
     * passer le champ en mode édition côté Compose.
     */
    private fun requestSecretEditAuth(onSuccess: () -> Unit) {
        lifecycleScope.launch {
            val ok = com.hasan.v1.auth.BiometricAuthHelper.authenticate(
                activity = requireActivity() as MainActivity,
                title = "Modifier une valeur sensible",
                subtitle = "Mot de passe ou code de pairing"
            )
            if (ok) onSuccess()
        }
    }

    // ─────────────────────────── Pairing / relay (WebSocket) ──────────────

    /**
     * Reflète viewModel.uiState.relayPaired / relayConnectionStatus / erreurs de
     * connexion dans l'état Compose local de ce Fragment (même pattern que la
     * collecte repeatOnLifecycle utilisée côté ConversationFragment.observeUiState()).
     */
    private fun observeRelayState() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uiState.collect { state ->
                    relayPairedState = state.relayPaired
                    relayEnabledState = state.relayEnabled
                    relayConnectionStatusState = state.relayConnectionStatus
                    relayErrorMessageState = state.relayErrorMessage
                    relayConnectionErrorState = state.relayConnectionError
                    webUiHealthErrorState = state.webUiHealthError

                    // Un pairing QR réussi met à jour state.webUiLoggedIn directement
                    // (contournant connectToWebUi()/son bouton "Se connecter"), mais
                    // ne touche jamais webUiConnectionStatusState — un message
                    // d'erreur affiché avant le scan (ex: "URL et mot de passe
                    // requis" suite à un tap sur "Se reconnecter" prématuré) restait
                    // donc affiché indéfiniment par-dessus un état pourtant connecté
                    // (voir SettingsScreen : webUiConnectionStatus?.message a priorité
                    // sur le fallback basé sur webUiLoggedIn). On efface ce message
                    // périmé et on recharge profils/MCP dès que l'état passe à connecté.
                    if (state.webUiLoggedIn && !webUiLoggedInState) {
                        webUiConnectionStatusState = null
                        loadHermesProfiles()
                        loadMcpServers()
                    }
                    webUiLoggedInState = state.webUiLoggedIn
                }
            }
        }
    }
}
