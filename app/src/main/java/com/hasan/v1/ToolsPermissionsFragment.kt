package com.hasan.v1

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.ComposeView
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import com.hasan.v1.ui.screens.CapabilityUiState
import com.hasan.v1.ui.screens.ToolsPermissionsCallbacks
import com.hasan.v1.ui.screens.ToolsPermissionsScreen
import com.hasan.v1.ui.screens.ToolsPermissionsUiState
import com.hasan.v1.ui.theme.HasanTheme
import com.hasan.v1.utils.HasanDialog

/**
 * Onglet "Tools" du drawer (ex-écran "Tools & Permissions" ouvert depuis Réglages,
 * promu en onglet à part entière — voir HasanNavTab.TOOLS) — remplace l'ancien McpFragment
 * (Views/XML, GridLayout + MaterialCardView) par un écran 100% Compose.
 *
 * Logique métier inchangée par rapport à McpFragment : les capabilities activées ici sont
 * exécutées à la demande via le canal `bridge` du relay WebSocket (BridgeCommandHandler.kt).
 * Pas de register/heartbeat/URL séparés comme l'ancien orchestrateur MCP tiers (retiré à
 * l'étape 11).
 */
class ToolsPermissionsFragment : Fragment() {

    private val viewModel: MainViewModel by activityViewModels()
    private val settings get() = viewModel.settings

    // mutableStateOf plutôt que StateFlow ici : SettingsManager (SharedPreferences) n'est
    // pas observable nativement — ce Fragment reste la seule source de vérité qui pousse
    // les changements vers l'état Compose après chaque action utilisateur (même pattern
    // que SettingsFragment).
    private var capabilitiesState by mutableStateOf<List<CapabilityUiState>>(emptyList())

    // Capability en attente de résultat de demande de permission
    private var pendingPermissionCapability: Capability? = null

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        val capability = pendingPermissionCapability
        pendingPermissionCapability = null
        if (capability == null) return@registerForActivityResult
        if (granted) {
            applyCapabilityToggle(capability, enabled = true)
        } else {
            refreshCapabilitiesState()
            HasanDialog.confirm(
                context = requireContext(),
                title = "Permission refusée",
                message = getString(R.string.mcp_permission_denied),
                confirmLabel = "OK",
                cancelLabel = "Fermer",
                onConfirm = {}
            )
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        refreshCapabilitiesState()
        return ComposeView(requireContext()).apply {
            setContent {
                HasanTheme {
                    ToolsPermissionsScreen(
                        state = ToolsPermissionsUiState(capabilities = capabilitiesState),
                        callbacks = ToolsPermissionsCallbacks(
                            onMenuClick = { (activity as? MainActivity)?.openDrawer() },
                            onToggleEnabled = { capability, enabled -> onCapabilityToggled(capability, enabled) },
                            onToggleAuthRequired = { capability, authRequired -> onAuthRequiredToggled(capability, authRequired) }
                        )
                    )
                }
            }
        }
    }

    // ─────────────────────────── Capabilities ──────────────────────────────

    private fun refreshCapabilitiesState() {
        val savedStates = settings.getCapabilities()
        capabilitiesState = ALL_CAPABILITIES.map { capability ->
            CapabilityUiState(
                capability = capability,
                enabled = savedStates[capability.name] ?: false,
                authRequired = settings.isCapabilityAuthRequired(capability.name, capability.authRequiredDefault),
                permissionState = permissionStateFor(capability)
            )
        }
    }

    private fun permissionStateFor(capability: Capability): CapabilityPermissionState {
        val permission = capability.permission ?: return CapabilityPermissionState.NOT_APPLICABLE
        val granted = ContextCompat.checkSelfPermission(requireContext(), permission) ==
            PackageManager.PERMISSION_GRANTED
        return if (granted) CapabilityPermissionState.GRANTED else CapabilityPermissionState.REQUIRED
    }

    private fun onCapabilityToggled(capability: Capability, enabled: Boolean) {
        if (!enabled) {
            applyCapabilityToggle(capability, enabled = false)
            return
        }

        val permission = capability.permission
        if (permission != null &&
            ContextCompat.checkSelfPermission(requireContext(), permission)
                != PackageManager.PERMISSION_GRANTED
        ) {
            pendingPermissionCapability = capability
            permissionLauncher.launch(permission)
            return
        }

        applyCapabilityToggle(capability, enabled = true)
    }

    private fun applyCapabilityToggle(capability: Capability, enabled: Boolean) {
        settings.setCapabilityEnabled(capability.name, enabled)
        refreshCapabilitiesState()
    }

    private fun onAuthRequiredToggled(capability: Capability, authRequired: Boolean) {
        settings.setCapabilityAuthRequired(capability.name, authRequired)
        refreshCapabilitiesState()
    }
}
