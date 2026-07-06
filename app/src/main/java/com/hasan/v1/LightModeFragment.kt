package com.hasan.v1

import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.hasan.v1.databinding.FragmentLightModeBinding
import com.hasan.v1.db.HassanDatabase
import kotlinx.coroutines.launch

class LightModeFragment : Fragment() {

    private var _binding: FragmentLightModeBinding? = null
    private val binding get() = _binding!!
    private val viewModel: MainViewModel by activityViewModels()

    private var lastVoiceState: VoiceState? = null
    private val vibrator by lazy { requireContext().getSystemService(Vibrator::class.java) }
    private var ttsMuted = false

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentLightModeBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.btnLightMic.setOnClickListener {
            val state = viewModel.uiState.value
            val listening = state.isListening || state.sttStatus == SttStatus.LISTENING || state.sttStatus == SttStatus.PROCESSING
            if (listening) {
                viewModel.onSttError(-1, "")
            } else {
                viewModel.toggleListening()
            }
        }

        binding.btnExitLightMode.setOnClickListener {
            (activity as? MainActivity)?.exitLightMode()
        }

        binding.btnLightMute.setOnClickListener {
            ttsMuted = !ttsMuted
            if (ttsMuted) {
                viewModel.stopTts()
                binding.btnLightMute.setImageResource(R.drawable.ic_volume_off)
            } else {
                binding.btnLightMute.setImageResource(R.drawable.ic_volume_on)
            }
        }

        observeState()
        observeLastMessage()
        observeWakeWord()
    }

    private fun observeState() {
        viewLifecycleOwner.lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uiState.collect { state ->
                    val voiceState = state.voiceState()
                    renderVoiceState(voiceState)
                }
            }
        }
    }

    private fun observeLastMessage() {
        viewLifecycleOwner.lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uiState.collect { state ->
                    val convId = state.resumedConversationId ?: return@collect
                    val db = HassanDatabase.getInstance(requireContext())
                    val msgs = db.messageDao().getMessagesForConversationOnce(convId)
                    val lastHasan = msgs.lastOrNull { it.role == "assistant" && it.content.isNotBlank() }
                    binding.tvLightLastMessage.text = lastHasan?.content ?: ""
                }
            }
        }
    }

    private fun observeWakeWord() {
        viewLifecycleOwner.lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                HassanWakeWordService.wakeWordDetected.collect {
                    Log.d(TAG, "Wake word détecté en Light Mode")
                    viewModel.onWakeWordDetected()
                }
            }
        }
    }

    private fun renderVoiceState(voiceState: VoiceState) {
        val prev = lastVoiceState
        lastVoiceState = voiceState

        val isListening = voiceState is VoiceState.SttListening || voiceState is VoiceState.SttProcessing || voiceState is VoiceState.WakeWordDetected
        binding.btnLightMic.setImageResource(
            if (isListening) R.drawable.ic_stop_rounded else R.drawable.ic_mic
        )

        binding.tvLightStatus.text = when (voiceState) {
            is VoiceState.Idle              -> getString(R.string.status_light_ready)
            is VoiceState.WakeWordListening -> getString(R.string.status_light_ready)
            is VoiceState.WakeWordDetected  -> getString(R.string.status_listening)
            is VoiceState.SttListening      -> getString(R.string.voice_state_stt_listening)
            is VoiceState.SttProcessing     -> getString(R.string.status_transcribing)
            is VoiceState.HermesThinking    -> getString(R.string.status_thinking)
            is VoiceState.HermesStreaming    -> voiceState.toolMessage
                ?: getString(R.string.status_generating)
            is VoiceState.TtsSpeaking       -> getString(R.string.status_speaking)
            is VoiceState.Error             -> "⚠️ ${voiceState.message}"
        }

        if (voiceState is VoiceState.WakeWordDetected && prev !is VoiceState.WakeWordDetected) {
            vibrator?.vibrate(VibrationEffect.createOneShot(50, VibrationEffect.DEFAULT_AMPLITUDE))
        }
        if (voiceState is VoiceState.TtsSpeaking && prev !is VoiceState.TtsSpeaking) {
            vibrator?.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 30, 60, 30), -1))
            // Coupe le TTS si l'utilisateur a mute
            if (ttsMuted) viewModel.stopTts()
        }
    }

    override fun onDestroyView() {
        _binding = null
        super.onDestroyView()
    }

    companion object {
        private const val TAG = "LightMode"
    }
}
