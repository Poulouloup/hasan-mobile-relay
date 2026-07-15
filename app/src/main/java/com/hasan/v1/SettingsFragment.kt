package com.hasan.v1

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.runtime.collectAsState
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
import com.hasan.v1.db.HassanDatabase
import com.hasan.v1.db.HermesSession
import com.hasan.v1.network.RelayConnectionStatus
import com.hasan.v1.ui.screens.ConnectionStatusUi
import com.hasan.v1.ui.screens.SettingsCallbacks
import com.hasan.v1.ui.screens.SettingsScreen
import com.hasan.v1.ui.screens.SettingsUiState
import com.hasan.v1.ui.screens.TtsEngineOption
import com.hasan.v1.ui.theme.HasanTheme
import com.hasan.v1.utils.HasanDialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Fragment Paramètres — lecture/écriture via SettingsManager (EncryptedSharedPreferences).
 * Accessible depuis la BottomNavigationView, sans bouton retour.
 *
 * L'UI est un unique ComposeView (SettingsScreen) — ce Fragment reste le seul
 * responsable de la logique métier (persistance SettingsManager, TOFU, sessions,
 * export, quit) et pilote l'état Compose via des mutableStateOf recomposés à
 * chaque changement, exactement comme l'ancien binding impératif mais sans vues XML.
 */
class SettingsFragment : Fragment() {

    private val viewModel: MainViewModel by activityViewModels()
    private val settings get() = viewModel.settings

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
            setContent {
                HasanTheme {
                    val sessions by viewModel.sessions.collectAsState()
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
                            sessions = sessions,
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
                            onExportHistory = { exportAllHistory() },
                            onNewSession = { createNewSession() },
                            onSessionTap = { session -> viewModel.activateSession(session) },
                            onSessionLongPress = { session -> showSessionMenu(session) },
                            onQuit = { confirmQuit() }
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

    /** Dialog TOFU pour le relay WebSocket — équivalent showNewCertDialog/showCertChangedDialog pour ce transport. */
    private fun showRelayCertCheckDialog(certCheck: CertPinStore.CertCheckResult) {
        val rootUrl = HermesApiClient.buildRootUrl(settings.serverUrl)
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
        val healthUrl = HermesApiClient.buildHealthUrl(settings.serverUrl)
        connectionStatusState = ConnectionStatusUi(ok = false, message = "Test en cours… ($healthUrl)")

        lifecycleScope.launch {
            // Crée le client avec les settings actuels (incluant les certs de confiance)
            val client = HermesApiClient(
                HermesConfig(settings.serverUrl, settings.authToken, settings.effectiveModel()),
                settings
            )
            val result = withContext(Dispatchers.IO) { client.checkHealth() }
            handleHealthResult(result, client)
        }
    }

    /**
     * Traite le résultat du health check et met à jour l'UI.
     * Gère les cas TOFU : dialog d'approbation ou alerte de changement de certificat.
     */
    private fun handleHealthResult(result: HealthResult, client: HermesApiClient) {
        when (result) {
            is HealthResult.Ok -> {
                showConnectionStatus(ok = true, message = getString(R.string.settings_connection_ok))
            }
            is HealthResult.NetworkError -> {
                showConnectionStatus(ok = false, message = "${getString(R.string.settings_connection_fail)} : ${result.message}")
            }
            is HealthResult.ServerError -> {
                showConnectionStatus(ok = false, message = "${getString(R.string.settings_connection_fail)} : HTTP ${result.code}")
            }
            is HealthResult.NeedsCertApproval -> {
                // Premier contact avec ce serveur — demander l'approbation
                showConnectionStatus(ok = false, message = "Certificat inconnu — approbation requise")
                showNewCertDialog(
                    serverUrl = settings.serverUrl,
                    fingerprint = result.fingerprint,
                    onApprove = {
                        client.trustCertificate(result.fingerprint)
                        // Reteste après approbation pour confirmer
                        lifecycleScope.launch {
                            val retry = withContext(Dispatchers.IO) { client.checkHealth() }
                            handleHealthResult(retry, client)
                        }
                    },
                    onDeny = {
                        showConnectionStatus(ok = false, message = "Connexion annulée")
                    }
                )
            }
            is HealthResult.CertChanged -> {
                // Certificat changé — alerte bloquante
                showConnectionStatus(ok = false, message = "⚠️ Certificat du serveur modifié !")
                showCertChangedDialog(
                    serverUrl = settings.serverUrl,
                    storedFingerprint = result.stored,
                    newFingerprint = result.received,
                    onTrustNew = {
                        client.revokeTrust()
                        client.trustCertificate(result.received)
                        lifecycleScope.launch {
                            val retry = withContext(Dispatchers.IO) { client.checkHealth() }
                            handleHealthResult(retry, client)
                        }
                    },
                    onDeny = {
                        showConnectionStatus(ok = false, message = "Connexion bloquée — certificat non reconnu")
                    }
                )
            }
        }
    }

    /** Met à jour le dot et le texte de statut de connexion. */
    private fun showConnectionStatus(ok: Boolean, message: String) {
        connectionStatusState = ConnectionStatusUi(ok = ok, message = message)
    }

    /**
     * Dialog TOFU — premier contact avec un serveur inconnu.
     * Affiche le serveur et le fingerprint SHA-256 pour que l'utilisateur
     * puisse vérifier visuellement avant d'accepter.
     */
    private fun showNewCertDialog(
        serverUrl: String,
        fingerprint: String,
        onApprove: () -> Unit,
        onDeny: () -> Unit
    ) {
        val rootUrl = HermesApiClient.buildRootUrl(serverUrl)
        val formatted = fingerprint.chunked(24).joinToString("\n")
        HasanDialog.confirm(
            context = requireContext(),
            title = "Certificat non reconnu",
            message = "Serveur : $rootUrl\n\nEmpreinte SHA-256 :\n$formatted\n\nFaire confiance à ce serveur ?",
            confirmLabel = "Faire confiance",
            cancelLabel = "Annuler",
            onConfirm = onApprove,
            onCancel = onDeny
        )
    }

    /**
     * Dialog d'alerte — le certificat du serveur a changé depuis la dernière connexion.
     * Affiche les deux empreintes pour comparaison manuelle.
     */
    private fun showCertChangedDialog(
        serverUrl: String,
        storedFingerprint: String,
        newFingerprint: String,
        onTrustNew: () -> Unit,
        onDeny: () -> Unit
    ) {
        val rootUrl = HermesApiClient.buildRootUrl(serverUrl)
        val storedFmt = storedFingerprint.chunked(24).joinToString("\n")
        val newFmt = newFingerprint.chunked(24).joinToString("\n")
        HasanDialog.confirm(
            context = requireContext(),
            title = "⚠ Certificat modifié",
            message = "Le certificat du serveur $rootUrl a changé.\n\nAncienne empreinte :\n$storedFmt\n\nNouvelle empreinte :\n$newFmt\n\nCela peut indiquer une attaque. Réinitialiser la confiance ?",
            confirmLabel = "Faire confiance",
            cancelLabel = "Bloquer",
            destructive = true,
            onConfirm = onTrustNew,
            onCancel = onDeny
        )
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

    // ───────────────────────────── Sessions ──────────────────────────────────

    private fun createNewSession() {
        val dateStr = java.text.SimpleDateFormat("d MMMM", java.util.Locale.FRENCH).format(java.util.Date())
        showSessionNameDialog("Nouvelle session", "Session du $dateStr") { name ->
            viewModel.createSession(name)
        }
    }

    private fun showSessionMenu(session: HermesSession) {
        HasanDialog.list(
            context = requireContext(),
            title = session.name,
            items = listOf("Renommer", "Supprimer"),
            onSelect = { index ->
                when (index) {
                    0 -> showSessionNameDialog("Renommer", session.name) { name ->
                        viewModel.renameSession(session, name)
                    }
                    1 -> HasanDialog.confirm(
                        context = requireContext(),
                        message = if (session.isActive)
                            "\"${session.name}\" est active.\nUne nouvelle session sera créée automatiquement."
                        else
                            "Supprimer \"${session.name}\" ?",
                        confirmLabel = "Supprimer",
                        cancelLabel = "Annuler",
                        destructive = true,
                        onConfirm = { viewModel.deleteSession(session) }
                    )
                }
            }
        )
    }

    private fun showSessionNameDialog(title: String, default: String, onConfirm: (String) -> Unit) {
        HasanDialog.input(
            context = requireContext(),
            title = title,
            default = default,
            hint = "Nom de la session",
            onConfirm = { name -> if (name.isNotBlank()) onConfirm(name) }
        )
    }

    private fun exportAllHistory() {
        lifecycleScope.launch {
            val db = HassanDatabase.getInstance(requireContext())
            val conversations = db.conversationDao().getAllOnce()
            val sb = StringBuilder().apply {
                appendLine("=== Historique Hasan ===")
                appendLine()
                conversations.forEach { conv ->
                    appendLine("--- ${conv.title} ---")
                    db.messageDao().getMessagesForConversationOnce(conv.id).forEach { msg ->
                        val prefix = if (msg.role == "user") "Vous" else "Hasan"
                        appendLine("$prefix : ${msg.content}")
                    }
                    appendLine()
                }
            }

            val intent = android.content.Intent(android.content.Intent.ACTION_CREATE_DOCUMENT).apply {
                addCategory(android.content.Intent.CATEGORY_OPENABLE)
                type = "text/plain"
                putExtra(android.content.Intent.EXTRA_TITLE, "hasan_historique.txt")
                putExtra(android.content.Intent.EXTRA_TEXT, sb.toString())
            }
            startActivity(android.content.Intent.createChooser(intent, "Exporter l'historique"))
        }
    }

    private fun confirmQuit() {
        HasanDialog.confirm(
            context = requireContext(),
            message = getString(R.string.settings_quit_confirm),
            confirmLabel = getString(R.string.dialog_confirm),
            cancelLabel = getString(R.string.dialog_cancel),
            onConfirm = {
                viewModel.stopTts()
                val context = requireContext()

                // Annule la notification persistante immédiatement — les ACTION_STOP
                // sont asynchrones et killProcess() peut intervenir avant leur traitement.
                val nm = context.getSystemService(android.app.NotificationManager::class.java)
                nm.cancelAll()

                context.stopService(android.content.Intent(context, HassanWakeWordService::class.java))
                context.stopService(android.content.Intent(context, HassanNotificationService::class.java))

                requireActivity().finishAndRemoveTask()
                android.os.Process.killProcess(android.os.Process.myPid())
            }
        )
    }
}
