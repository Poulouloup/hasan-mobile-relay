package com.hasan.v1

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import com.hasan.v1.databinding.FragmentSettingsBinding
import com.hasan.v1.db.HassanDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Fragment Paramètres — lecture/écriture via SettingsManager (EncryptedSharedPreferences).
 * Accessible depuis la BottomNavigationView, sans bouton retour.
 */
class SettingsFragment : Fragment() {

    private var _binding: FragmentSettingsBinding? = null
    private val binding get() = _binding!!

    private val viewModel: MainViewModel by activityViewModels()
    private val settings get() = viewModel.settings

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentSettingsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        loadCurrentValues()
        setupListeners()
        populateTtsEngineSelector()
        populateTtsVoices()
        populateWakeWordModelSelector()
    }

    // ─────────────────────────── Chargement des valeurs ───────────────────

    private fun loadCurrentValues() {
        val wakeEnabled = settings.wakeWordEnabled
        binding.switchWakeWord.isChecked = wakeEnabled
        updateWakeWordSwitchColor(wakeEnabled)
        binding.sliderSensitivity.value  = settings.wakeWordSensitivity

        val ttsEnabled = settings.ttsEnabled
        binding.switchTts.isChecked  = ttsEnabled
        updateTtsSwitchColor(ttsEnabled)
        binding.sliderVolume.value   = settings.ttsVolume
        binding.sliderSpeed.value    = settings.ttsSpeed
        binding.tvVolumeValue.text   = "${settings.ttsVolume.toInt()}%"
        binding.tvSpeedValue.text    = "%.1fx".format(settings.ttsSpeed)

        binding.etServerUrl.setText(settings.serverUrl)
        binding.etAuthToken.setText(settings.authToken)
    }

    private fun updateWakeWordSwitchColor(enabled: Boolean) {
        val thumbColor = if (enabled) R.color.hasan_accent else R.color.hasan_text_secondary
        val trackColor = if (enabled) R.color.hasan_accent_dim else R.color.hasan_border
        binding.switchWakeWord.thumbTintList = android.content.res.ColorStateList.valueOf(
            ContextCompat.getColor(requireContext(), thumbColor)
        )
        binding.switchWakeWord.trackTintList = android.content.res.ColorStateList.valueOf(
            ContextCompat.getColor(requireContext(), trackColor)
        )
    }

    private fun updateTtsSwitchColor(enabled: Boolean) {
        val thumbColor = if (enabled) R.color.hasan_accent else R.color.hasan_text_secondary
        val trackColor = if (enabled) R.color.hasan_accent_dim else R.color.hasan_border
        binding.switchTts.thumbTintList = android.content.res.ColorStateList.valueOf(
            ContextCompat.getColor(requireContext(), thumbColor)
        )
        binding.switchTts.trackTintList = android.content.res.ColorStateList.valueOf(
            ContextCompat.getColor(requireContext(), trackColor)
        )
    }

    // ─────────────────────────── Listeners ────────────────────────────────

    private fun setupListeners() {
        // Section Wake Word
        binding.switchWakeWord.setOnCheckedChangeListener { _, isChecked ->
            settings.wakeWordEnabled = isChecked
            updateWakeWordSwitchColor(isChecked)
            if (isChecked) viewModel.sendWakeWordIntent(HassanWakeWordService.ACTION_RESUME)
            else           viewModel.sendWakeWordIntent(HassanWakeWordService.ACTION_PAUSE)
        }

        binding.sliderSensitivity.addOnChangeListener { _, value, fromUser ->
            if (fromUser) viewModel.setWakeWordSensitivity(value)
        }

        // Section Voix
        binding.switchTts.setOnCheckedChangeListener { _, isChecked ->
            settings.ttsEnabled = isChecked
            updateTtsSwitchColor(isChecked)
        }

        binding.sliderVolume.addOnChangeListener { _, value, fromUser ->
            if (fromUser) {
                binding.tvVolumeValue.text = "${value.toInt()}%"
                viewModel.setTtsVolume(value)
            }
        }

        binding.sliderSpeed.addOnChangeListener { _, value, fromUser ->
            if (fromUser) {
                binding.tvSpeedValue.text = "%.1fx".format(value)
                viewModel.setTtsSpeed(value)
            }
        }

        // Section Connexion
        binding.btnTestConnection.setOnClickListener { testConnection() }
        binding.btnManageCerts.setOnClickListener { showTrustedCertsDialog() }

        binding.etServerUrl.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) saveConnectionSettings()
        }
        binding.etAuthToken.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) saveConnectionSettings()
        }

        // Section Conversation
        binding.btnClearHistory.setOnClickListener { confirmClearHistory() }
        binding.btnExportHistory.setOnClickListener { exportAllHistory() }

        // À propos
        binding.btnQuit.setOnClickListener { confirmQuit() }
    }

    // ─────────────────────────── Moteur TTS ───────────────────────────────

    private fun populateTtsEngineSelector() {
        binding.btnTtsNative.visibility      = View.GONE
        binding.btnTtsPremium.visibility     = View.GONE
        binding.ttsEngineContainer.visibility = View.VISIBLE

        val engines = viewModel.getAvailableTtsEngines()
        if (engines.isEmpty()) return

        val currentEngine = settings.ttsEngine.ifBlank { viewModel.getCurrentTtsEngine() }

        val radioGroup = RadioGroup(requireContext()).apply {
            orientation = RadioGroup.VERTICAL
        }

        var selectedId = View.NO_ID
        var firstId    = View.NO_ID

        engines.forEach { engine ->
            val radio = RadioButton(requireContext()).apply {
                id       = View.generateViewId()
                text     = engine.label
                tag      = engine.name
                textSize = 14f
                setTextColor(ContextCompat.getColor(requireContext(), R.color.hasan_text_primary))
            }
            radioGroup.addView(radio)
            if (firstId == View.NO_ID) firstId = radio.id
            if (engine.name == currentEngine) selectedId = radio.id
        }

        radioGroup.setOnCheckedChangeListener { group, checkedId ->
            val selected = group.findViewById<RadioButton>(checkedId) ?: return@setOnCheckedChangeListener
            val pkg = selected.tag as? String ?: return@setOnCheckedChangeListener
            viewModel.changeTtsEngine(pkg)
            // Recharge les voix disponibles pour le nouveau moteur (après un court délai d'init)
            binding.ttsEngineContainer.postDelayed({ reloadTtsVoices() }, 1500)
        }

        binding.ttsEngineContainer.addView(radioGroup)

        val idToCheck = if (selectedId != View.NO_ID) selectedId else firstId
        if (idToCheck != View.NO_ID) radioGroup.check(idToCheck)
    }

    private fun reloadTtsVoices() {
        // Supprime l'ancien RadioGroup des voix et recrée
        val container = binding.ttsEngineContainer
        // Garde uniquement le premier enfant (RadioGroup moteurs), retire les suivants
        while (container.childCount > 1) container.removeViewAt(1)
        populateTtsVoices()
    }

    // ─────────────────────────── Voix TTS ─────────────────────────────────

    private fun populateTtsVoices() {
        val voices = viewModel.getAvailableTtsVoices()
        if (voices.isEmpty()) return

        val currentVoice = settings.ttsVoice

        val radioGroup = RadioGroup(requireContext()).apply {
            orientation = RadioGroup.VERTICAL
        }

        var selectedId = View.NO_ID
        var firstId    = View.NO_ID

        voices.forEach { voice ->
            val label = buildVoiceLabel(voice)
            val radio = RadioButton(requireContext()).apply {
                id       = View.generateViewId()
                text     = label
                tag      = voice.name
                textSize = 14f
                setTextColor(ContextCompat.getColor(requireContext(), R.color.hasan_text_primary))
            }
            radioGroup.addView(radio)
            if (firstId == View.NO_ID) firstId = radio.id
            if (voice.name == currentVoice) selectedId = radio.id
        }

        radioGroup.setOnCheckedChangeListener { group, checkedId ->
            val selected = group.findViewById<RadioButton>(checkedId) ?: return@setOnCheckedChangeListener
            val voiceName = selected.tag as? String ?: return@setOnCheckedChangeListener
            viewModel.changeTtsVoice(voiceName)
        }

        // Séparateur visuel entre moteur et voix
        val divider = View(requireContext()).apply {
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT, 1
            ).also { it.topMargin = resources.getDimensionPixelSize(R.dimen.spacing_m) }
            setBackgroundColor(ContextCompat.getColor(requireContext(), R.color.hasan_border))
        }
        binding.ttsEngineContainer.addView(divider)
        binding.ttsEngineContainer.addView(radioGroup)

        val idToCheck = if (selectedId != View.NO_ID) selectedId else firstId
        if (idToCheck != View.NO_ID) {
            radioGroup.check(idToCheck)
            if (selectedId == View.NO_ID) {
                val firstVoice = radioGroup.findViewById<RadioButton>(firstId)?.tag as? String
                if (firstVoice != null) viewModel.changeTtsVoice(firstVoice)
            }
        }
    }

    private fun buildVoiceLabel(voice: android.speech.tts.Voice): String {
        val lang = voice.locale.displayLanguage
        val country = voice.locale.displayCountry.takeIf { it.isNotBlank() }
        val location = if (country != null) "$lang ($country)" else lang
        // Extrait un nom lisible depuis le nom technique (ex: "fr-fr-x-fra-local" → "fra local")
        val shortName = voice.name
            .substringAfterLast("-x-")
            .replace("-", " ")
            .replaceFirstChar { it.uppercase() }
            .ifBlank { voice.name }
        return "$shortName · $location"
    }

    // ─────────────────────────── Sélecteur modèle wake word ──────────────

    private fun populateWakeWordModelSelector() {
        val group = binding.rgWakeWordModel
        val currentModel = settings.wakeWordModel
        var selectedId = View.NO_ID

        SettingsManager.WAKE_WORD_MODELS.forEach { modelPath ->
            val radio = RadioButton(requireContext()).apply {
                id        = View.generateViewId()
                text      = modelPath.removeSuffix(".onnx")
                tag       = modelPath
                textSize  = 14f
                setTextColor(ContextCompat.getColor(requireContext(), R.color.hasan_text_primary))
            }
            group.addView(radio)
            if (modelPath == currentModel) selectedId = radio.id
        }

        group.setOnCheckedChangeListener { grp, checkedId ->
            val selected = grp.findViewById<RadioButton>(checkedId) ?: return@setOnCheckedChangeListener
            val modelPath = selected.tag as? String ?: return@setOnCheckedChangeListener
            viewModel.swapWakeWordModel(modelPath)
        }

        // Sélection après ajout + listener pour garantir une seule case cochée
        if (selectedId != View.NO_ID) group.check(selectedId)
        else if (group.childCount > 0) group.check(
            (group.getChildAt(0) as? RadioButton)?.id ?: View.NO_ID
        )
    }

    // ─────────────────────────── Connexion ────────────────────────────────

    private fun saveConnectionSettings() {
        val url   = binding.etServerUrl.text?.toString()?.trim() ?: ""
        val token = binding.etAuthToken.text?.toString()?.trim() ?: ""
        if (url.isNotBlank())   settings.serverUrl  = url
        if (token.isNotBlank()) settings.authToken  = token
    }

    private fun testConnection() {
        saveConnectionSettings()
        val healthUrl = HermesApiClient.buildHealthUrl(settings.serverUrl)
        binding.connectionStatusLayout.visibility = View.VISIBLE
        binding.tvConnectionResult.text = "Test en cours… ($healthUrl)"
        binding.tvConnectionResult.setTextColor(
            ContextCompat.getColor(requireContext(), R.color.hasan_text_secondary)
        )

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
        val dotColor = if (ok) R.color.hasan_success else R.color.hasan_error
        binding.viewTestDot.backgroundTintList =
            android.content.res.ColorStateList.valueOf(
                ContextCompat.getColor(requireContext(), dotColor)
            )
        binding.tvConnectionResult.text = message
        binding.tvConnectionResult.setTextColor(
            ContextCompat.getColor(requireContext(), if (ok) R.color.hasan_success else R.color.hasan_error)
        )
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
        // Formate le fingerprint en groupes de 8 pour la lisibilité
        val formatted = fingerprint.chunked(24).joinToString("\n")
        AlertDialog.Builder(requireContext())
            .setTitle("Certificat non reconnu")
            .setMessage(
                "Serveur : $rootUrl\n\n" +
                "Empreinte SHA-256 :\n$formatted\n\n" +
                "Faire confiance à ce serveur ?"
            )
            .setPositiveButton("Faire confiance") { _, _ -> onApprove() }
            .setNegativeButton("Annuler") { _, _ -> onDeny() }
            .setCancelable(false)
            .show()
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
        AlertDialog.Builder(requireContext())
            .setTitle("⚠️ Certificat modifié")
            .setMessage(
                "Le certificat du serveur $rootUrl a changé.\n\n" +
                "Ancienne empreinte :\n$storedFmt\n\n" +
                "Nouvelle empreinte :\n$newFmt\n\n" +
                "Cela peut indiquer une attaque. Réinitialiser la confiance ?"
            )
            .setPositiveButton("Faire confiance au nouveau") { _, _ -> onTrustNew() }
            .setNegativeButton("Bloquer") { _, _ -> onDeny() }
            .setCancelable(false)
            .show()
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
            AlertDialog.Builder(requireContext())
                .setTitle("Certificats de confiance")
                .setMessage("Aucun certificat enregistré.\n\nLes certificats sont ajoutés automatiquement lors du premier test de connexion.")
                .setPositiveButton("Fermer", null)
                .show()
            return
        }

        // Construit la liste : "trusted_cert_abc123" → "10.200.0.2 — A3:4F:2B:..."
        val entries = certs.entries.toList()
        val labels = entries.map { (_, fingerprint) ->
            // Affiche les 3 premiers et 3 derniers groupes du fingerprint
            val parts = fingerprint.split(":")
            val preview = if (parts.size > 8)
                "${parts.take(3).joinToString(":")}:…:${parts.takeLast(3).joinToString(":")}"
            else fingerprint
            preview
        }.toTypedArray()

        AlertDialog.Builder(requireContext())
            .setTitle("Certificats de confiance (${certs.size})")
            .setItems(labels) { _, index ->
                // Clic sur une entrée → proposer de révoquer
                val key = entries[index].key
                val fingerprint = entries[index].value
                val preview = labels[index]
                AlertDialog.Builder(requireContext())
                    .setTitle("Révoquer ce certificat ?")
                    .setMessage("Empreinte : $fingerprint\n\nLa prochaine connexion à ce serveur demandera une nouvelle approbation.")
                    .setPositiveButton("Supprimer") { _, _ ->
                        settings.removeTrustedCertFingerprint(key)
                    }
                    .setNegativeButton("Annuler", null)
                    .show()
            }
            .setNeutralButton("Tout effacer") { _, _ ->
                AlertDialog.Builder(requireContext())
                    .setMessage("Supprimer tous les certificats de confiance ? Toutes les connexions demanderont une nouvelle approbation.")
                    .setPositiveButton("Effacer") { _, _ -> settings.clearAllTrustedCerts() }
                    .setNegativeButton("Annuler", null)
                    .show()
            }
            .setNegativeButton("Fermer", null)
            .show()
    }

    // ─────────────────────────── Données ──────────────────────────────────

    private fun confirmClearHistory() {
        AlertDialog.Builder(requireContext())
            .setMessage(getString(R.string.settings_clear_history_confirm))
            .setPositiveButton(getString(R.string.dialog_confirm)) { _, _ ->
                lifecycleScope.launch {
                    HassanDatabase.getInstance(requireContext())
                        .conversationDao()
                        .deleteAll()
                    viewModel.startNewConversation()
                }
            }
            .setNegativeButton(getString(R.string.dialog_cancel), null)
            .show()
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
        AlertDialog.Builder(requireContext())
            .setMessage(getString(R.string.settings_quit_confirm))
            .setPositiveButton(getString(R.string.dialog_confirm)) { _, _ ->
                // Arrête le service wake word (foreground + notification persistante)
                requireContext().stopService(
                    android.content.Intent(requireContext(), HassanWakeWordService::class.java)
                )
                // Ferme l'activité et tue le processus proprement
                requireActivity().finishAndRemoveTask()
                android.os.Process.killProcess(android.os.Process.myPid())
            }
            .setNegativeButton(getString(R.string.dialog_cancel), null)
            .show()
    }

    override fun onDestroyView() {
        _binding = null
        super.onDestroyView()
    }
}
