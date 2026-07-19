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
import com.hasan.v1.ui.components.CutCornerPanel
import com.hasan.v1.ui.components.HasanToggle
import com.hasan.v1.ui.components.TagPill
import com.hasan.v1.ui.theme.HasanColors
import com.hasan.v1.ui.theme.HasanDimens
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
    val onMenuClick: () -> Unit,
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
        ToolsPermissionsHeader(onMenuClick = callbacks.onMenuClick)

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = HasanDimens.SpacingL),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = HasanDimens.SpacingM),
            verticalArrangement = Arrangement.spacedBy(HasanDimens.SpacingM)
        ) {
            items(state.capabilities, key = { it.capability.name }) { item ->
                CapabilityCard(item = item, callbacks = callbacks)
            }
        }
    }
}

@Composable
private fun ToolsPermissionsHeader(onMenuClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = HasanDimens.SpacingL, vertical = HasanDimens.SpacingM),
        verticalAlignment = Alignment.CenterVertically
    ) {
        com.hasan.v1.ui.components.HasanIconButton(
            iconRes = R.drawable.ic_menu_hamburger,
            contentDescription = "Menu",
            onClick = onMenuClick
        )
        Spacer(modifier = Modifier.width(HasanDimens.SpacingM))
        Text(
            text = "TOOLS & PERMISSIONS",
            color = HasanColors.TextPrimary,
            fontFamily = IBMPlexSans,
            fontWeight = FontWeight.Bold,
            fontSize = HasanDimens.TextTitle
        )
    }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(HasanDimens.BorderWidth)
            .background(HasanColors.Border)
    )
}

@Composable
private fun CapabilityCard(item: CapabilityUiState, callbacks: ToolsPermissionsCallbacks) {
    val capability = item.capability
    CutCornerPanel(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(HasanDimens.SpacingM)) {

            // ── Icône + nom + badge tool LLM ─────────────────────────────
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    painter = painterResource(capability.iconRes),
                    contentDescription = null,
                    tint = HasanColors.Accent,
                    modifier = Modifier.size(HasanDimens.IconMedium)
                )
                Spacer(modifier = Modifier.width(HasanDimens.SpacingS))
                Text(
                    text = stringResource(capability.labelRes),
                    color = HasanColors.TextPrimary,
                    fontFamily = IBMPlexSans,
                    fontWeight = FontWeight.Bold,
                    fontSize = HasanDimens.TextBody,
                    modifier = Modifier.weight(1f)
                )
            }

            Spacer(modifier = Modifier.height(HasanDimens.SpacingS))

            TagPill(text = "$TOOL_NAME_PREFIX${capability.name}")

            Spacer(modifier = Modifier.height(HasanDimens.SpacingS))

            Text(
                text = stringResource(capability.descriptionRes),
                color = HasanColors.TextSecondary,
                fontFamily = IBMPlexSans,
                fontSize = HasanDimens.TextBodyMedium
            )

            // ── Badge permission Android ──────────────────────────────────
            if (item.permissionState != CapabilityPermissionState.NOT_APPLICABLE) {
                Spacer(modifier = Modifier.height(HasanDimens.SpacingS))
                PermissionStateBadge(item.permissionState)
            }

            // ── Paramètres attendus ────────────────────────────────────────
            if (capability.parameters.isNotEmpty()) {
                Spacer(modifier = Modifier.height(HasanDimens.SpacingM))
                Box(modifier = Modifier.fillMaxWidth().height(HasanDimens.BorderWidth).background(HasanColors.Border))
                Spacer(modifier = Modifier.height(HasanDimens.SpacingM))
                Text(
                    text = "PARAMÈTRES",
                    color = HasanColors.TextMutedA11y,
                    fontFamily = IBMPlexMono,
                    fontSize = HasanDimens.TextLabelSmall,
                    letterSpacing = 1.sp,
                    modifier = Modifier.padding(bottom = 6.dp)
                )
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    capability.parameters.forEach { param -> ParamSpecRow(param) }
                }
            }

            Spacer(modifier = Modifier.height(HasanDimens.SpacingM))
            Box(modifier = Modifier.fillMaxWidth().height(HasanDimens.BorderWidth).background(HasanColors.Border))
            Spacer(modifier = Modifier.height(HasanDimens.SpacingM))

            // ── Toggles ─────────────────────────────────────────────────
            ToggleRow(
                label = "Activer",
                checked = item.enabled,
                onCheckedChange = { checked -> callbacks.onToggleEnabled(capability, checked) }
            )
            Spacer(modifier = Modifier.height(HasanDimens.SpacingS))
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
            fontSize = HasanDimens.TextCaption,
            modifier = Modifier.padding(end = 6.dp)
        )
        Text(
            text = "(${paramTypeLabel(param.type)})",
            color = HasanColors.TextMutedA11y,
            fontFamily = IBMPlexMono,
            fontSize = HasanDimens.TextLabelMedium,
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
            fontSize = HasanDimens.TextCaption,
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
            fontSize = HasanDimens.TextBodyLarge,
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
            fontSize = HasanDimens.TextLabelSmall
        )
    }
}
