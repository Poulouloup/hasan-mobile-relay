package com.hasan.v1.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hasan.v1.Capability
import com.hasan.v1.CapabilityPermissionState
import com.hasan.v1.ParamSpec
import com.hasan.v1.ParamType
import com.hasan.v1.R
import com.hasan.v1.ui.components.CutCornerIconButton
import com.hasan.v1.ui.components.CutCornerPanel
import com.hasan.v1.ui.components.HasanToggle
import com.hasan.v1.ui.components.TagPill
import com.hasan.v1.ui.theme.HasanColors
import com.hasan.v1.ui.theme.IBMPlexMono
import com.hasan.v1.ui.theme.IBMPlexSans

/** Préfixe appliqué au nom court de la capability pour obtenir le nom exact du tool côté LLM Hermes. */
private const val TOOL_NAME_PREFIX = "android_"

/** État persistant d'une capability affiché par l'écran — reflète SettingsManager sans y accéder directement (MVVM). */
data class CapabilityUiState(
    val capability: Capability,
    val enabled: Boolean,
    val authRequired: Boolean,
    val permissionState: CapabilityPermissionState
)

/** État global piloté par le Fragment hôte. */
data class ToolsPermissionsUiState(
    val capabilities: List<CapabilityUiState>
)

/** Callbacks délégués au Fragment — aucune logique métier (permissions runtime, persistance) dans les composables. */
class ToolsPermissionsCallbacks(
    val onBack: () -> Unit,
    val onToggleEnabled: (Capability, Boolean) -> Unit,
    val onToggleAuthRequired: (Capability, Boolean) -> Unit
)

private fun paramTypeLabel(type: ParamType): String = when (type) {
    ParamType.STRING -> "texte"
    ParamType.INT -> "entier"
    ParamType.FLOAT -> "nombre"
    ParamType.BOOLEAN -> "booléen"
}

@Composable
fun ToolsPermissionsScreen(
    state: ToolsPermissionsUiState,
    callbacks: ToolsPermissionsCallbacks
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(HasanColors.BgBase)
    ) {
        ToolsPermissionsHeader(onBack = callbacks.onBack)

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            items(state.capabilities, key = { it.capability.name }) { item ->
                CapabilityCard(item = item, callbacks = callbacks)
            }
        }
    }
}

@Composable
private fun ToolsPermissionsHeader(onBack: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        CutCornerIconButton(onClick = onBack, modifier = Modifier.size(36.dp)) {
            Icon(
                painter = painterResource(R.drawable.ic_close),
                contentDescription = "Retour",
                modifier = Modifier.size(16.dp)
            )
        }
        Spacer(modifier = Modifier.width(12.dp))
        Text(
            text = "TOOLS & PERMISSIONS",
            color = HasanColors.TextPrimary,
            fontFamily = IBMPlexSans,
            fontWeight = FontWeight.Bold,
            fontSize = 16.sp
        )
    }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(HasanColors.Border)
    )
}

@Composable
private fun CapabilityCard(item: CapabilityUiState, callbacks: ToolsPermissionsCallbacks) {
    val capability = item.capability
    CutCornerPanel(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(14.dp)) {

            // ── Icône + nom + badge tool LLM ─────────────────────────────
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    painter = painterResource(capability.iconRes),
                    contentDescription = null,
                    tint = HasanColors.Accent,
                    modifier = Modifier.size(24.dp)
                )
                Spacer(modifier = Modifier.width(10.dp))
                Text(
                    text = stringResource(capability.labelRes),
                    color = HasanColors.TextPrimary,
                    fontFamily = IBMPlexSans,
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp,
                    modifier = Modifier.weight(1f)
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            TagPill(text = "$TOOL_NAME_PREFIX${capability.name}")

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = stringResource(capability.descriptionRes),
                color = HasanColors.TextSecondary,
                fontFamily = IBMPlexSans,
                fontSize = 12.sp
            )

            // ── Badge permission Android ──────────────────────────────────
            if (item.permissionState != CapabilityPermissionState.NOT_APPLICABLE) {
                Spacer(modifier = Modifier.height(8.dp))
                PermissionStateBadge(item.permissionState)
            }

            // ── Paramètres attendus ────────────────────────────────────────
            if (capability.parameters.isNotEmpty()) {
                Spacer(modifier = Modifier.height(10.dp))
                Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(HasanColors.Border))
                Spacer(modifier = Modifier.height(10.dp))
                Text(
                    text = "PARAMÈTRES",
                    color = HasanColors.TextMutedA11y,
                    fontFamily = IBMPlexMono,
                    fontSize = 9.sp,
                    letterSpacing = 1.sp,
                    modifier = Modifier.padding(bottom = 6.dp)
                )
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    capability.parameters.forEach { param -> ParamSpecRow(param) }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))
            Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(HasanColors.Border))
            Spacer(modifier = Modifier.height(10.dp))

            // ── Toggles ─────────────────────────────────────────────────
            ToggleRow(
                label = "Activer",
                checked = item.enabled,
                onCheckedChange = { checked -> callbacks.onToggleEnabled(capability, checked) }
            )
            Spacer(modifier = Modifier.height(8.dp))
            ToggleRow(
                label = "Confirmation requise",
                checked = item.authRequired,
                onCheckedChange = { checked -> callbacks.onToggleAuthRequired(capability, checked) }
            )
        }
    }
}

@Composable
private fun ParamSpecRow(param: ParamSpec) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        Text(
            text = param.name,
            color = HasanColors.Accent,
            fontFamily = IBMPlexMono,
            fontSize = 11.sp,
            modifier = Modifier.padding(end = 6.dp)
        )
        Text(
            text = "(${paramTypeLabel(param.type)})",
            color = HasanColors.TextMutedA11y,
            fontFamily = IBMPlexMono,
            fontSize = 10.sp,
            modifier = Modifier.padding(end = 6.dp)
        )
        if (param.required) {
            TagPill(
                text = "REQUIS",
                backgroundColor = HasanColors.AccentDim,
                contentColor = HasanColors.Accent
            )
            Spacer(modifier = Modifier.width(6.dp))
        }
    }
    if (param.description.isNotBlank()) {
        Text(
            text = param.description,
            color = HasanColors.TextSecondary,
            fontFamily = IBMPlexSans,
            fontSize = 11.sp,
            modifier = Modifier.padding(start = 2.dp, top = 2.dp)
        )
    }
}

@Composable
private fun ToggleRow(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            color = HasanColors.TextPrimary,
            fontFamily = IBMPlexSans,
            fontSize = 12.5.sp,
            modifier = Modifier.weight(1f)
        )
        HasanToggle(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun PermissionStateBadge(state: CapabilityPermissionState) {
    val (label, fg) = when (state) {
        CapabilityPermissionState.GRANTED -> "Permission accordée" to HasanColors.Accent
        CapabilityPermissionState.REQUIRED -> "Permission requise" to HasanColors.TextMutedA11y
        CapabilityPermissionState.NOT_APPLICABLE -> return
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(6.dp)
                .clip(CircleShape)
                .background(fg)
        )
        Spacer(modifier = Modifier.width(6.dp))
        Text(
            text = label,
            color = fg,
            fontFamily = IBMPlexMono,
            fontSize = 9.5.sp
        )
    }
}
