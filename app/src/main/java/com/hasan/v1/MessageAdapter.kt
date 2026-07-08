package com.hasan.v1

import android.animation.ObjectAnimator
import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.hasan.v1.databinding.ItemMessageBinding
import com.hasan.v1.db.Message
import android.text.method.LinkMovementMethod
import io.noties.markwon.Markwon
import io.noties.markwon.ext.strikethrough.StrikethroughPlugin
import io.noties.markwon.ext.tables.TablePlugin
import io.noties.markwon.linkify.LinkifyPlugin
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Adapter RecyclerView pour la liste de messages de la conversation.
 * Bulles utilisateur à droite (#2A2A2A) et Hasan à gauche (bordure rouge #CC2936).
 * Les bulles Hasan utilisent Markwon pour le rendu Markdown.
 */
class MessageAdapter(
    private val onUserLongPress: (Message) -> Unit,
    private val onHasanLongPress: (Message) -> Unit,
    private val onToggleTts: (Message) -> Unit,
    private val onCopy: (Message) -> Unit,
    private val onRetry: (() -> Unit)? = null,
    private val onClarifyResponse: ((String) -> Unit)? = null
) : ListAdapter<Message, MessageAdapter.MessageViewHolder>(MessageDiffCallback()) {

    /** ID du message actuellement lu par le TTS — mis à jour par le Fragment. */
    var ttsPlayingMessageId: Long? = null
        set(value) {
            val old = field
            field = value
            if (old != value) {
                // Rafraîchit uniquement les deux items concernés
                currentList.forEachIndexed { i, msg ->
                    if (msg.id == old || msg.id == value) notifyItemChanged(i)
                }
            }
        }

    private val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())

    // Instance Markwon partagée — créée une seule fois pour éviter l'overhead
    private var markwon: Markwon? = null

    private fun getMarkwon(context: Context): Markwon {
        return markwon ?: Markwon.builder(context)
            .usePlugin(StrikethroughPlugin.create())
            .usePlugin(TablePlugin.create(context))
            .usePlugin(LinkifyPlugin.create())
            .build()
            .also { markwon = it }
    }

    inner class MessageViewHolder(
        private val binding: ItemMessageBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        private var dotsAnimator: ObjectAnimator? = null

        fun bind(message: Message) {
            val timeStr = timeFormat.format(Date(message.timestamp))

            if (message.role == "clarify") {
                binding.containerClarify.visibility  = View.VISIBLE
                binding.containerError.visibility    = View.GONE
                binding.containerThinking.visibility = View.GONE
                binding.containerHasan.visibility    = View.GONE
                binding.containerUser.visibility     = View.GONE

                val clarifyData = try { org.json.JSONObject(message.content) } catch (_: Exception) { null }
                val question = clarifyData?.optString("question") ?: message.content
                val choicesArr = clarifyData?.optJSONArray("choices")
                val choices = if (choicesArr != null) (0 until choicesArr.length()).map { choicesArr.getString(it) } else null
                val answered = clarifyData?.optBoolean("answered", false) ?: false
                val answeredWith = clarifyData?.optString("answeredWith")?.takeIf { it.isNotBlank() && it != "null" }

                binding.tvClarifyQuestion.movementMethod = LinkMovementMethod.getInstance()
                getMarkwon(binding.root.context).setMarkdown(binding.tvClarifyQuestion, question)
                binding.tvClarifyQuestion.alpha = if (answered) 0.6f else 1f

                val choiceRows = listOf(
                    Triple(binding.btnChoice1, binding.tvChoiceLabel1, binding.divChoice1),
                    Triple(binding.btnChoice2, binding.tvChoiceLabel2, binding.divChoice2),
                    Triple(binding.btnChoice3, binding.tvChoiceLabel3, binding.divChoice3),
                    Triple(binding.btnChoice4, binding.tvChoiceLabel4, binding.divChoice4)
                )
                if (choices != null) {
                    binding.containerClarifyChoices.visibility = View.VISIBLE
                    choiceRows.forEachIndexed { i, (row, label, divider) ->
                        if (i < choices.size) {
                            getMarkwon(binding.root.context).setMarkdown(label, choices[i])
                            row.visibility = View.VISIBLE
                            val isLast = i == choices.size - 1
                            divider.visibility = if (isLast) View.GONE else View.VISIBLE
                            val choiceText = choices[i]
                            val isChosen = answered && choiceText == answeredWith
                            row.alpha = if (answered && !isChosen) 0.4f else 1f
                            row.setBackgroundColor(
                                if (isChosen)
                                    ContextCompat.getColor(binding.root.context, R.color.hasan_accent_dim)
                                else
                                    android.graphics.Color.TRANSPARENT
                            )
                            if (answered) {
                                row.isClickable = false
                                row.setOnClickListener(null)
                            } else {
                                row.isClickable = true
                                row.isFocusable = true
                                row.setOnClickListener { onClarifyResponse?.invoke(choiceText) }
                            }
                        } else {
                            row.visibility = View.GONE
                            row.isClickable = false
                            divider.visibility = View.GONE
                        }
                    }
                    binding.containerClarifyInput.visibility = View.GONE
                    if (answered) {
                        binding.btnChoiceOther.visibility = View.GONE
                    } else {
                        binding.btnChoiceOther.visibility = View.VISIBLE
                        binding.btnChoiceOther.setOnClickListener {
                            // Remplace "Autre chose" par le champ de saisie sur place —
                            // les choix 1-3 restent visibles au-dessus.
                            binding.btnChoiceOther.visibility = View.GONE
                            binding.containerClarifyInput.visibility = View.VISIBLE
                            binding.etClarifyInput.requestFocus()
                        }
                    }
                } else {
                    // Pas de choix : champ libre uniquement. containerClarifyChoices reste
                    // visible car il héberge maintenant containerClarifyInput ; seules les
                    // rows numérotées et "Autre chose" sont masquées.
                    binding.containerClarifyChoices.visibility = View.VISIBLE
                    choiceRows.forEach { (row, _, divider) -> row.visibility = View.GONE; divider.visibility = View.GONE }
                    binding.btnChoiceOther.visibility = View.GONE
                    if (answered) {
                        binding.containerClarifyInput.visibility = View.GONE
                        if (!answeredWith.isNullOrBlank()) {
                            binding.tvClarifyQuestion.text = "$question\n\n→ $answeredWith"
                        }
                    } else {
                        binding.containerClarifyInput.visibility = View.VISIBLE
                    }
                }

                binding.btnClarifySend.setOnClickListener {
                    val text = binding.etClarifyInput.text?.toString()?.trim() ?: ""
                    if (text.isNotEmpty()) onClarifyResponse?.invoke(text)
                }
                return
            }

            binding.containerClarify.visibility = View.GONE

            if (message.role == "error") {
                binding.containerError.visibility    = View.VISIBLE
                binding.containerThinking.visibility = View.GONE
                binding.containerHasan.visibility     = View.GONE
                binding.containerUser.visibility      = View.GONE
                binding.tvErrorMessage.text = "⚠️ ${message.content}"
                binding.btnRetry.setOnClickListener { onRetry?.invoke() }
                return
            }

            binding.containerError.visibility = View.GONE

            if (message.role == "thinking") {
                binding.containerThinking.visibility = View.VISIBLE
                binding.containerHasan.visibility    = View.GONE
                binding.containerUser.visibility     = View.GONE
                binding.containerClarify.visibility  = View.GONE
                binding.tvThinkingMessage.text = message.content
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

                if (message.isStreaming && message.content.isBlank()) {
                    // Bulle en attente du premier token — affiche les "..." animés
                    binding.tvMessageHasan.text = "•••"
                    binding.tvMessageHasan.movementMethod = null
                    if (dotsAnimator == null) {
                        dotsAnimator = ObjectAnimator.ofFloat(binding.tvMessageHasan, "alpha", 0.3f, 1f).apply {
                            duration    = 600L
                            repeatMode  = ObjectAnimator.REVERSE
                            repeatCount = ObjectAnimator.INFINITE
                            start()
                        }
                    }
                    binding.tvTimestampHasan.text = ""
                    binding.tvMetadata.visibility = View.GONE
                    binding.btnToggleTts.visibility = View.GONE
                    binding.btnCopyMessage.visibility = View.GONE
                    binding.containerHasan.setOnLongClickListener(null)
                } else {
                    dotsAnimator?.cancel()
                    dotsAnimator = null
                    binding.tvMessageHasan.alpha = 1f
                    binding.tvMessageHasan.setTextIsSelectable(true)
                    binding.tvMessageHasan.movementMethod = LinkMovementMethod.getInstance()
                    getMarkwon(binding.root.context)
                        .setMarkdown(binding.tvMessageHasan, message.content)
                    binding.tvTimestampHasan.text = timeStr
                    val metaText = buildMetadataText(message.metadata)
                    if (metaText != null) {
                        binding.tvMetadata.text = metaText
                        binding.tvMetadata.visibility = View.VISIBLE
                    } else {
                        binding.tvMetadata.visibility = View.GONE
                    }
                    binding.btnToggleTts.visibility = View.VISIBLE
                    binding.btnCopyMessage.visibility = View.VISIBLE
                    binding.containerHasan.setOnLongClickListener {
                        onHasanLongPress(message)
                        true
                    }
                    val isPlaying = ttsPlayingMessageId == message.id
                    binding.btnToggleTts.setImageResource(
                        if (isPlaying) R.drawable.ic_volume_off else R.drawable.ic_replay
                    )
                    binding.btnToggleTts.setOnClickListener { onToggleTts(message) }
                    binding.btnCopyMessage.setOnClickListener { onCopy(message) }
                }
            }
        }
    }

    private fun buildMetadataText(metadata: String?): String? {
        if (metadata.isNullOrBlank()) return null
        return try {
            val obj = JSONObject(metadata)
            val durationMs = obj.optLong("duration_ms", -1L)
            val outputTokens = obj.optInt("output_tokens", 0)
            if (durationMs < 0 && outputTokens == 0) return null
            val parts = mutableListOf<String>()
            if (durationMs >= 0) parts.add("${"%.1f".format(durationMs / 1000.0)}s")
            if (outputTokens > 0) parts.add("$outputTokens tok")
            parts.joinToString(" · ")
        } catch (_: Exception) { null }
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
