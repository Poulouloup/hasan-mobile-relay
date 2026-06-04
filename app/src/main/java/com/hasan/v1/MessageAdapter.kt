package com.hasan.v1

import android.animation.ObjectAnimator
import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.chip.Chip
import com.hasan.v1.databinding.ItemMessageBinding
import com.hasan.v1.db.Message
import com.hasan.v1.utils.MarkdownUtils
import io.noties.markwon.Markwon
import io.noties.markwon.ext.strikethrough.StrikethroughPlugin
import io.noties.markwon.ext.tables.TablePlugin
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Adapter RecyclerView pour la liste de messages de la conversation.
 * Bulles utilisateur à droite (#2A2A2A) et Hasan à gauche (bordure rouge #CC2936).
 * Bouton 🔊 sur chaque bulle Hasan pour rejouer le TTS.
 * Les bulles Hasan utilisent Markwon pour le rendu Markdown.
 * Les QCM détectés dans les réponses Hasan sont affichés comme chips cliquables.
 */
class MessageAdapter(
    private val onUserLongPress: (Message) -> Unit,
    private val onHasanLongPress: (Message) -> Unit,
    private val onReplayTts: (Message) -> Unit,
    private val onQcmChoice: ((String) -> Unit)? = null
) : ListAdapter<Message, MessageAdapter.MessageViewHolder>(MessageDiffCallback()) {

    private val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())

    // Instance Markwon partagée — créée une seule fois pour éviter l'overhead
    private var markwon: Markwon? = null

    private fun getMarkwon(context: Context): Markwon {
        return markwon ?: Markwon.builder(context)
            .usePlugin(StrikethroughPlugin.create())
            .usePlugin(TablePlugin.create(context))
            .build()
            .also { markwon = it }
    }

    inner class MessageViewHolder(
        private val binding: ItemMessageBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(message: Message) {
            val timeStr = timeFormat.format(Date(message.timestamp))

            if (message.role == "thinking") {
                binding.containerThinking.visibility = View.VISIBLE
                binding.containerHasan.visibility    = View.GONE
                binding.containerUser.visibility     = View.GONE
                binding.tvThinkingMessage.text = message.content
                // Animate dots alpha
                ObjectAnimator.ofFloat(binding.tvThinkingDots, "alpha", 0.2f, 1f).apply {
                    duration    = 700L
                    repeatMode  = ObjectAnimator.REVERSE
                    repeatCount = ObjectAnimator.INFINITE
                    start()
                }
                return
            }

            binding.containerThinking.visibility = View.GONE

            if (message.role == "user") {
                binding.containerUser.visibility  = View.VISIBLE
                binding.containerHasan.visibility = View.GONE
                binding.tvMessageUser.text  = message.content
                binding.tvTimestampUser.text = timeStr
                binding.containerUser.setOnLongClickListener {
                    onUserLongPress(message)
                    true
                }
            } else {
                binding.containerHasan.visibility = View.VISIBLE
                binding.containerUser.visibility  = View.GONE

                // Rendu Markdown dans les bulles Hasan
                getMarkwon(binding.root.context)
                    .setMarkdown(binding.tvMessageHasan, message.content)

                binding.tvTimestampHasan.text = timeStr
                binding.containerHasan.setOnLongClickListener {
                    onHasanLongPress(message)
                    true
                }
                // Bouton rejouer TTS
                binding.btnReplayTts.setOnClickListener {
                    onReplayTts(message)
                }

                // Chips QCM — affiche les options si détectées et message non en streaming
                val options = if (!message.isStreaming)
                    MarkdownUtils.extractQcmOptions(message.content)
                else emptyList()

                if (options.isNotEmpty() && onQcmChoice != null) {
                    binding.chipGroupQcm.visibility = View.VISIBLE
                    binding.chipGroupQcm.removeAllViews()
                    options.forEach { option ->
                        val chip = Chip(binding.root.context).apply {
                            text = option
                            isClickable = true
                            isCheckable = false
                            setChipBackgroundColorResource(R.color.hasan_bg_card)
                            setChipStrokeColorResource(R.color.hasan_accent)
                            chipStrokeWidth = 1.5f
                            setTextColor(
                                binding.root.context.getColor(R.color.hasan_text_primary)
                            )
                            textSize = 13f
                            setOnClickListener {
                                // Envoie le choix comme message utilisateur
                                onQcmChoice.invoke(option)
                                // Cache les chips après le choix
                                binding.chipGroupQcm.visibility = View.GONE
                            }
                        }
                        binding.chipGroupQcm.addView(chip)
                    }
                } else {
                    binding.chipGroupQcm.visibility = View.GONE
                }
            }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): MessageViewHolder {
        val binding = ItemMessageBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return MessageViewHolder(binding)
    }

    override fun onBindViewHolder(holder: MessageViewHolder, position: Int) {
        holder.bind(getItem(position))
    }
}

private class MessageDiffCallback : DiffUtil.ItemCallback<Message>() {
    override fun areItemsTheSame(oldItem: Message, newItem: Message) = oldItem.id == newItem.id
    override fun areContentsTheSame(oldItem: Message, newItem: Message) = oldItem == newItem
}
