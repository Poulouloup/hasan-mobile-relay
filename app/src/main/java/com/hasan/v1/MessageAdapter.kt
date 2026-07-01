package com.hasan.v1

import android.animation.ObjectAnimator
import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
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
    private val onRetry: (() -> Unit)? = null
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

        fun bind(message: Message) {
            val timeStr = timeFormat.format(Date(message.timestamp))

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

                binding.tvMessageHasan.setTextIsSelectable(true)
                binding.tvMessageHasan.movementMethod = LinkMovementMethod.getInstance()
                getMarkwon(binding.root.context)
                    .setMarkdown(binding.tvMessageHasan, message.content)

                binding.tvTimestampHasan.text = timeStr
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
