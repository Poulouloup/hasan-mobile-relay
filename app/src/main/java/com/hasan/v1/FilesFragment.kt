package com.hasan.v1

import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.ComposeView
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.hasan.v1.databinding.FragmentFilesBinding
import com.hasan.v1.ui.screens.FilesCallbacks
import com.hasan.v1.ui.screens.FilesScreen
import com.hasan.v1.ui.screens.FilesScreenUiState
import com.hasan.v1.ui.theme.HasanTheme
import kotlinx.coroutines.launch

/**
 * Écran Fichiers — parcourt et télécharge le workspace hermes-webui
 * (/api/list, /api/file/raw), jusqu'ici utilisés seulement par le frontend
 * web statique de hermes-webui. Remplace share_file/attachments-out : un
 * seul chemin pour voir/télécharger des fichiers, sans dépendre d'un lien
 * caché dans le texte du chat.
 *
 * Le paramètre session_id est requis par l'API mais n'isole PAS le contenu
 * dans la config par défaut de hermes-webui : toutes les sessions partagent
 * le même DEFAULT_WORKSPACE côté serveur tant qu'un workspace différent n'a
 * pas été explicitement choisi par session — voir docs/ARCHITECTURE.md
 * #fichiers. Ce que cet écran affiche est donc, en pratique, un workspace
 * global partagé, pas un espace isolé par conversation.
 */
class FilesFragment : Fragment() {

    private val viewModel: FilesViewModel by activityViewModels()

    private var _binding: FragmentFilesBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentFilesBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        // Le ViewModel est partagé au niveau Activity (activityViewModels) et
        // ne se recrée pas à chaque ouverture de cet overlay — un refresh
        // explicite ici garantit que la liste reflète les derniers fichiers
        // écrits par l'agent depuis la dernière fermeture de l'écran.
        viewModel.refresh()
        val composeView = binding.filesComposeRoot as ComposeView
        composeView.setContent {
            HasanTheme {
                val state by viewModel.uiState.collectAsState()
                FilesScreen(
                    state = FilesScreenUiState(
                        entries = state.entries,
                        currentPath = state.currentPath,
                        loading = state.loading,
                        errorMessage = state.errorMessage
                    ),
                    callbacks = FilesCallbacks(
                        onBack = { (activity as? MainActivity)?.closeFiles() },
                        onRefresh = { viewModel.refresh() },
                        onEntryClick = { entry -> viewModel.navigateInto(entry) },
                        onDownload = { entry -> viewModel.downloadAndOpen(entry) },
                        onNavigateUp = { viewModel.navigateUp() },
                        onDismissError = { viewModel.clearError() }
                    )
                )
            }
        }
        observeOpenFile()
    }

    private fun observeOpenFile() {
        viewLifecycleOwner.lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.openFile.collect { uri ->
                    try {
                        val intent = Intent(Intent.ACTION_VIEW).apply {
                            setDataAndType(uri, requireContext().contentResolver.getType(uri) ?: "*/*")
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }
                        startActivity(intent)
                    } catch (e: ActivityNotFoundException) {
                        Toast.makeText(requireContext(), "Aucune application pour ouvrir ce fichier", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
