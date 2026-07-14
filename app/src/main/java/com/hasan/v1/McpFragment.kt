package com.hasan.v1

import android.Manifest
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import android.widget.GridLayout
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.hasan.v1.databinding.FragmentMcpBinding
import com.hasan.v1.databinding.ItemCapabilityCardBinding
import com.hasan.v1.utils.HasanDialog

/**
 * Fragment MCP — active/désactive les capabilities exposées par cet appareil.
 *
 * Aucune connexion réseau propre à ce fragment : les capabilities activées
 * ici sont exécutées à la demande via le canal `bridge` du relay WebSocket
 * (voir BridgeCommandHandler.kt, câblé depuis MainViewModel — la connexion
 * WS elle-même est gérée par ConnectionManager, partagée avec le reste de
 * l'app). Pas de register/heartbeat/URL séparés comme l'ancien orchestrateur
 * MCP tiers (retiré à l'étape 11).
 */
class McpFragment : Fragment() {

    private var _binding: FragmentMcpBinding? = null
    private val binding get() = _binding!!

    private val viewModel: MainViewModel by activityViewModels()
    private val settings get() = viewModel.settings

    private lateinit var capabilityAdapter: CapabilityAdapter

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
            capabilityAdapter.setEnabled(capability.name, false)
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
        _binding = FragmentMcpBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupCapabilitiesList()
    }

    // ─────────────────────────── Capabilities ──────────────────────────────

    private fun setupCapabilitiesList() {
        val savedStates = settings.getCapabilities()
        val capabilities = ALL_CAPABILITIES.map { it.copy(enabled = savedStates[it.name] ?: false) }

        capabilityAdapter = CapabilityAdapter(
            container = binding.containerCapabilities,
            items = capabilities,
            settings = settings,
            onToggle = { capability, enabled -> onCapabilityToggled(capability, enabled) },
            onAuthRequiredToggle = { capability, authRequired -> onAuthRequiredToggled(capability, authRequired) }
        )
        capabilityAdapter.bindAll()
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
        capabilityAdapter.setEnabled(capability.name, enabled)
        settings.setCapabilityEnabled(capability.name, enabled)
    }

    private fun onAuthRequiredToggled(capability: Capability, authRequired: Boolean) {
        settings.setCapabilityAuthRequired(capability.name, authRequired)
    }

    override fun onDestroyView() {
        _binding = null
        super.onDestroyView()
    }
}

// ─────────────────────────── Modèle capability ─────────────────────────────

data class Capability(
    val name: String,
    val iconRes: Int,
    val labelRes: Int,
    val descriptionRes: Int,
    val authRequiredDefault: Boolean,
    val permission: String?,
    val enabled: Boolean = false,
    /** Schéma des paramètres attendus — voir CapabilitySchema.kt. Vide si la capability ne prend aucun paramètre. */
    val parameters: List<ParamSpec> = emptyList()
)

/** Accessible aussi depuis CapabilityExecutor pour la validation des paramètres avant exécution. */
val ALL_CAPABILITIES = listOf(
    Capability("get_battery",      R.drawable.ic_cap_battery,      R.string.mcp_cap_get_battery_label,      R.string.mcp_cap_get_battery_desc,      false, null),
    Capability("send_sms",         R.drawable.ic_cap_sms,          R.string.mcp_cap_send_sms_label,         R.string.mcp_cap_send_sms_desc,         true,  Manifest.permission.SEND_SMS,
        parameters = listOf(
            ParamSpec("to", ParamType.STRING, required = true, description = "Numéro de téléphone du destinataire"),
            ParamSpec("message", ParamType.STRING, required = true, description = "Contenu du SMS")
        )),
    Capability("get_location",     R.drawable.ic_cap_location,     R.string.mcp_cap_get_location_label,     R.string.mcp_cap_get_location_desc,     true,  Manifest.permission.ACCESS_FINE_LOCATION),
    Capability("send_notification",R.drawable.ic_cap_notification, R.string.mcp_cap_send_notification_label,R.string.mcp_cap_send_notification_desc, false, null,
        parameters = listOf(
            ParamSpec("title", ParamType.STRING, required = false, description = "Titre de la notification (défaut: Hasan)"),
            ParamSpec("body", ParamType.STRING, required = true, description = "Texte de la notification")
        )),
    Capability("set_volume",       R.drawable.ic_cap_volume,       R.string.mcp_cap_set_volume_label,       R.string.mcp_cap_set_volume_desc,       false, null,
        parameters = listOf(
            ParamSpec("level", ParamType.INT, required = true, description = "Volume cible, 0 à 100")
        )),
    Capability("launch_app",       R.drawable.ic_cap_launch_app,   R.string.mcp_cap_launch_app_label,       R.string.mcp_cap_launch_app_desc,       false, null,
        parameters = listOf(
            ParamSpec("package_name", ParamType.STRING, required = true, description = "Nom de package Android à lancer")
        )),
    Capability("discover_apps",    R.drawable.ic_cap_discover_apps,R.string.mcp_cap_discover_apps_label,    R.string.mcp_cap_discover_apps_desc,    false, null),
    Capability("get_contacts",     R.drawable.ic_cap_contacts,     R.string.mcp_cap_get_contacts_label,     R.string.mcp_cap_get_contacts_desc,     true,  Manifest.permission.READ_CONTACTS,
        parameters = listOf(
            ParamSpec("query", ParamType.STRING, required = false, description = "Filtre sur le nom du contact"),
            ParamSpec("limit", ParamType.INT, required = false, description = "Nombre maximum de résultats (défaut 20, max 100)")
        )),
    Capability("set_alarm",        R.drawable.ic_cap_alarm,        R.string.mcp_cap_set_alarm_label,        R.string.mcp_cap_set_alarm_desc,        false, null,
        parameters = listOf(
            ParamSpec("hour", ParamType.INT, required = true, description = "Heure (0-23)"),
            ParamSpec("minute", ParamType.INT, required = true, description = "Minute (0-59)"),
            ParamSpec("label", ParamType.STRING, required = false, description = "Libellé de l'alarme (défaut: Hasan)")
        )),
    Capability("get_network_info",    R.drawable.ic_cap_wifi,         R.string.mcp_cap_get_network_info_label,    R.string.mcp_cap_get_network_info_desc,    false, null),
    Capability("get_device_info",  R.drawable.ic_cap_device_info,  R.string.mcp_cap_get_device_info_label,  R.string.mcp_cap_get_device_info_desc,  false, null)
)

// ─────────────────────────── Adapter ────────────────────────────────────────

/**
 * Grille 2 colonnes de capabilities — inflation directe dans [container] (GridLayout)
 * plutôt qu'un RecyclerView, pour éviter les problèmes de mesure wrap_content imbriqué.
 */
private class CapabilityAdapter(
    private val container: ViewGroup,
    private val items: List<Capability>,
    private val settings: SettingsManager,
    private val onToggle: (Capability, Boolean) -> Unit,
    private val onAuthRequiredToggle: (Capability, Boolean) -> Unit
) {

    private val states = items.associate { it.name to it.enabled }.toMutableMap()
    private val authStates = items.associate {
        it.name to settings.isCapabilityAuthRequired(it.name, it.authRequiredDefault)
    }.toMutableMap()
    private val bindings = mutableMapOf<String, ItemCapabilityCardBinding>()

    fun bindAll() {
        container.removeAllViews()
        bindings.clear()
        items.forEach { capability ->
            val itemBinding = ItemCapabilityCardBinding.inflate(
                LayoutInflater.from(container.context), container, false
            )
            // Chaque cell occupe 1 colonne sur 2, largeur égale
            val params = GridLayout.LayoutParams().apply {
                columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1, 1f)
                width = 0
                height = GridLayout.LayoutParams.WRAP_CONTENT
            }
            itemBinding.root.layoutParams = params
            bindings[capability.name] = itemBinding
            bind(itemBinding, capability)
            container.addView(itemBinding.root)
        }
    }

    private fun bind(binding: ItemCapabilityCardBinding, capability: Capability) {
        val ctx = binding.root.context
        binding.ivCapabilityIcon.setImageResource(capability.iconRes)
        binding.tvCapabilityName.text = ctx.getString(capability.labelRes)

        binding.btnCapabilityInfo.setOnClickListener {
            showInfoBottomSheet(ctx, capability)
        }

        binding.switchCapability.setOnCheckedChangeListener(null)
        binding.switchCapability.isChecked = states[capability.name] ?: false
        updateSwitchColor(binding, states[capability.name] ?: false)
        binding.switchCapability.setOnCheckedChangeListener { _, isChecked ->
            states[capability.name] = isChecked
            updateSwitchColor(binding, isChecked)
            onToggle(capability, isChecked)
        }

        binding.switchAuthRequired.setOnCheckedChangeListener(null)
        binding.switchAuthRequired.isChecked = authStates[capability.name] ?: capability.authRequiredDefault
        updateAuthSwitchColor(binding, authStates[capability.name] ?: capability.authRequiredDefault)
        binding.switchAuthRequired.setOnCheckedChangeListener { _, isChecked ->
            authStates[capability.name] = isChecked
            updateAuthSwitchColor(binding, isChecked)
            onAuthRequiredToggle(capability, isChecked)
        }
    }

    private fun showInfoBottomSheet(ctx: android.content.Context, capability: Capability) {
        val sheet = BottomSheetDialog(ctx)
        val view = LayoutInflater.from(ctx).inflate(
            android.R.layout.simple_list_item_2, null, false
        )
        // Utiliser un layout simple TextView pour éviter une dépendance sur un nouveau fichier XML
        val tv = TextView(ctx).apply {
            setPadding(64, 48, 64, 64)
            textSize = 15f
            setTextColor(ContextCompat.getColor(ctx, R.color.hasan_text_primary))
            text = "${ctx.getString(capability.labelRes)}\n\n${ctx.getString(capability.descriptionRes)}"
            setBackgroundColor(ContextCompat.getColor(ctx, R.color.hasan_bg_card))
        }
        sheet.setContentView(tv)
        sheet.show()
    }

    private fun updateSwitchColor(binding: ItemCapabilityCardBinding, enabled: Boolean) {
        val thumbColor = if (enabled) R.color.hasan_accent else R.color.hasan_text_secondary
        val trackColor = if (enabled) R.color.hasan_accent_dim else R.color.hasan_border
        binding.switchCapability.thumbTintList = ColorStateList.valueOf(
            ContextCompat.getColor(binding.root.context, thumbColor)
        )
        binding.switchCapability.trackTintList = ColorStateList.valueOf(
            ContextCompat.getColor(binding.root.context, trackColor)
        )
    }

    private fun updateAuthSwitchColor(binding: ItemCapabilityCardBinding, enabled: Boolean) {
        val thumbColor = if (enabled) R.color.hasan_accent else R.color.hasan_text_secondary
        val trackColor = if (enabled) R.color.hasan_accent_dim else R.color.hasan_border
        binding.switchAuthRequired.thumbTintList = ColorStateList.valueOf(
            ContextCompat.getColor(binding.root.context, thumbColor)
        )
        binding.switchAuthRequired.trackTintList = ColorStateList.valueOf(
            ContextCompat.getColor(binding.root.context, trackColor)
        )
    }

    fun setEnabled(name: String, enabled: Boolean) {
        val capability = items.find { it.name == name } ?: return
        states[name] = enabled
        bindings[name]?.let { bind(it, capability) }
    }
}

