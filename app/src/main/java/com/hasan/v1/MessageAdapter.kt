package com.hasan.v1

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.hasan.v1.databinding.ItemMessageBinding
import com.hasan.v1.db.Message
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Adapter RecyclerView pour la liste de messages de la conversation.
 * Bulles utilisateur à droite (#2A2A2A) et Hasan à gauche (bordure rouge #CC2936).
 * Bouton 🔊 sur chaque bulle Hasan pour rejouer le TTS.
 */
class MessageAdapter(
    private val onUserLongPress: (Message) -> Unit,
    private val onHasanLongPress: (Message) -> Unit,
    private val onReplayTts: (Message) -> Unit
) : ListAdapter<Message, MessageAdapter.MessageViewHolder>(MessageDiffCallback()) {

    private val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())

    inner class MessageViewHolder(
        private val binding: ItemMessageBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(message: Message) {
            val timeStr = timeFormat.format(Date(message.timestamp))

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
                binding.tvMessageHasan.text  = message.content
                binding.tvTimestampHasan.text = timeStr
                binding.containerHasan.setOnLongClickListener {
                    onHasanLongPress(message)
                    true
                }
                // Bouton rejouer TTS — icône son en bas à gauche
                binding.btnReplayTts.setOnClickListener {
                    onReplayTts(message)
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
