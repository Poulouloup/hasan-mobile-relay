package com.hasan.v1

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.RadioButton
import android.widget.RadioGroup
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
        binding.connectionStatusLayout.visibility = View.VISIBLE
        binding.tvConnectionResult.text = "Test en cours…"
        binding.tvConnectionResult.setTextColor(
            ContextCompat.getColor(requireContext(), R.color.hasan_text_secondary)
        )

        lifecycleScope.launch {
            val ok = withContext(Dispatchers.IO) {
                try {
                    HermesApiClient(
                        HermesConfig(settings.serverUrl, settings.authToken, settings.effectiveModel())
                    ).checkHealth()
                } catch (_: Exception) { false }
            }
            val dotColor = if (ok) R.color.hasan_success else R.color.hasan_error
            binding.viewTestDot.backgroundTintList =
                android.content.res.ColorStateList.valueOf(
                    ContextCompat.getColor(requireContext(), dotColor)
                )
            binding.tvConnectionResult.text = getString(
                if (ok) R.string.settings_connection_ok else R.string.settings_connection_fail
            )
            binding.tvConnectionResult.setTextColor(
                ContextCompat.getColor(requireContext(),
                    if (ok) R.color.hasan_success else R.color.hasan_error)
            )
        }
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
