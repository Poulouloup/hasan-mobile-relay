package com.hasan.v1

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.hasan.v1.databinding.FragmentSessionsBinding
import com.hasan.v1.databinding.ItemSessionBinding
import com.hasan.v1.db.HermesSession
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Fragment de gestion des sessions Hermes.
 * Affiché par-dessus SettingsFragment via fragmentManager (pas de BottomNav).
 *
 * Permet de : lister, créer, renommer, supprimer, activer des sessions.
 * La session active détermine le conversation_id envoyé à Hermes.
 */
class SessionsFragment : Fragment() {

    private var _binding: FragmentSessionsBinding? = null
    private val binding get() = _binding!!

    private val viewModel: MainViewModel by activityViewModels()

    private lateinit var adapter: SessionAdapter

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentSessionsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        setupRecyclerView()
        setupListeners()
        observeSessions()
    }

    private fun setupRecyclerView() {
        adapter = SessionAdapter(
            onTap = { session -> viewModel.activateSession(session) },
            onLongPress = { session -> showSessionMenu(session) }
        )
        binding.rvSessions.layoutManager = LinearLayoutManager(requireContext())
        binding.rvSessions.adapter = adapter
    }

    private fun setupListeners() {
        binding.btnBack.setOnClickListener {
            parentFragmentManager.popBackStack()
        }

        binding.btnNewSession.setOnClickListener {
            createNewSession()
        }
    }

    private fun observeSessions() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.lifecycle.addObserver(
                object : androidx.lifecycle.DefaultLifecycleObserver {
                    override fun onStart(owner: androidx.lifecycle.LifecycleOwner) {
                        viewLifecycleOwner.lifecycleScope.launch {
                            viewModel.sessions.collectLatest { sessions ->
                                adapter.submitList(sessions)
                                binding.rvSessions.visibility =
                                    if (sessions.isEmpty()) View.GONE else View.VISIBLE
                                binding.tvEmpty.visibility =
                                    if (sessions.isEmpty()) View.VISIBLE else View.GONE
                            }
                        }
                    }
                }
            )
        }
        // Observation directe pour simplifier
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.sessions.collectLatest { sessions ->
                adapter.submitList(sessions)
                binding.rvSessions.visibility =
                    if (sessions.isEmpty()) View.GONE else View.VISIBLE
                binding.tvEmpty.visibility =
                    if (sessions.isEmpty()) View.VISIBLE else View.GONE
            }
        }
    }

    // ─────────────────────────── Actions ─────────────────────────────────────

    private fun createNewSession() {
        val dateStr = SimpleDateFormat("d MMMM", Locale.FRENCH).format(Date())
        val defaultName = "Session du $dateStr"
        showNameDialog(
            title = "Nouvelle session",
            defaultName = defaultName,
            onConfirm = { name -> viewModel.createSession(name) }
        )
    }

    private fun showSessionMenu(session: HermesSession) {
        val options = arrayOf("Renommer", "Supprimer")
        AlertDialog.Builder(requireContext())
            .setTitle(session.name)
            .setItems(options) { _, index ->
                when (index) {
                    0 -> showRenameDialog(session)
                    1 -> confirmDelete(session)
                }
            }
            .show()
    }

    private fun showRenameDialog(session: HermesSession) {
        showNameDialog(
            title = "Renommer la session",
            defaultName = session.name,
            onConfirm = { newName -> viewModel.renameSession(session, newName) }
        )
    }

    private fun showNameDialog(title: String, defaultName: String, onConfirm: (String) -> Unit) {
        val input = EditText(requireContext()).apply {
            setText(defaultName)
            selectAll()
            setTextColor(resources.getColor(R.color.hasan_text_primary, null))
            setHintTextColor(resources.getColor(R.color.hasan_text_secondary, null))
            setPadding(48, 32, 48, 8)
        }
        AlertDialog.Builder(requireContext())
            .setTitle(title)
            .setView(input)
            .setPositiveButton("Confirmer") { _, _ ->
                val name = input.text.toString().trim()
                if (name.isNotBlank()) onConfirm(name)
            }
            .setNegativeButton("Annuler", null)
            .show()
    }

    private fun confirmDelete(session: HermesSession) {
        val message = if (session.isActive) {
            "\"${session.name}\" est la session active.\nUne nouvelle session sera créée automatiquement."
        } else {
            "Supprimer \"${session.name}\" ?"
        }
        AlertDialog.Builder(requireContext())
            .setMessage(message)
            .setPositiveButton("Supprimer") { _, _ -> viewModel.deleteSession(session) }
            .setNegativeButton("Annuler", null)
            .show()
    }

    override fun onDestroyView() {
        _binding = null
        super.onDestroyView()
    }
}

// ─── Adapter ─────────────────────────────────────────────────────────────────

private class SessionAdapter(
    private val onTap: (HermesSession) -> Unit,
    private val onLongPress: (HermesSession) -> Unit
) : ListAdapter<HermesSession, SessionAdapter.ViewHolder>(SessionDiffCallback()) {

    private val dateFormat = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault())

    inner class ViewHolder(val binding: ItemSessionBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(session: HermesSession) {
            binding.tvSessionName.text = session.name
            binding.tvSessionDate.text = dateFormat.format(Date(session.updatedAt))

            // Point rouge visible si session active
            binding.viewActiveDot.visibility =
                if (session.isActive) View.VISIBLE else View.INVISIBLE

            // Badge "Active"
            binding.tvActiveBadge.visibility =
                if (session.isActive) View.VISIBLE else View.GONE

            // Bordure accentuée si active
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
}

private class SessionDiffCallback : DiffUtil.ItemCallback<HermesSession>() {
    override fun areItemsTheSame(a: HermesSession, b: HermesSession) = a.id == b.id
    override fun areContentsTheSame(a: HermesSession, b: HermesSession) = a == b
}
