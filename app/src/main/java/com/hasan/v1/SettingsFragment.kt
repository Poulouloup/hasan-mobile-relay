package com.hasan.v1

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.hasan.v1.utils.HasanDialog
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.hasan.v1.databinding.FragmentSettingsBinding
import com.hasan.v1.databinding.ItemSessionBinding
import com.hasan.v1.db.HassanDatabase
import com.hasan.v1.db.HermesSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Fragment Paramètres — lecture/écriture via SettingsManager (EncryptedSharedPreferences).
 * Accessible depuis la BottomNavigationView, sans bouton retour.
 */
class SettingsFragment : Fragment() {

    private var _binding: FragmentSettingsBinding? = null
    private val binding get() = _binding!!

    private val viewModel: MainViewModel by activityViewModels()
    private val settings get() = viewModel.settings

    private lateinit var sessionAdapter: InlineSessionAdapter

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
        setupSessionsList()
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

        // Section Sessions
        binding.btnNewSession.setOnClickListener { createNewSession() }

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

    // ─────────────────────────── Outils (toolsets) ────────────────────────

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

    private fun setupSessionsList() {
        sessionAdapter = InlineSessionAdapter(
            onTap = { session -> viewModel.activateSession(session) },
            onLongPress = { session -> showSessionMenu(session) }
        )
        binding.rvSessions.layoutManager = LinearLayoutManager(requireContext())
        binding.rvSessions.adapter = sessionAdapter

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.sessions.collect { sessions ->
                    sessionAdapter.submitList(sessions)
                    binding.rvSessions.visibility = if (sessions.isEmpty()) View.GONE else View.VISIBLE
                    binding.tvNoSessions.visibility = if (sessions.isEmpty()) View.VISIBLE else View.GONE
                }
            }
        }
    }

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
                // ACTION_STOP : chaque service s'arrête lui-même (stopForeground +
                // stopSelf + START_NOT_STICKY) avant que le process ne soit tué —
                // un simple stopService() est asynchrone et le killProcess() peut
                // intervenir avant, ce qui fait redémarrer les services START_STICKY.
                context.startService(
                    android.content.Intent(context, HassanWakeWordService::class.java)
                        .setAction(HassanWakeWordService.ACTION_STOP)
                )
                context.startService(
                    android.content.Intent(context, HassanNotificationService::class.java)
                        .setAction(HassanNotificationService.ACTION_STOP)
                )
                context.startService(
                    android.content.Intent(context, HassanOrchestratorService::class.java)
                        .setAction(HassanOrchestratorService.ACTION_STOP)
                )
                requireActivity().finishAndRemoveTask()
                android.os.Process.killProcess(android.os.Process.myPid())
            }
        )
    }

    override fun onDestroyView() {
        _binding = null
        super.onDestroyView()
    }
}

// ─── Adapter sessions inline ──────────────────────────────────────────────────

private class InlineSessionAdapter(
    private val onTap: (HermesSession) -> Unit,
    private val onLongPress: (HermesSession) -> Unit
) : ListAdapter<HermesSession, InlineSessionAdapter.ViewHolder>(Diff()) {

    private val dateFormat = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault())

    inner class ViewHolder(val binding: ItemSessionBinding) :
        RecyclerView.ViewHolder(binding.root) {
        fun bind(session: HermesSession) {
            binding.tvSessionName.text = session.name
            binding.tvSessionDate.text = dateFormat.format(Date(session.updatedAt))
            binding.viewActiveDot.visibility = if (session.isActive) View.VISIBLE else View.INVISIBLE
            binding.tvActiveBadge.visibility = if (session.isActive) View.VISIBLE else View.GONE
            (binding.root as? com.google.android.material.card.MaterialCardView)
                ?.strokeColor = if (session.isActive)
                    itemView.context.getColor(R.color.hasan_accent)
                else
                    itemView.context.getColor(R.color.hasan_border)
            binding.root.setOnClickListener { onTap(session) }
            binding.root.setOnLongClickListener { onLongPress(session); true }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        ViewHolder(ItemSessionBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: ViewHolder, position: Int) =
        holder.bind(getItem(position))

    private class Diff : DiffUtil.ItemCallback<HermesSession>() {
        override fun areItemsTheSame(a: HermesSession, b: HermesSession) = a.id == b.id
        override fun areContentsTheSame(a: HermesSession, b: HermesSession) = a == b
    }
}
