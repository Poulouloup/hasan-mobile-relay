@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.hasan.v1.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.alpha
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hasan.v1.R
import com.hasan.v1.ui.components.CutCornerPanel
import com.hasan.v1.ui.components.HasanToggle
import com.hasan.v1.ui.theme.ChakraPetch
import com.hasan.v1.ui.theme.HasanColors
import com.hasan.v1.ui.theme.HasanDimens
import com.hasan.v1.ui.theme.HasanShapes
import com.hasan.v1.ui.theme.IBMPlexMono
import com.hasan.v1.ui.theme.IBMPlexSans

/** Modèle affichable d'un moteur TTS natif — reflète TextToSpeech.EngineInfo sans dépendre du SDK Android ici. */
data class TtsEngineOption(val name: String, val label: String)

/** Résultat de health check affiché sous une connexion (webUiConnectionStatus). */
data class ConnectionStatusUi(val ok: Boolean, val message: String)

/**
 * État complet piloté par SettingsFragment — toutes les valeurs affichées, aucune
 * logique métier. Le Fragment reste seul responsable de la persistance (SettingsManager)
 * et des effets de bord (sessions, export, quit).
 */
data class SettingsUiState(
    val relayPaired: Boolean,
    val relayEnabled: Boolean,
    val relayConnectionStatus: com.hasan.v1.network.RelayConnectionStatus,
    val relayManualUrl: String,
    val relayManualCode: String,
    val relayErrorMessage: String?,
    /** Raison du dernier échec de connexion au relay / de GET /health hermes-webui (voir SecureHttp.describeFailure), null si joignable. */
    val relayConnectionError: String?,
    val webUiHealthError: String?,
    val relayDeviceLabel: String,
    val ttsProvider: String,
    val ttsProviderSubOptions: List<Pair<String, String>>,
    val ttsSelectedSubOption: String,
    val nativeEngines: List<TtsEngineOption>,
    val nativeSelectedEngine: String,
    val showNativeEngineSelector: Boolean,
    val ttsEnabled: Boolean,
    val ttsSpeed: Float,
    val ttsVolume: Float,
    val wakeWordEnabled: Boolean,
    val wakeWordSensitivity: Float,
    val wakeWordModels: List<String>,
    val wakeWordSelectedModel: String,
    val batteryOptimizationIgnored: Boolean,
    val hermesProfiles: List<com.hasan.v1.webui.models.HermesProfile>,
    val mcpServers: List<com.hasan.v1.webui.models.McpServer>,
    val webUiServerUrl: String,
    val webUiPassword: String,
    val webUiLoggedIn: Boolean,
    val webUiConnectionStatus: ConnectionStatusUi?,
    val aboutVersion: String,
    val aboutSubtitle: String,
    val aboutWakeWord: String,
    val aboutSttTts: String,
    val aboutFeatures: String
)

/** Callbacks délégués au Fragment — aucune logique métier dans les composables. */
class SettingsCallbacks(
    val onScanQrPairing: () -> Unit,
    val onRelayManualUrlChange: (String) -> Unit,
    val onRelayManualCodeChange: (String) -> Unit,
    val onRelayDeviceLabelChange: (String) -> Unit,
    val onDismissRelayError: () -> Unit,
    val onRelayToggle: (Boolean) -> Unit,
    val onDisconnectWebUi: () -> Unit,
    /** Intercepte l'édition d'un champ secret (mot de passe webui, code pairing) — authentification biométrique avant [onSuccess]. */
    val onAuthRequiredForSecretEdit: (onSuccess: () -> Unit) -> Unit,
    val onTtsProviderChange: (String) -> Unit,
    val onTtsSubOptionChange: (String) -> Unit,
    val onNativeEngineChange: (String) -> Unit,
    val onTtsEnabledChange: (Boolean) -> Unit,
    val onTtsSpeedChange: (Float) -> Unit,
    val onTtsVolumeChange: (Float) -> Unit,
    val onWakeWordEnabledChange: (Boolean) -> Unit,
    val onWakeWordSensitivityChange: (Float) -> Unit,
    val onWakeWordModelChange: (String) -> Unit,
    val onRequestBatteryExemption: () -> Unit,
    val onProfileSelect: (String) -> Unit,
    val onMcpToggle: (String, Boolean) -> Unit,
    val onWebUiServerUrlChange: (String) -> Unit,
    val onWebUiPasswordChange: (String) -> Unit,
    val onWebUiConnect: () -> Unit,
    val onOpenLogs: () -> Unit,
    val onMenuClick: () -> Unit
)

private val sectionTitleColor = HasanColors.AccentLight

/**
 * .section-label du mockup (ligne 394-399) — préfixe "// " en text-disabled généré par
 * ::before (couleur différente du reste du texte, donc deux Text plutôt qu'un préfixe
 * inline), font-size:11.5px (pas TextLabelSmall=9sp), color:var(--accent) (AccentLight,
 * pas Accent/deep — trop sombre pour ce rôle), border-top dashed sauf pour :first-child.
 */
@Composable
private fun SectionTitle(text: String, isFirst: Boolean = false) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(
                if (isFirst) Modifier
                else Modifier.dashedBorderTop(HasanColors.BorderStrong).padding(top = 14.dp)
            )
    ) {
        Text(text = "// ", color = HasanColors.TextMuted, fontFamily = IBMPlexMono, fontSize = 11.5.sp, letterSpacing = 1.sp)
        Text(
            text = text,
            color = sectionTitleColor,
            fontFamily = IBMPlexMono,
            fontSize = 11.5.sp,
            letterSpacing = 1.sp
        )
    }
}

private fun Modifier.dashedBorderTop(color: Color): Modifier = drawBehind {
    drawLine(
        color = color,
        start = Offset(0f, 0f),
        end = Offset(size.width, 0f),
        strokeWidth = 1.dp.toPx(),
        pathEffect = PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 4.dp.toPx()), 0f)
    )
}

/**
 * .card du mockup (ligne 287-291, ex: 1049-1051 pour Serveurs MCP) — padding:16px sur la
 * card elle-même, PUIS .list-row{padding:13px 2px} à l'intérieur. Sans le padding ici, le
 * texte/statut des lignes colle directement au bord de la card (2dp de SettingsRow ne
 * suffit pas seul — il s'ajoute au padding de la card, pas le remplace).
 */
@Composable
private fun SettingsSection(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column {
        SectionTitle(title)
        CutCornerPanel(modifier = Modifier.fillMaxWidth(), shape = HasanShapes.panel(cut = 12.dp)) {
            Column(modifier = Modifier.padding(HasanDimens.SpacingL), content = content)
        }
    }
}

/** Panel .settings-panel utilisé pour les contrôles riches (sliders, radios, boutons) — padding interne 16dp. */
@Composable
private fun SettingsControlPanel(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column {
        SectionTitle(title)
        CutCornerPanel(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(HasanDimens.SpacingL), content = content)
        }
    }
}

/**
 * Ligne .settings-row : label à gauche, contenu (valeur mono, toggle...) à droite,
 * séparateur horizontal sauf sur la dernière ligne du panel (géré par l'appelant via `showDivider`).
 */
// .list-row du mockup (ligne 402-408) — padding:13px 2px, min-height:44px, .rl-title
// font-size:14.5px, .rl-sub font-size:12px (pas TextBodyMedium=12sp/TextCaption=11sp).
@Composable
private fun SettingsRow(
    label: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    showDivider: Boolean = true,
    trailing: @Composable () -> Unit
) {
    Column(modifier = modifier) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 44.dp)
                .padding(horizontal = 2.dp, vertical = 13.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = label,
                    color = HasanColors.TextPrimary,
                    fontFamily = IBMPlexSans,
                    fontSize = 14.5.sp
                )
                if (subtitle != null) {
                    Text(
                        text = subtitle,
                        color = HasanColors.TextMutedA11y,
                        fontFamily = IBMPlexMono,
                        fontSize = 12.sp,
                        modifier = Modifier.padding(top = 1.dp)
                    )
                }
            }
            trailing()
        }
        if (showDivider) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(HasanDimens.BorderWidth)
                    .background(HasanColors.Border)
            )
        }
    }
}

/**
 * Valeur en lecture seule d'une .settings-row (.settings-row-value : mono, texte secondaire).
 * Largeur bornée par un maximum plutôt que fixe, avec wrap explicite — une valeur
 * longue (ex: la liste de fonctionnalités dans "À propos") doit passer à la ligne
 * proprement plutôt que se compresser visuellement dans une largeur figée.
 */
@Composable
private fun SettingsRowValue(text: String) {
    Text(
        text = text,
        color = HasanColors.TextSecondary,
        fontFamily = IBMPlexMono,
        fontSize = HasanDimens.TextLabelMedium,
        textAlign = androidx.compose.ui.text.style.TextAlign.End,
        modifier = Modifier.widthIn(max = 180.dp)
    )
}

/** Bouton crayon compact — bascule une .settings-row en mode édition inline. */
@Composable
private fun EditPencilButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .size(HasanDimens.TouchTarget)
            .clip(CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Image(
            painter = painterResource(R.drawable.ic_edit_small),
            contentDescription = "Modifier",
            colorFilter = androidx.compose.ui.graphics.ColorFilter.tint(HasanColors.TextSecondary),
            modifier = Modifier.size(13.dp)
        )
    }
}

/**
 * Ligne éditable : affiche la valeur + crayon en mode lecture, bascule vers un OutlinedTextField
 * compact inline au clic sur le crayon. État d'édition purement local (pas de champ UiState).
 *
 * [onAuthRequiredForEdit] optionnel : si fourni, le clic sur le crayon lui délègue le passage en
 * édition (authentification biométrique côté Fragment) au lieu de l'appliquer directement — utilisé
 * pour les champs secrets (mot de passe webui, code de pairing).
 */
@Composable
private fun SettingsEditableRow(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    showDivider: Boolean = true,
    placeholder: String = "",
    isSecret: Boolean = false,
    onAuthRequiredForEdit: ((onSuccess: () -> Unit) -> Unit)? = null
) {
    var editing by remember { mutableStateOf(false) }
    var draft by remember(value) { mutableStateOf(value) }
    var secretVisible by remember { mutableStateOf(false) }

    Column(modifier = modifier) {
        if (editing) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = HasanDimens.SpacingM, vertical = HasanDimens.SpacingS),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = label,
                    color = HasanColors.TextPrimary,
                    fontFamily = IBMPlexSans,
                    fontSize = HasanDimens.TextBodyMedium,
                    modifier = Modifier.padding(bottom = HasanDimens.SpacingXs)
                )
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = HasanDimens.SpacingM, vertical = HasanDimens.SpacingS),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = draft,
                    onValueChange = { draft = it; onValueChange(it) },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                    textStyle = androidx.compose.ui.text.TextStyle(fontFamily = IBMPlexMono, fontSize = HasanDimens.TextBodyMedium),
                    placeholder = { Text(placeholder, color = HasanColors.TextMutedA11y, fontSize = HasanDimens.TextBodyMedium) },
                    visualTransformation = if (isSecret && !secretVisible) PasswordVisualTransformation() else VisualTransformation.None,
                    trailingIcon = if (isSecret) {
                        {
                            IconButton(onClick = { secretVisible = !secretVisible }) {
                                Text(
                                    text = if (secretVisible) "Masquer" else "Afficher",
                                    color = HasanColors.TextSecondary,
                                    fontSize = HasanDimens.TextLabelSmall,
                                    fontFamily = IBMPlexMono
                                )
                            }
                        }
                    } else null,
                    colors = hasanTextFieldColors()
                )
                Spacer(modifier = Modifier.width(HasanDimens.SpacingS))
                Box(
                    modifier = Modifier
                        .clip(HasanShapes.panelSmall())
                        .background(HasanColors.Accent)
                        .clickable { editing = false }
                        .padding(horizontal = HasanDimens.SpacingM, vertical = HasanDimens.SpacingS)
                ) {
                    Text(text = "OK", color = HasanColors.TextPrimary, fontFamily = IBMPlexMono, fontSize = HasanDimens.TextCaption)
                }
            }
        } else {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = HasanDimens.SpacingM, vertical = HasanDimens.SpacingM),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = label,
                    color = HasanColors.TextPrimary,
                    fontFamily = IBMPlexSans,
                    fontSize = HasanDimens.TextBodyMedium,
                    modifier = Modifier.weight(1f)
                )
                val displayValue = if (isSecret && value.isNotEmpty()) "•".repeat(value.length.coerceAtMost(10)) else value
                Text(
                    text = displayValue.ifEmpty { placeholder },
                    color = if (displayValue.isEmpty()) HasanColors.TextMutedA11y else HasanColors.TextSecondary,
                    fontFamily = IBMPlexMono,
                    fontSize = HasanDimens.TextLabelMedium,
                    maxLines = 1
                )
                Spacer(modifier = Modifier.width(HasanDimens.SpacingS))
                EditPencilButton(onClick = {
                    if (onAuthRequiredForEdit != null) onAuthRequiredForEdit { editing = true } else editing = true
                })
            }
        }
        if (showDivider) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(HasanDimens.BorderWidth)
                    .background(HasanColors.Border)
            )
        }
    }
}

/** Bouton pleine largeur à coin coupé, fond plein — équivalent .btn-primary du mockup. */
@Composable
fun CutCornerFilledButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    backgroundColor: Color = HasanColors.Accent,
    contentColor: Color = HasanColors.TextPrimary,
    shape: Shape = HasanShapes.panelSmall(),
    icon: Int? = null,
    enabled: Boolean = true
) {
    // .btn du mockup (ligne 321-324) — Chakra Petch capitales letter-spacing, min-height:44px,
    // même règle universelle que CutCornerOutlineButton (pas IBMPlexSans casse normale).
    // .btn[disabled]{ opacity:.4 } du mockup (ligne 349) — pas d'état grisé auparavant.
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 44.dp)
            .clip(shape)
            .alpha(if (enabled) 1f else 0.4f)
            .background(backgroundColor)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(vertical = HasanDimens.SpacingM),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (icon != null) {
            androidx.compose.foundation.Image(
                painter = androidx.compose.ui.res.painterResource(icon),
                contentDescription = null,
                colorFilter = androidx.compose.ui.graphics.ColorFilter.tint(contentColor),
                modifier = Modifier.size(HasanDimens.IconSmall)
            )
            Spacer(modifier = Modifier.width(HasanDimens.SpacingS))
        }
        Text(
            text = text.uppercase(),
            color = contentColor,
            fontFamily = ChakraPetch,
            fontWeight = FontWeight.SemiBold,
            fontSize = HasanDimens.TextSubtitle,
            letterSpacing = 0.8.sp
        )
    }
}

/**
 * Bouton pleine largeur à coin coupé, contour seul — équivalent .btn du mockup (ligne 321-330).
 * TOUS les boutons .btn (peu importe la variante ghost/primary/danger) partagent : police
 * Chakra Petch, capitales forcées, letter-spacing — pas IBM Plex Sans en casse normale
 * (règle universelle du système de boutons, pas une exception pour ce composant précis).
 */
@Composable
fun CutCornerOutlineButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    borderColor: Color = HasanColors.Border,
    contentColor: Color = HasanColors.TextSecondary,
    backgroundColor: Color = HasanColors.BgSurface,
    shape: Shape = HasanShapes.panelSmall()
) {
    Box(
        // .btn du mockup (ligne 321-324) — min-height:var(--tap-min) == 44px, pas garanti
        // par le seul padding vertical (SpacingM*2 + texte ≈ 42dp, en dessous de la cible).
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 44.dp)
            .clip(shape)
            .background(backgroundColor)
            .border(HasanDimens.BorderWidth, borderColor, shape)
            .clickable(onClick = onClick)
            .padding(vertical = HasanDimens.SpacingM),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text.uppercase(),
            color = contentColor,
            fontFamily = ChakraPetch,
            fontWeight = FontWeight.SemiBold,
            fontSize = HasanDimens.TextSubtitle,
            letterSpacing = 0.8.sp
        )
    }
}

/**
 * .link-row du mockup (ligne 409-411, 1005-1008) — icône optionnelle à gauche + texte,
 * chevron/flèche à droite, PAS un .btn (pas de capitales/Chakra Petch) : padding:15px 14px,
 * min-height:44px, font-size:14.5px, casse normale IBM Plex Sans.
 */
@Composable
private fun LinkRow(text: String, onClick: () -> Unit, leadingIcon: Int? = null, trailingIcon: Int = R.drawable.ic_chevron_r) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 44.dp)
            .clip(HasanShapes.panel(cut = 12.dp))
            .background(HasanColors.BgSurface2)
            .border(HasanDimens.BorderWidth, HasanColors.Border, HasanShapes.panel(cut = 12.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 15.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (leadingIcon != null) {
                Image(
                    painter = painterResource(leadingIcon),
                    contentDescription = null,
                    colorFilter = androidx.compose.ui.graphics.ColorFilter.tint(HasanColors.TextPrimary),
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(11.dp))
            }
            Text(text = text, color = HasanColors.TextPrimary, fontFamily = IBMPlexSans, fontSize = 14.5.sp)
        }
        Image(
            painter = painterResource(trailingIcon),
            contentDescription = null,
            colorFilter = androidx.compose.ui.graphics.ColorFilter.tint(HasanColors.Accent),
            modifier = Modifier.size(HasanDimens.IconSmall)
        )
    }
}

@Composable
fun SettingsScreen(
    state: SettingsUiState,
    callbacks: SettingsCallbacks
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(HasanColors.BgBase)
    ) {
        com.hasan.v1.ui.components.HasanMinimalHeader(callbacks.onMenuClick, title = "Paramètres")
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = HasanDimens.SpacingL, vertical = HasanDimens.SpacingS),
            verticalArrangement = Arrangement.spacedBy(HasanDimens.SpacingXl)
        ) {
            // Ordre : Connexion Hermes → Voix → Wake Word → Profil → Serveurs MCP →
            // Logs → À propos. Gestion des sessions déplacée entièrement dans le
            // drawer (voir HasanDrawer.kt), "Tools & Permissions" promu en onglet
            // à part entière (HasanNavTab.TOOLS) — plus de section dédiée ici.
            ConnectionSection(state, callbacks)
            VoiceSection(state, callbacks)
            WakeWordSection(state, callbacks)
            ProfileSection(state, callbacks)
            McpServersSection(state, callbacks)
            LogsSection(callbacks)
            AboutSection(state)

            Spacer(modifier = Modifier.height(HasanDimens.SpacingS))
        }
    }
}

// ─────────────────────────── Connexion Hermes ──────────────────────────────────
//
// Rework : un seul point d'entrée (scan QR) configure les deux connexions d'un
// coup quand le QR les porte (pairFromQr le fait déjà côté ViewModel), un bloc
// de statut unifié montre les deux d'un coup d'œil, et la saisie manuelle
// (repli en second recours, relay-only pour le pairing manuel — le webui
// manuel reste dans l'accordéon aussi) est repliée par défaut. Le relay est
// traité comme sensible : switch dédié, activation protégée par
// authentification biométrique/PIN côté SettingsFragment (le Composable ne
// fait qu'exposer l'intention via onRelayToggle, jamais l'authentification
// elle-même). L'ancienne section "config héritée" (settings.serverUrl/
// authToken, vestige du flux HTTP relay pré-migration webui) reste retirée.

@Composable
private fun ConnectionSection(state: SettingsUiState, callbacks: SettingsCallbacks) {
    Column(verticalArrangement = Arrangement.spacedBy(HasanDimens.SpacingL)) {
        SectionTitle("CONNEXIONS", isFirst = true)

        state.relayErrorMessage?.let { message ->
            RelayErrorBanner(message = message, onDismiss = callbacks.onDismissRelayError)
        }

        ConnectionStatusPanel(state, callbacks)

        CutCornerFilledButton(
            text = "Scanner un QR de pairing",
            onClick = callbacks.onScanQrPairing,
            icon = com.hasan.v1.R.drawable.ic_qr_code
        )
        Text(
            text = "Configure automatiquement le chat et le relay si le QR les inclut.",
            color = HasanColors.TextMutedA11y,
            fontSize = HasanDimens.TextCaption,
            modifier = Modifier.padding(top = HasanDimens.SpacingXs, start = HasanDimens.SpacingXs)
        )

        ManualConnectionAccordion(state, callbacks)
    }
}

/** Statut des deux connexions d'un coup d'œil — chat (texte + déconnexion) et relay (switch protégé). */
@Composable
private fun ConnectionStatusPanel(state: SettingsUiState, callbacks: SettingsCallbacks) {
    // .card du mockup (ligne 970-982) — UN SEUL niveau de panel (--notch:12px hérité de
    // :root), pas de sous-panel .settings-group imbriqué à l'intérieur : Chat/Relay sont
    // des .list-row directement sur le fond de la card, séparées par leur propre
    // border-bottom (voir SettingsRow), pas par un second fond gris.
    CutCornerPanel(modifier = Modifier.fillMaxWidth(), shape = HasanShapes.panel(cut = 12.dp)) {
        Column(modifier = Modifier.padding(HasanDimens.SpacingL)) {
            SettingsRow(label = "Chat", subtitle = "hermes-webui") {
                ConnectionStatusBadge(
                    text = state.webUiConnectionStatus?.message
                        ?: if (state.webUiLoggedIn) "Connecté" else "Non connecté",
                    success = state.webUiLoggedIn
                )
            }
            SettingsRow(label = "Relay", subtitle = "Actions téléphone", showDivider = false) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    ConnectionStatusBadge(
                        text = relayStatusLabel(state.relayPaired, state.relayEnabled, state.relayConnectionStatus),
                        success = state.relayPaired && state.relayEnabled
                    )
                    Spacer(modifier = Modifier.width(HasanDimens.SpacingS))
                    HasanToggle(checked = state.relayEnabled, onCheckedChange = callbacks.onRelayToggle)
                }
            }

            // Le détail n'est affiché que pour une connexion configurée et voulue —
            // sinon "injoignable" s'afficherait sur une install jamais appairée.
            val errorDetails = listOfNotNull(
                state.webUiHealthError?.takeIf { state.webUiServerUrl.isNotBlank() }?.let { "Chat : $it" },
                state.relayConnectionError?.takeIf { state.relayPaired && state.relayEnabled }?.let { "Relay : $it" }
            )
            errorDetails.forEach { detail ->
                Text(
                    text = detail,
                    color = HasanColors.Accent,
                    fontSize = HasanDimens.TextCaption,
                    modifier = Modifier.padding(top = HasanDimens.SpacingS)
                )
            }

            Spacer(modifier = Modifier.height(HasanDimens.SpacingM))

            Row(horizontalArrangement = Arrangement.spacedBy(HasanDimens.SpacingS)) {
                CutCornerFilledButton(
                    text = if (state.webUiLoggedIn) "Se reconnecter" else "Se connecter",
                    onClick = callbacks.onWebUiConnect,
                    modifier = Modifier.weight(1f)
                )
                if (state.webUiLoggedIn) {
                    CutCornerOutlineButton(
                        text = "Déconnecter",
                        onClick = callbacks.onDisconnectWebUi,
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }
    }
}

@Composable
private fun RelayErrorBanner(message: String, onDismiss: () -> Unit) {
    CutCornerPanel(
        modifier = Modifier.fillMaxWidth().padding(vertical = HasanDimens.SpacingXs),
        backgroundColor = HasanColors.BgSurface,
        borderColor = HasanColors.Accent
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(HasanDimens.SpacingM),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = message,
                color = HasanColors.Accent,
                fontSize = HasanDimens.TextBodyMedium,
                modifier = Modifier.weight(1f)
            )
            Image(
                painter = painterResource(R.drawable.ic_close),
                contentDescription = "Fermer",
                colorFilter = androidx.compose.ui.graphics.ColorFilter.tint(HasanColors.TextMutedA11y),
                modifier = Modifier
                    .size(HasanDimens.IconSmall)
                    .padding(start = HasanDimens.SpacingS)
                    .clickable(onClick = onDismiss)
            )
        }
    }
}

/**
 * Menu dépliant "Configuration manuelle" — replié par défaut, second recours
 * derrière le scan QR. Regroupe les 4 champs existants (URL/mot de passe
 * hermes-webui, URL/code relay) tels quels, avec leurs boutons d'action
 * respectifs — même chevron-rotation que SkillsScreen.CategoryHeader.
 */
@Composable
private fun ManualConnectionAccordion(state: SettingsUiState, callbacks: SettingsCallbacks) {
    var expanded by remember { mutableStateOf(false) }
    val rotation by androidx.compose.animation.core.animateFloatAsState(
        targetValue = if (expanded) 90f else 0f,
        label = "manual-connection-chevron-rotation"
    )

    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded }
                .padding(vertical = HasanDimens.SpacingS),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "▶",
                color = HasanColors.TextMutedA11y,
                fontSize = HasanDimens.TextLabelSmall,
                modifier = Modifier.rotate(rotation).padding(end = 6.dp)
            )
            Text(
                text = "CONFIGURATION MANUELLE",
                color = HasanColors.TextMutedA11y,
                fontFamily = IBMPlexMono,
                fontSize = HasanDimens.TextLabelSmall,
                letterSpacing = 1.sp,
                modifier = Modifier.weight(1f)
            )
        }

        if (expanded) {
            CutCornerPanel(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(HasanDimens.SpacingL)) {
                    Text(
                        text = "Chat (hermes-webui)",
                        color = HasanColors.TextSecondary,
                        fontFamily = IBMPlexMono,
                        fontSize = HasanDimens.TextLabelSmall,
                        letterSpacing = 1.sp,
                        modifier = Modifier.padding(bottom = HasanDimens.SpacingS)
                    )
                    Column(modifier = Modifier.clip(HasanShapes.panelSmall()).background(HasanColors.BgSurface2)) {
                        SettingsEditableRow(
                            label = "URL hermes-webui",
                            value = state.webUiServerUrl,
                            onValueChange = callbacks.onWebUiServerUrlChange,
                            placeholder = "https://serveur"
                        )
                        SettingsEditableRow(
                            label = "Mot de passe",
                            value = state.webUiPassword,
                            onValueChange = callbacks.onWebUiPasswordChange,
                            placeholder = "mot de passe",
                            isSecret = true,
                            showDivider = false,
                            onAuthRequiredForEdit = callbacks.onAuthRequiredForSecretEdit
                        )
                    }

                    Spacer(modifier = Modifier.height(HasanDimens.SpacingL))

                    Text(
                        text = "Relay (actions téléphone)",
                        color = HasanColors.TextSecondary,
                        fontFamily = IBMPlexMono,
                        fontSize = HasanDimens.TextLabelSmall,
                        letterSpacing = 1.sp,
                        modifier = Modifier.padding(bottom = HasanDimens.SpacingS)
                    )
                    Column(modifier = Modifier.clip(HasanShapes.panelSmall()).background(HasanColors.BgSurface2)) {
                        SettingsEditableRow(
                            label = "URL du relay",
                            value = state.relayManualUrl,
                            onValueChange = callbacks.onRelayManualUrlChange,
                            placeholder = "https://relay:8767"
                        )
                        SettingsEditableRow(
                            label = "Code de pairing",
                            value = state.relayManualCode,
                            onValueChange = callbacks.onRelayManualCodeChange,
                            placeholder = "ABC123",
                            isSecret = true,
                            onAuthRequiredForEdit = callbacks.onAuthRequiredForSecretEdit
                        )
                        SettingsEditableRow(
                            label = "Nom de cet appareil",
                            value = state.relayDeviceLabel,
                            onValueChange = callbacks.onRelayDeviceLabelChange,
                            placeholder = "Téléphone de Loup",
                            showDivider = false
                        )
                    }
                    Text(
                        text = "Le nom de l'appareil permet à Hermes de le distinguer quand plusieurs appareils (téléphone, Hasan Desktop…) sont connectés en même temps.",
                        color = HasanColors.TextMutedA11y,
                        fontSize = HasanDimens.TextCaption,
                        modifier = Modifier.padding(top = HasanDimens.SpacingXs, start = HasanDimens.SpacingXs)
                    )
                    Text(
                        text = "Le bouton \"Se connecter\" ci-dessus appaire le relay et connecte le chat en un seul geste.",
                        color = HasanColors.TextMutedA11y,
                        fontSize = HasanDimens.TextCaption,
                        modifier = Modifier.padding(top = HasanDimens.SpacingS, start = HasanDimens.SpacingXs)
                    )
                }
            }
        }
    }
}

private fun relayStatusLabel(
    paired: Boolean,
    enabled: Boolean,
    status: com.hasan.v1.network.RelayConnectionStatus
): String {
    // "Connecté" seul (pas "Appairé — connecté") côté CONNECTED — section 7 du brief
    // next_update/PROMPT_CLAUDE_CODE.md : le sous-titre "Actions téléphone" porte déjà
    // le contexte "relay/appairage", inutile de le répéter dans le badge de statut.
    if (!paired) return "Non appairé"
    if (!enabled) return "Appairé — désactivé"
    return when (status) {
        com.hasan.v1.network.RelayConnectionStatus.CONNECTED -> "Connecté"
        com.hasan.v1.network.RelayConnectionStatus.CONNECTING -> "Appairé — connexion…"
        com.hasan.v1.network.RelayConnectionStatus.RECONNECTING -> "Appairé — reconnexion…"
        com.hasan.v1.network.RelayConnectionStatus.DISCONNECTED -> "Appairé — déconnecté"
    }
}

/**
 * Serveurs MCP configurés (~/.hermes/config.yaml) — toggle actif/inactif par
 * serveur, sans écran dédié. Un serveur avec toggle_supported=false (contrôlé
 * autrement côté serveur) affiche son état en lecture seule plutôt qu'un
 * toggle inerte trompeur.
 */
@Composable
private fun McpServersSection(state: SettingsUiState, callbacks: SettingsCallbacks) {
    if (state.mcpServers.isEmpty()) return
    SettingsSection(title = "SERVEURS MCP") {
        state.mcpServers.forEachIndexed { index, server ->
            SettingsRow(
                label = server.name,
                showDivider = index < state.mcpServers.lastIndex
            ) {
                if (server.toggleSupported) {
                    HasanToggle(
                        checked = server.enabled,
                        onCheckedChange = { checked -> callbacks.onMcpToggle(server.name, checked) }
                    )
                } else {
                    HasanBadge(text = if (server.enabled) "Actif" else "Inactif", success = server.enabled)
                }
            }
        }
    }
}

// ─────────────────────────── Voix ───────────────────────────────────────────────
//
// TTS complet dans un seul panel fusionné : switch d'activation en haut, puis
// sliders vitesse/volume et choix du moteur (natif/Edge + sous-voix) — tout
// visible directement, sans disclosure "options avancées" (cf. disposition.md).

@Composable
private fun VoiceSection(state: SettingsUiState, callbacks: SettingsCallbacks) {
    SettingsControlPanel(title = "VOIX") {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "Synthèse vocale (TTS)",
                color = HasanColors.TextPrimary,
                fontSize = HasanDimens.TextBody,
                modifier = Modifier.weight(1f)
            )
            HasanToggle(checked = state.ttsEnabled, onCheckedChange = callbacks.onTtsEnabledChange)
        }

        Divider()

        LabeledSlider(
            label = "Vitesse",
            valueText = "%.1fx".format(state.ttsSpeed),
            value = state.ttsSpeed,
            valueRange = 0.5f..2.0f,
            steps = ((2.0f - 0.5f) / 0.1f).toInt() - 1,
            onValueChange = callbacks.onTtsSpeedChange
        )

        Spacer(modifier = Modifier.height(HasanDimens.SpacingM))

        LabeledSlider(
            label = "Volume",
            valueText = "${state.ttsVolume.toInt()}%",
            value = state.ttsVolume,
            valueRange = 0f..100f,
            steps = 99,
            onValueChange = callbacks.onTtsVolumeChange
        )

        Divider()

        ProviderChoiceRow(
            label = "Voix Android (hors ligne)",
            selected = state.ttsProvider == com.hasan.v1.SettingsManager.TTS_PROVIDER_NATIVE,
            onClick = { callbacks.onTtsProviderChange(com.hasan.v1.SettingsManager.TTS_PROVIDER_NATIVE) }
        )
        Spacer(modifier = Modifier.height(6.dp))
        ProviderChoiceRow(
            label = "Voix Edge TTS (en ligne, gratuit)",
            selected = state.ttsProvider == com.hasan.v1.SettingsManager.TTS_PROVIDER_EDGE,
            onClick = { callbacks.onTtsProviderChange(com.hasan.v1.SettingsManager.TTS_PROVIDER_EDGE) }
        )

        if (state.ttsProvider == com.hasan.v1.SettingsManager.TTS_PROVIDER_EDGE) {
            // Sous-sélecteur voix Edge TTS
            Divider()
            RadioOptionGroup(
                options = state.ttsProviderSubOptions,
                selected = state.ttsSelectedSubOption,
                onSelect = callbacks.onTtsSubOptionChange
            )
        } else {
            // Sous-sélecteur moteur natif, puis voix du moteur choisi
            if (state.nativeEngines.isNotEmpty()) {
                Divider()
                // "Reconnaissance vocale (STT)" du mockup (ligne 1021, .rl-sub) — label absent
                // avant cette correction, le radio Google apparaissait seul sans en-tête.
                Text(
                    text = "Reconnaissance vocale (STT)",
                    color = HasanColors.TextPrimary,
                    fontSize = HasanDimens.TextBody,
                    modifier = Modifier.padding(top = HasanDimens.SpacingM, bottom = 6.dp)
                )
                RadioOptionGroup(
                    options = state.nativeEngines.map { it.name to it.label },
                    selected = state.nativeSelectedEngine,
                    onSelect = callbacks.onNativeEngineChange
                )
            }
            if (state.showNativeEngineSelector && state.ttsProviderSubOptions.isNotEmpty()) {
                Divider()
                RadioOptionGroup(
                    options = state.ttsProviderSubOptions,
                    selected = state.ttsSelectedSubOption,
                    onSelect = callbacks.onTtsSubOptionChange
                )
            }
        }
    }
}

// .radio-row du mockup (ligne 371, 1018-1019) — pas de bordure ni de fond, même état
// sélectionné/non-sélectionné, seul le petit indicateur .radio change. Le cadre rouge
// complet ajoutait un contour absent du mockup.
@Composable
private fun ProviderChoiceRow(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = HasanDimens.SpacingS),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioDot(selected = selected)
        Spacer(modifier = Modifier.width(10.dp))
        Text(text = label, color = HasanColors.TextPrimary, fontSize = HasanDimens.TextBody)
    }
}

@Composable
private fun RadioOptionGroup(
    options: List<Pair<String, String>>,
    selected: String,
    onSelect: (String) -> Unit
) {
    Column {
        options.forEach { (value, label) ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onSelect(value) }
                    .padding(vertical = HasanDimens.SpacingS),
                verticalAlignment = Alignment.CenterVertically
            ) {
                RadioDot(selected = value == selected)
                Spacer(modifier = Modifier.width(10.dp))
                Text(text = label, color = HasanColors.TextPrimary, fontSize = HasanDimens.TextBody)
            }
        }
    }
}

/**
 * .badge.badge-success avec .dot du mockup (ligne 973/978) — même fond+bordure teintés que
 * HasanBadge, plus un point 7px avec halo (box-shadow 0 0 0 3px success-soft) avant le texte.
 * "Connecté" côté Chat/Relay affichait un simple point + texte sans aucun fond de badge
 * avant cette correction — écart visible en comparant à l'échelle réelle du mockup.
 */
@Composable
private fun ConnectionStatusBadge(text: String, success: Boolean) {
    val contentColor = if (success) HasanColors.Success else HasanColors.TextMutedA11y
    val bgColor = if (success) HasanColors.SuccessSoft else HasanColors.BgSurface3
    val borderColor = if (success) HasanColors.Success.copy(alpha = 0.35f) else HasanColors.Border
    // .badge du mockup (ligne 377) — rectangle simple, PAS de cut-corner (aucun clip-path
    // sur .badge/.badge-success dans le CSS source).
    Row(
        modifier = Modifier
            .background(bgColor)
            .border(HasanDimens.BorderWidth, borderColor)
            .padding(horizontal = 9.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(13.dp)
                .clip(CircleShape)
                .background(if (success) HasanColors.SuccessSoft else Color.Transparent),
            contentAlignment = Alignment.Center
        ) {
            Box(
                modifier = Modifier
                    .size(7.dp)
                    .clip(CircleShape)
                    .background(contentColor)
            )
        }
        Spacer(modifier = Modifier.width(5.dp))
        Text(text = text, color = contentColor, fontFamily = IBMPlexMono, fontSize = 11.sp, letterSpacing = 0.3.sp)
    }
}

/**
 * .badge.badge-success / .badge-muted du mockup (ligne 377-379) — fond + bordure teintés,
 * pas juste du texte coloré seul (écart observé sur "Actif"/"Inactif"/"Autorisée").
 */
@Composable
private fun HasanBadge(text: String, success: Boolean) {
    val contentColor = if (success) HasanColors.Success else HasanColors.TextMutedA11y
    val bgColor = if (success) HasanColors.SuccessSoft else HasanColors.BgSurface3
    val borderColor = if (success) HasanColors.Success.copy(alpha = 0.35f) else HasanColors.Border
    // .badge du mockup (ligne 377) — rectangle simple, pas de cut-corner.
    Box(
        modifier = Modifier
            .background(bgColor)
            .border(HasanDimens.BorderWidth, borderColor)
            .padding(horizontal = 9.dp, vertical = 4.dp)
    ) {
        Text(text = text, color = contentColor, fontFamily = IBMPlexMono, fontSize = 11.sp, letterSpacing = 0.3.sp)
    }
}

// .radio du mockup (ligne 372-374) — 19px, bordure 1.5px border-strong (accent si .on),
// marqueur intérieur CARRÉ (inset:5px, aucun border-radius sur ::after), pas un rond.
@Composable
private fun RadioDot(selected: Boolean) {
    Box(
        modifier = Modifier
            .size(19.dp)
            .clip(CircleShape)
            .border(1.5.dp, if (selected) HasanColors.Accent else HasanColors.BorderStrong, CircleShape),
        contentAlignment = Alignment.Center
    ) {
        if (selected) {
            Box(
                modifier = Modifier
                    .size(9.dp)
                    .background(HasanColors.Accent)
            )
        }
    }
}

@Composable
private fun Divider() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = HasanDimens.SpacingM)
            .height(HasanDimens.BorderWidth)
            .background(HasanColors.Border)
    )
}

@Composable
private fun LabeledSlider(
    label: String,
    valueText: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    steps: Int,
    onValueChange: (Float) -> Unit
) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(text = label, color = HasanColors.TextPrimary, fontSize = HasanDimens.TextBody, modifier = Modifier.weight(1f))
        Text(text = valueText, color = HasanColors.TextSecondary, fontSize = HasanDimens.TextSubtitle)
    }
    // input[type=range] du mockup (ligne 1015/1017) — piste continue, aucun repère de step
    // visible malgré step="0.1"/step natif (rendu navigateur par défaut). Le Slider Material3
    // dessine un tick par step quand steps>0 (pointillés visibles à l'écran, absents du
    // mockup) — track custom sans tick marks, le snapping logique (steps) reste inchangé.
    Slider(
        value = value,
        onValueChange = onValueChange,
        valueRange = valueRange,
        steps = steps,
        thumb = { sliderThumbCircle() },
        track = { sliderState ->
            val fraction = ((sliderState.value - sliderState.valueRange.start) /
                (sliderState.valueRange.endInclusive - sliderState.valueRange.start)).coerceIn(0f, 1f)
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(4.dp)
                    .background(HasanColors.Border)
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(fraction)
                        .height(4.dp)
                        .background(HasanColors.Accent)
                )
            }
        },
        colors = SliderDefaults.colors(
            thumbColor = HasanColors.Accent,
            activeTrackColor = HasanColors.Accent,
            inactiveTrackColor = HasanColors.Border
        )
    )
}

/** Poignée ronde pleine (~16dp) — le thumb par défaut Material3 est une barre verticale épaisse, absente du mockup (input[type=range] natif, poignée circulaire discrète). */
@Composable
private fun sliderThumbCircle() {
    Box(
        modifier = Modifier
            .size(16.dp)
            .clip(CircleShape)
            .background(HasanColors.Accent)
    )
}

// ─────────────────────────── Wake Word ──────────────────────────────────────────
//
// Switch d'activation, slider de sensibilité, et choix du modèle de détection
// en liste à puce — tout visible directement, sans disclosure (cf. disposition.md).

@Composable
private fun WakeWordSection(state: SettingsUiState, callbacks: SettingsCallbacks) {
    SettingsControlPanel(title = "WAKE WORD") {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "Activer \"Ok Hasan\"",
                color = HasanColors.TextPrimary,
                fontSize = HasanDimens.TextBody,
                modifier = Modifier.weight(1f)
            )
            HasanToggle(checked = state.wakeWordEnabled, onCheckedChange = callbacks.onWakeWordEnabledChange)
        }

        Divider()

        Text(
            text = "Sensibilité du wake word",
            color = HasanColors.TextPrimary,
            fontSize = HasanDimens.TextBody,
            modifier = Modifier.padding(bottom = 6.dp)
        )
        // Track custom sans tick marks — même raison que LabeledSlider ci-dessus (input[type=range]
        // du mockup ligne 1030, piste continue malgré step natif).
        Slider(
            value = state.wakeWordSensitivity,
            onValueChange = callbacks.onWakeWordSensitivityChange,
            valueRange = 0.1f..1.0f,
            steps = 8,
            thumb = { sliderThumbCircle() },
            track = { sliderState ->
                val fraction = ((sliderState.value - sliderState.valueRange.start) /
                    (sliderState.valueRange.endInclusive - sliderState.valueRange.start)).coerceIn(0f, 1f)
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(4.dp)
                        .background(HasanColors.Border)
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(fraction)
                            .height(4.dp)
                            .background(HasanColors.Accent)
                    )
                }
            },
            colors = SliderDefaults.colors(
                thumbColor = HasanColors.Accent,
                activeTrackColor = HasanColors.Accent,
                inactiveTrackColor = HasanColors.Border
            )
        )
        // Graduations sous la piste — un trait par palier (9 steps = 10 positions,
        // 0.1 à 1.0 par pas de 0.1), demande explicite pour repérer les crans disponibles.
        Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            repeat(10) {
                Box(
                    modifier = Modifier
                        .width(1.dp)
                        .height(4.dp)
                        .background(HasanColors.BorderStrong)
                )
            }
        }
        Spacer(modifier = Modifier.height(4.dp))
        Row(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = "Moins sensible",
                color = HasanColors.TextSecondary,
                fontSize = HasanDimens.TextLabelMedium,
                modifier = Modifier.weight(1f)
            )
            Text(text = "Plus sensible", color = HasanColors.TextSecondary, fontSize = HasanDimens.TextLabelMedium)
        }

        Divider()

        Text(
            text = "Modèle de détection",
            color = HasanColors.TextPrimary,
            fontSize = HasanDimens.TextBody,
            modifier = Modifier.padding(bottom = 6.dp)
        )
        RadioOptionGroup(
            options = state.wakeWordModels.map { it to it.removeSuffix(".onnx") },
            selected = state.wakeWordSelectedModel,
            onSelect = callbacks.onWakeWordModelChange
        )

        Divider()

        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "Écoute en arrière-plan",
                color = HasanColors.TextPrimary,
                fontSize = HasanDimens.TextBody,
                modifier = Modifier.weight(1f)
            )
            HasanBadge(
                text = if (state.batteryOptimizationIgnored) "Autorisée" else "Non autorisée",
                success = state.batteryOptimizationIgnored
            )
        }
        if (!state.batteryOptimizationIgnored) {
            Text(
                text = "Certains téléphones (Xiaomi, Huawei, Samsung, OnePlus…) coupent le wake word en arrière-plan sans cette autorisation.",
                color = HasanColors.TextSecondary,
                fontSize = HasanDimens.TextLabelMedium,
                modifier = Modifier.padding(top = 4.dp, bottom = HasanDimens.SpacingS)
            )
            CutCornerOutlineButton(
                text = "Autoriser l'écoute en arrière-plan",
                onClick = callbacks.onRequestBatteryExemption
            )
        }
    }
}

// ─────────────────────────── Profil Hermes ──────────────────────────────────────
//
// Changer de profil bascule tout le HERMES_HOME (config/skills/workspace) —
// action lourde, à la différence du choix de modèle par tour (barre de
// composition du Chat). Un seul profil ("default") existe sur le serveur de
// test, mais le sélecteur est déjà fonctionnel (voir WebUiProfilesClient).

@Composable
private fun ProfileSection(state: SettingsUiState, callbacks: SettingsCallbacks) {
    if (state.hermesProfiles.isEmpty()) return
    SettingsControlPanel(title = "PROFIL HERMES") {
        state.hermesProfiles.forEachIndexed { index, profile ->
            ProviderChoiceRow(
                label = "${profile.name} — ${profile.skillCount} skills" +
                    (profile.model?.let { " · $it" } ?: ""),
                selected = profile.isActive,
                onClick = { callbacks.onProfileSelect(profile.name) }
            )
            if (index < state.hermesProfiles.lastIndex) {
                Spacer(modifier = Modifier.height(HasanDimens.SpacingS))
            }
        }
    }
}

// ─────────────────────────── Logs ───────────────────────────────────────────────
//
// Contient uniquement le lien vers l'écran Logs (overlay plein écran, voir
// MainActivity.openLogs()) — pas de rendu inline ici pour ne pas allonger
// l'écran Réglages avec un journal qui peut contenir beaucoup d'événements.

@Composable
private fun LogsSection(callbacks: SettingsCallbacks) {
    Column {
        SectionTitle("JOURNAL")
        LinkRow(text = "Voir les logs", onClick = callbacks.onOpenLogs, trailingIcon = R.drawable.ic_arrow_r)
    }
}

// ─────────────────────────── Groupe : À propos ─────────────────────────────────

@Composable
private fun AboutSection(state: SettingsUiState) {
    SettingsSection(title = "À PROPOS") {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = HasanDimens.SpacingM, vertical = HasanDimens.SpacingM),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // .logo-badge du mockup (ligne 1063, 38px) — vrai logo Hasan (même asset que le
            // header/drawer), pas un glyphe "M" générique sur fond plein.
            com.hasan.v1.ui.components.BrandMark(size = 38.dp)
            Spacer(modifier = Modifier.width(HasanDimens.SpacingM))
            Column {
                Text(text = state.aboutVersion, color = HasanColors.TextPrimary, fontWeight = FontWeight.Bold, fontSize = HasanDimens.TextTitle)
                Text(text = state.aboutSubtitle, color = HasanColors.TextSecondary, fontSize = HasanDimens.TextBodyMedium)
            }
        }
        Box(modifier = Modifier.fillMaxWidth().height(HasanDimens.BorderWidth).background(HasanColors.Border))

        SettingsRow(label = "Wake word") { SettingsRowValue(state.aboutWakeWord) }
        SettingsRow(label = "STT / TTS") { SettingsRowValue(state.aboutSttTts) }
        SettingsRow(label = "Fonctionnalités", showDivider = false) { SettingsRowValue(state.aboutFeatures) }
    }
}

@Composable
private fun hasanTextFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedTextColor = HasanColors.TextPrimary,
    unfocusedTextColor = HasanColors.TextPrimary,
    focusedBorderColor = HasanColors.Accent,
    unfocusedBorderColor = HasanColors.Border,
    cursorColor = HasanColors.Accent,
    focusedContainerColor = HasanColors.BgSurface2,
    unfocusedContainerColor = HasanColors.BgSurface2
)
