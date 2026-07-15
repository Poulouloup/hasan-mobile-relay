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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hasan.v1.R
import com.hasan.v1.db.HermesSession
import com.hasan.v1.ui.components.CutCornerPanel
import com.hasan.v1.ui.components.HasanToggle
import com.hasan.v1.ui.components.TagPill
import com.hasan.v1.ui.theme.HasanColors
import com.hasan.v1.ui.theme.HasanShapes
import com.hasan.v1.ui.theme.IBMPlexMono
import com.hasan.v1.ui.theme.IBMPlexSans
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Modèle affichable d'un moteur TTS natif — reflète TextToSpeech.EngineInfo sans dépendre du SDK Android ici. */
data class TtsEngineOption(val name: String, val label: String)

/** Résultat de test de connexion affiché sous le bouton "Tester la connexion". */
data class ConnectionStatusUi(val ok: Boolean, val message: String)

/**
 * État complet piloté par SettingsFragment — toutes les valeurs affichées, aucune
 * logique métier. Le Fragment reste seul responsable de la persistance (SettingsManager)
 * et des effets de bord (TOFU, sessions, export, quit).
 */
data class SettingsUiState(
    val serverUrl: String,
    val authToken: String,
    val connectionStatus: ConnectionStatusUi?,
    val relayPaired: Boolean,
    val relayConnectionStatus: com.hasan.v1.network.RelayConnectionStatus,
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
    val sessions: List<HermesSession>,
    val aboutVersion: String,
    val aboutSubtitle: String,
    val aboutWakeWord: String,
    val aboutSttTts: String,
    val aboutFeatures: String
)

/** Callbacks délégués au Fragment — aucune logique métier dans les composables. */
class SettingsCallbacks(
    val onServerUrlChange: (String) -> Unit,
    val onAuthTokenChange: (String) -> Unit,
    val onTestConnection: () -> Unit,
    val onManageCerts: () -> Unit,
    val onScanQrPairing: () -> Unit,
    val onOpenToolsPermissions: () -> Unit,
    val onTtsProviderChange: (String) -> Unit,
    val onTtsSubOptionChange: (String) -> Unit,
    val onNativeEngineChange: (String) -> Unit,
    val onTtsEnabledChange: (Boolean) -> Unit,
    val onTtsSpeedChange: (Float) -> Unit,
    val onTtsVolumeChange: (Float) -> Unit,
    val onWakeWordEnabledChange: (Boolean) -> Unit,
    val onWakeWordSensitivityChange: (Float) -> Unit,
    val onWakeWordModelChange: (String) -> Unit,
    val onExportHistory: () -> Unit,
    val onNewSession: () -> Unit,
    val onSessionTap: (HermesSession) -> Unit,
    val onSessionLongPress: (HermesSession) -> Unit,
    val onQuit: () -> Unit
)

private val sectionTitleColor = HasanColors.Accent

@Composable
private fun SectionTitle(text: String) {
    Text(
        text = text,
        color = sectionTitleColor,
        fontFamily = IBMPlexMono,
        fontWeight = FontWeight.Medium,
        fontSize = 9.sp,
        letterSpacing = 1.5.sp,
        modifier = Modifier.padding(start = 4.dp, bottom = 8.dp)
    )
}

/**
 * Groupe .settings-group : label mono accent + panel à coin coupé contenant des lignes
 * .settings-row empilées (pas de padding interne, chaque SettingsRow se pad elle-même).
 */
@Composable
private fun SettingsSection(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column {
        SectionTitle(title)
        CutCornerPanel(modifier = Modifier.fillMaxWidth()) {
            Column(content = content)
        }
    }
}

/** Panel .settings-panel utilisé pour les contrôles riches (sliders, radios, boutons) — padding interne 16dp. */
@Composable
private fun SettingsControlPanel(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column {
        SectionTitle(title)
        CutCornerPanel(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp), content = content)
        }
    }
}

/**
 * Ligne .settings-row : label à gauche, contenu (valeur mono, toggle...) à droite,
 * séparateur horizontal sauf sur la dernière ligne du panel (géré par l'appelant via `showDivider`).
 */
@Composable
private fun SettingsRow(
    label: String,
    modifier: Modifier = Modifier,
    showDivider: Boolean = true,
    trailing: @Composable () -> Unit
) {
    Column(modifier = modifier) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 13.dp, vertical = 11.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = label,
                color = HasanColors.TextPrimary,
                fontFamily = IBMPlexSans,
                fontSize = 12.sp,
                modifier = Modifier.weight(1f)
            )
            trailing()
        }
        if (showDivider) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(HasanColors.Border)
            )
        }
    }
}

/** Valeur en lecture seule d'une .settings-row (.settings-row-value : mono, texte secondaire). */
@Composable
private fun SettingsRowValue(text: String) {
    Text(
        text = text,
        color = HasanColors.TextSecondary,
        fontFamily = IBMPlexMono,
        fontSize = 10.5.sp,
        textAlign = androidx.compose.ui.text.style.TextAlign.End,
        modifier = Modifier.width(180.dp)
    )
}

/** Bouton crayon compact — bascule une .settings-row en mode édition inline. */
@Composable
private fun EditPencilButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .size(22.dp)
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
 */
@Composable
private fun SettingsEditableRow(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    showDivider: Boolean = true,
    placeholder: String = "",
    isSecret: Boolean = false
) {
    var editing by remember { mutableStateOf(false) }
    var draft by remember(value) { mutableStateOf(value) }
    var secretVisible by remember { mutableStateOf(false) }

    Column(modifier = modifier) {
        if (editing) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 13.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = label,
                    color = HasanColors.TextPrimary,
                    fontFamily = IBMPlexSans,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(bottom = 4.dp)
                )
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 13.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = draft,
                    onValueChange = { draft = it; onValueChange(it) },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                    textStyle = androidx.compose.ui.text.TextStyle(fontFamily = IBMPlexMono, fontSize = 12.sp),
                    placeholder = { Text(placeholder, color = HasanColors.TextMutedA11y, fontSize = 12.sp) },
                    visualTransformation = if (isSecret && !secretVisible) PasswordVisualTransformation() else VisualTransformation.None,
                    trailingIcon = if (isSecret) {
                        {
                            IconButton(onClick = { secretVisible = !secretVisible }) {
                                Text(
                                    text = if (secretVisible) "Masquer" else "Afficher",
                                    color = HasanColors.TextSecondary,
                                    fontSize = 9.sp,
                                    fontFamily = IBMPlexMono
                                )
                            }
                        }
                    } else null,
                    colors = hasanTextFieldColors()
                )
                Spacer(modifier = Modifier.width(8.dp))
                Box(
                    modifier = Modifier
                        .clip(HasanShapes.panelSmall())
                        .background(HasanColors.Accent)
                        .clickable { editing = false }
                        .padding(horizontal = 10.dp, vertical = 8.dp)
                ) {
                    Text(text = "OK", color = HasanColors.TextPrimary, fontFamily = IBMPlexMono, fontSize = 11.sp)
                }
            }
        } else {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 13.dp, vertical = 11.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = label,
                    color = HasanColors.TextPrimary,
                    fontFamily = IBMPlexSans,
                    fontSize = 12.sp,
                    modifier = Modifier.weight(1f)
                )
                val displayValue = if (isSecret && value.isNotEmpty()) "•".repeat(value.length.coerceAtMost(10)) else value
                Text(
                    text = displayValue.ifEmpty { placeholder },
                    color = if (displayValue.isEmpty()) HasanColors.TextMutedA11y else HasanColors.TextSecondary,
                    fontFamily = IBMPlexMono,
                    fontSize = 10.5.sp,
                    maxLines = 1
                )
                Spacer(modifier = Modifier.width(8.dp))
                EditPencilButton(onClick = { editing = true })
            }
        }
        if (showDivider) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(HasanColors.Border)
            )
        }
    }
}

/** Bouton pleine largeur à coin coupé, fond plein — équivalent .btn-primary du mockup. */
@Composable
private fun CutCornerFilledButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    backgroundColor: Color = HasanColors.Accent,
    contentColor: Color = HasanColors.TextPrimary,
    shape: Shape = HasanShapes.panelSmall()
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(backgroundColor)
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(text = text, color = contentColor, fontFamily = IBMPlexSans, fontSize = 14.sp)
    }
}

/** Bouton pleine largeur à coin coupé, contour seul — équivalent .btn-outline du mockup. */
@Composable
private fun CutCornerOutlineButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    borderColor: Color = HasanColors.Border,
    contentColor: Color = HasanColors.TextSecondary,
    backgroundColor: Color = HasanColors.BgSurface,
    shape: Shape = HasanShapes.panelSmall()
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(backgroundColor)
            .border(1.dp, borderColor, shape)
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(text = text, color = contentColor, fontFamily = IBMPlexSans, fontSize = 13.sp)
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
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(28.dp)
        ) {
            // Groupe 1 — Voix & Interaction : réglages du quotidien en premier.
            VoiceInteractionGroup(state, callbacks)

            // Groupe 2 — Connexion & Sécurité : Hermes + pairing relay + Tools & Permissions,
            // rapprochés visuellement sous un même macro-groupe.
            ConnectionSecurityGroup(state, callbacks)

            // Groupe 3 — Historique & Sessions : export fusionné comme item de la section Sessions.
            SessionsSection(state, callbacks)

            // Groupe 4 — À propos : infos passives uniquement, le bouton Quitter est sorti
            // de ce groupe (action de fin de liste, séparée visuellement ci-dessous).
            AboutSection(state)

            Spacer(modifier = Modifier.height(8.dp))
        }

        // Action de fin de liste, hors de tout groupe/panel — espacement généreux pour
        // bien la signaler comme distincte des réglages au-dessus.
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 20.dp)) {
            CutCornerOutlineButton(
                text = "Quitter Hasan",
                onClick = callbacks.onQuit,
                borderColor = HasanColors.Accent,
                contentColor = HasanColors.Accent
            )
        }
    }
}

// ─────────────────────────── Groupe : Connexion & Sécurité ────────────────────
//
// Fusionne visuellement CONNEXION HERMES + APPAIRAGE RELAY + le lien vers
// Tools & Permissions sous un même macro-groupe (espacement interne réduit,
// 10dp, vs 28dp entre groupes de haut niveau) — cf. rapport d'audit UI.

@Composable
private fun ConnectionSecurityGroup(state: SettingsUiState, callbacks: SettingsCallbacks) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        ConnectionSection(state, callbacks)
        RelayPairingSection(state, callbacks)
        ToolsPermissionsLinkSection(callbacks)
    }
}

// ─────────────────────────── 1. Connexion Hermes ──────────────────────────────

@Composable
private fun ConnectionSection(state: SettingsUiState, callbacks: SettingsCallbacks) {
    SettingsSection(title = "CONNEXION HERMES") {
        SettingsEditableRow(
            label = "URL du serveur",
            value = state.serverUrl,
            onValueChange = callbacks.onServerUrlChange,
            placeholder = "http://serveur:8642/v1"
        )
        SettingsEditableRow(
            label = "Token d'authentification",
            value = state.authToken,
            onValueChange = callbacks.onAuthTokenChange,
            placeholder = "HASAN_DEV_TOKEN",
            isSecret = true,
            showDivider = state.connectionStatus != null
        )
        state.connectionStatus?.let { status ->
            SettingsRow(label = "État", showDivider = false) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(if (status.ok) HasanColors.Accent else HasanColors.TextSecondary)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = status.message,
                        color = if (status.ok) HasanColors.Accent else HasanColors.TextSecondary,
                        fontFamily = IBMPlexMono,
                        fontSize = 10.5.sp
                    )
                }
            }
        }
    }

    Spacer(modifier = Modifier.height(10.dp))

    CutCornerFilledButton(
        text = "⚡ Tester la connexion",
        onClick = callbacks.onTestConnection
    )

    Spacer(modifier = Modifier.height(8.dp))

    CutCornerOutlineButton(
        text = "Gérer les certificats de confiance",
        onClick = callbacks.onManageCerts
    )
}

// ─────────────────────────── 1bis. Pairing relay (WebSocket) ──────────────────

@Composable
private fun RelayPairingSection(state: SettingsUiState, callbacks: SettingsCallbacks) {
    SettingsSection(title = "APPAIRAGE RELAY") {
        SettingsRow(label = "Appareil appairé", showDivider = false) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(if (state.relayPaired) HasanColors.Accent else HasanColors.TextSecondary)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = relayStatusLabel(state.relayPaired, state.relayConnectionStatus),
                    color = if (state.relayPaired) HasanColors.Accent else HasanColors.TextSecondary,
                    fontFamily = IBMPlexMono,
                    fontSize = 10.5.sp
                )
            }
        }
    }

    Spacer(modifier = Modifier.height(10.dp))

    CutCornerOutlineButton(
        text = if (state.relayPaired) "Réappairer un appareil (scanner QR)" else "Appairer un appareil (scanner QR)",
        onClick = callbacks.onScanQrPairing
    )
}

private fun relayStatusLabel(
    paired: Boolean,
    status: com.hasan.v1.network.RelayConnectionStatus
): String {
    if (!paired) return "Non appairé"
    return when (status) {
        com.hasan.v1.network.RelayConnectionStatus.CONNECTED -> "Appairé — connecté"
        com.hasan.v1.network.RelayConnectionStatus.CONNECTING -> "Appairé — connexion…"
        com.hasan.v1.network.RelayConnectionStatus.RECONNECTING -> "Appairé — reconnexion…"
        com.hasan.v1.network.RelayConnectionStatus.DISCONNECTED -> "Appairé — déconnecté"
    }
}

// ─────────────────────────── 1ter. Lien Tools & Permissions ───────────────────

@Composable
private fun ToolsPermissionsLinkSection(callbacks: SettingsCallbacks) {
    SettingsSection(title = "CAPABILITIES DE L'APPAREIL") {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = callbacks.onOpenToolsPermissions)
                .padding(horizontal = 13.dp, vertical = 13.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Tools & Permissions",
                color = HasanColors.TextPrimary,
                fontFamily = IBMPlexSans,
                fontSize = 13.sp,
                modifier = Modifier.weight(1f)
            )
            Text(
                text = "→",
                color = HasanColors.Accent,
                fontFamily = IBMPlexMono,
                fontSize = 14.sp
            )
        }
    }
}

// ─────────────────────────── Groupe : Voix & Interaction ──────────────────────
//
// Groupe le plus utilisé au quotidien, placé en premier (cf. plan validé) :
// toggles TTS/wake word + sliders vitesse/volume/sensibilité en accès direct,
// puis les réglages plus rares (provider TTS + sous-voix, modèle de détection
// wake word ONNX) repliés sous un accordéon "Options avancées" (AnimatedVisibility
// standard — aucun pattern d'accordéon préexistant ailleurs dans le projet).

@Composable
private fun VoiceInteractionGroup(state: SettingsUiState, callbacks: SettingsCallbacks) {
    var advancedExpanded by remember { mutableStateOf(false) }

    VoiceSection(state, callbacks)

    Spacer(modifier = Modifier.height(4.dp))

    WakeWordSection(state, callbacks)

    Spacer(modifier = Modifier.height(10.dp))

    AdvancedOptionsToggle(
        expanded = advancedExpanded,
        onToggle = { advancedExpanded = !advancedExpanded }
    )

    AnimatedVisibility(
        visible = advancedExpanded,
        enter = fadeIn() + expandVertically(),
        exit = fadeOut() + shrinkVertically()
    ) {
        Column {
            Spacer(modifier = Modifier.height(10.dp))
            TtsProviderSection(state, callbacks)
            Spacer(modifier = Modifier.height(10.dp))
            WakeWordModelSection(state, callbacks)
        }
    }
}

@Composable
private fun AdvancedOptionsToggle(expanded: Boolean, onToggle: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onToggle)
            .padding(horizontal = 4.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = "Options avancées",
            color = HasanColors.TextSecondary,
            fontFamily = IBMPlexMono,
            fontSize = 10.sp,
            letterSpacing = 1.sp
        )
        Spacer(modifier = Modifier.width(6.dp))
        Text(
            text = if (expanded) "▴" else "▾",
            color = HasanColors.TextSecondary,
            fontFamily = IBMPlexMono,
            fontSize = 10.sp
        )
    }
}

// ─────────────────────────── Voix : essentiel ──────────────────────────────────

@Composable
private fun VoiceSection(state: SettingsUiState, callbacks: SettingsCallbacks) {
    SettingsSection(title = "VOIX") {
        SettingsRow(label = "Synthèse vocale (TTS)", showDivider = false) {
            HasanToggle(checked = state.ttsEnabled, onCheckedChange = callbacks.onTtsEnabledChange)
        }
    }

    Spacer(modifier = Modifier.height(4.dp))

    SettingsControlPanel(title = "RÉGLAGES VOCAUX") {
        // Slider vitesse
        LabeledSlider(
            label = "Vitesse",
            valueText = "%.1fx".format(state.ttsSpeed),
            value = state.ttsSpeed,
            valueRange = 0.5f..2.0f,
            steps = ((2.0f - 0.5f) / 0.1f).toInt() - 1,
            onValueChange = callbacks.onTtsSpeedChange
        )

        Spacer(modifier = Modifier.height(12.dp))

        // Slider volume
        LabeledSlider(
            label = "Volume",
            valueText = "${state.ttsVolume.toInt()}%",
            value = state.ttsVolume,
            valueRange = 0f..100f,
            steps = 99,
            onValueChange = callbacks.onTtsVolumeChange
        )
    }
}

// ─────────────────────────── Voix : avancé (provider + sous-voix) ─────────────

@Composable
private fun TtsProviderSection(state: SettingsUiState, callbacks: SettingsCallbacks) {
    SettingsControlPanel(title = "PROVIDER TTS") {
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

        Divider()

        if (state.ttsProvider == com.hasan.v1.SettingsManager.TTS_PROVIDER_EDGE) {
            // Sous-sélecteur voix Edge TTS
            RadioOptionGroup(
                options = state.ttsProviderSubOptions,
                selected = state.ttsSelectedSubOption,
                onSelect = callbacks.onTtsSubOptionChange
            )
        } else {
            // Sous-sélecteur moteur natif, puis voix du moteur choisi
            if (state.nativeEngines.isNotEmpty()) {
                RadioOptionGroup(
                    options = state.nativeEngines.map { it.name to it.label },
                    selected = state.nativeSelectedEngine,
                    onSelect = callbacks.onNativeEngineChange
                )
                Divider()
            }
            if (state.showNativeEngineSelector && state.ttsProviderSubOptions.isNotEmpty()) {
                RadioOptionGroup(
                    options = state.ttsProviderSubOptions,
                    selected = state.ttsSelectedSubOption,
                    onSelect = callbacks.onTtsSubOptionChange
                )
            }
        }
    }
}

@Composable
private fun ProviderChoiceRow(label: String, selected: Boolean, onClick: () -> Unit) {
    val borderColor = if (selected) HasanColors.Accent else HasanColors.Border
    val bgColor = if (selected) HasanColors.AccentGlowBg else HasanColors.BgSurface2
    val shape = HasanShapes.panelSmall()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(bgColor)
            .border(1.dp, borderColor, shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioDot(selected = selected)
        Spacer(modifier = Modifier.width(10.dp))
        Text(text = label, color = HasanColors.TextPrimary, fontSize = 14.sp)
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
                    .padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                RadioDot(selected = value == selected)
                Spacer(modifier = Modifier.width(10.dp))
                Text(text = label, color = HasanColors.TextPrimary, fontSize = 14.sp)
            }
        }
    }
}

@Composable
private fun RadioDot(selected: Boolean) {
    Box(
        modifier = Modifier
            .size(16.dp)
            .clip(CircleShape)
            .border(1.5.dp, if (selected) HasanColors.Accent else HasanColors.TextSecondary, CircleShape),
        contentAlignment = Alignment.Center
    ) {
        if (selected) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(CircleShape)
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
            .padding(vertical = 12.dp)
            .height(1.dp)
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
        Text(text = label, color = HasanColors.TextPrimary, fontSize = 14.sp, modifier = Modifier.weight(1f))
        Text(text = valueText, color = HasanColors.TextSecondary, fontSize = 13.sp)
    }
    Slider(
        value = value,
        onValueChange = onValueChange,
        valueRange = valueRange,
        steps = steps,
        colors = SliderDefaults.colors(
            thumbColor = HasanColors.Accent,
            activeTrackColor = HasanColors.Accent,
            inactiveTrackColor = HasanColors.Border
        )
    )
}

// ─────────────────────────── Wake word : essentiel ─────────────────────────────

@Composable
private fun WakeWordSection(state: SettingsUiState, callbacks: SettingsCallbacks) {
    SettingsSection(title = "WAKE WORD") {
        SettingsRow(label = "Activer \"Ok Hasan\"", showDivider = false) {
            HasanToggle(checked = state.wakeWordEnabled, onCheckedChange = callbacks.onWakeWordEnabledChange)
        }
    }
    Text(
        text = "Nécessite un build natif",
        color = HasanColors.TextSecondary,
        fontFamily = IBMPlexMono,
        fontSize = 10.sp,
        modifier = Modifier.padding(start = 4.dp, top = 4.dp)
    )

    Spacer(modifier = Modifier.height(4.dp))

    SettingsControlPanel(title = "SENSIBILITÉ") {
        Text(
            text = "Sensibilité du wake word",
            color = HasanColors.TextPrimary,
            fontSize = 14.sp,
            modifier = Modifier.padding(bottom = 6.dp)
        )
        Slider(
            value = state.wakeWordSensitivity,
            onValueChange = callbacks.onWakeWordSensitivityChange,
            valueRange = 0.1f..1.0f,
            steps = 8,
            colors = SliderDefaults.colors(
                thumbColor = HasanColors.Accent,
                activeTrackColor = HasanColors.Accent,
                inactiveTrackColor = HasanColors.Border
            )
        )
        Row(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = "Moins sensible",
                color = HasanColors.TextSecondary,
                fontSize = 10.sp,
                modifier = Modifier.weight(1f)
            )
            Text(text = "Plus sensible", color = HasanColors.TextSecondary, fontSize = 10.sp)
        }
    }
}

// ─────────────────────────── Wake word : avancé (modèle ONNX) ─────────────────

@Composable
private fun WakeWordModelSection(state: SettingsUiState, callbacks: SettingsCallbacks) {
    SettingsControlPanel(title = "MODÈLE DE DÉTECTION") {
        RadioOptionGroup(
            options = state.wakeWordModels.map { it to it.removeSuffix(".onnx") },
            selected = state.wakeWordSelectedModel,
            onSelect = callbacks.onWakeWordModelChange
        )
    }
}

// ─────────────────────────── Groupe : Historique & Sessions ───────────────────

private val sessionDateFormat = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault())

@Composable
private fun SessionsSection(state: SettingsUiState, callbacks: SettingsCallbacks) {
    SettingsControlPanel(title = "SESSIONS") {
        CutCornerFilledButton(
            text = "Nouvelle session",
            onClick = callbacks.onNewSession
        )

        Spacer(modifier = Modifier.height(12.dp))

        if (state.sessions.isEmpty()) {
            Text(
                text = "Aucune session",
                color = HasanColors.TextSecondary,
                fontSize = 13.sp,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 12.dp),
                textAlign = androidx.compose.ui.text.style.TextAlign.Center
            )
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 280.dp)
                    .padding(bottom = 4.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(state.sessions, key = { it.id }) { session ->
                    SessionRow(
                        session = session,
                        onTap = { callbacks.onSessionTap(session) },
                        onLongPress = { callbacks.onSessionLongPress(session) }
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))
        Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(HasanColors.Border))
        Spacer(modifier = Modifier.height(12.dp))

        // Export d'historique fusionné ici comme item de la section Sessions
        // (anciennement une section CONVERSATION séparée).
        CutCornerOutlineButton(
            text = "Exporter l'historique (.txt)",
            onClick = callbacks.onExportHistory,
            contentColor = HasanColors.TextPrimary
        )
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun SessionRow(session: HermesSession, onTap: () -> Unit, onLongPress: () -> Unit) {
    val borderColor = if (session.isActive) HasanColors.Accent else HasanColors.Border
    val shape = HasanShapes.panel()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(HasanColors.BgSurface2)
            .border(1.dp, borderColor, shape)
            .combinedClickable(onClick = onTap, onLongClick = onLongPress)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (session.isActive) {
                    Box(
                        modifier = Modifier
                            .size(6.dp)
                            .clip(CircleShape)
                            .background(HasanColors.Accent)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                }
                Text(text = session.name, color = HasanColors.TextPrimary, fontSize = 14.sp)
            }
            Text(
                text = sessionDateFormat.format(Date(session.updatedAt)),
                color = HasanColors.TextMutedA11y,
                fontFamily = IBMPlexMono,
                fontSize = 10.sp,
                modifier = Modifier.padding(top = 2.dp)
            )
        }
        if (session.isActive) {
            TagPill(text = "ACTIVE")
        }
    }
}

// ─────────────────────────── Groupe : À propos ─────────────────────────────────
//
// Le bouton "Quitter Hasan" est sorti de ce groupe — c'est désormais une action de
// fin de liste, rendue par SettingsScreen() en dehors de tout groupe/panel.

@Composable
private fun AboutSection(state: SettingsUiState) {
    SettingsSection(title = "À PROPOS") {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 13.dp, vertical = 11.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(HasanShapes.diagonal)
                    .background(HasanColors.Accent),
                contentAlignment = Alignment.Center
            ) {
                Image(
                    painter = painterResource(R.drawable.ic_hasan_brand_glyph),
                    contentDescription = null,
                    modifier = Modifier.size(20.dp)
                )
            }
            Spacer(modifier = Modifier.width(12.dp))
            Column {
                Text(text = state.aboutVersion, color = HasanColors.TextPrimary, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                Text(text = state.aboutSubtitle, color = HasanColors.TextSecondary, fontSize = 12.sp)
            }
        }
        Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(HasanColors.Border))

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
