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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import com.hasan.v1.ui.components.CutCornerPanel
import com.hasan.v1.ui.components.HasanToggle
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
 * et des effets de bord (TOFU, sessions, export, quit).
 */
data class SettingsUiState(
    val relayPaired: Boolean,
    val relayConnectionStatus: com.hasan.v1.network.RelayConnectionStatus,
    val relayManualUrl: String,
    val relayManualCode: String,
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
    val onManageCerts: () -> Unit,
    val onScanQrPairing: () -> Unit,
    val onRelayManualUrlChange: (String) -> Unit,
    val onRelayManualCodeChange: (String) -> Unit,
    val onPairManually: () -> Unit,
    val onTtsProviderChange: (String) -> Unit,
    val onTtsSubOptionChange: (String) -> Unit,
    val onNativeEngineChange: (String) -> Unit,
    val onTtsEnabledChange: (Boolean) -> Unit,
    val onTtsSpeedChange: (Float) -> Unit,
    val onTtsVolumeChange: (Float) -> Unit,
    val onWakeWordEnabledChange: (Boolean) -> Unit,
    val onWakeWordSensitivityChange: (Float) -> Unit,
    val onWakeWordModelChange: (String) -> Unit,
    val onProfileSelect: (String) -> Unit,
    val onMcpToggle: (String, Boolean) -> Unit,
    val onWebUiServerUrlChange: (String) -> Unit,
    val onWebUiPasswordChange: (String) -> Unit,
    val onWebUiConnect: () -> Unit,
    val onOpenLogs: () -> Unit,
    val onMenuClick: () -> Unit
)

private val sectionTitleColor = HasanColors.Accent

@Composable
private fun SectionTitle(text: String) {
    Text(
        text = text,
        color = sectionTitleColor,
        fontFamily = IBMPlexMono,
        fontWeight = FontWeight.Medium,
        fontSize = HasanDimens.TextLabelSmall,
        letterSpacing = 1.5.sp,
        modifier = Modifier.padding(start = HasanDimens.SpacingXs, bottom = HasanDimens.SpacingS)
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
            Column(modifier = Modifier.padding(HasanDimens.SpacingL), content = content)
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
                EditPencilButton(onClick = { editing = true })
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
private fun CutCornerFilledButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    backgroundColor: Color = HasanColors.Accent,
    contentColor: Color = HasanColors.TextPrimary,
    shape: Shape = HasanShapes.panelSmall(),
    icon: Int? = null
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(backgroundColor)
            .clickable(onClick = onClick)
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
        Text(text = text, color = contentColor, fontFamily = IBMPlexSans, fontSize = HasanDimens.TextBody)
    }
}

/** Bouton pleine largeur à coin coupé, contour seul — équivalent .btn-outline du mockup. */
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
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(backgroundColor)
            .border(HasanDimens.BorderWidth, borderColor, shape)
            .clickable(onClick = onClick)
            .padding(vertical = HasanDimens.SpacingM),
        contentAlignment = Alignment.Center
    ) {
        Text(text = text, color = contentColor, fontFamily = IBMPlexSans, fontSize = HasanDimens.TextSubtitle)
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
        com.hasan.v1.ui.components.HasanMinimalHeader(callbacks.onMenuClick)
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
// Deux sous-sections distinctes, chacune son propre panel + titre : hermes-webui
// (LE chat — c'est ce qui permet d'envoyer des messages, doit être en premier et
// clairement identifié) et relay bridge (canal séparé pour SMS/localisation/etc.,
// pas nécessaire pour discuter avec Hasan). L'ancienne section "config héritée"
// (settings.serverUrl/authToken, vestige du flux HTTP relay pré-migration webui)
// a été retirée avec son dernier consommateur (page Connexion de l'onboarding).

@Composable
private fun ConnectionSection(state: SettingsUiState, callbacks: SettingsCallbacks) {
    Column(verticalArrangement = Arrangement.spacedBy(HasanDimens.SpacingXl)) {
        WebUiConnectionSection(state, callbacks)
        RelayBridgeSection(state, callbacks)
    }
}

/** hermes-webui — LA connexion nécessaire pour discuter avec Hasan (envoi de messages). */
@Composable
private fun WebUiConnectionSection(state: SettingsUiState, callbacks: SettingsCallbacks) {
    Column {
        SectionTitle("HERMES-WEBUI — CHAT")
        CutCornerPanel(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(HasanDimens.SpacingL)) {
                Text(
                    text = "Connexion nécessaire pour envoyer des messages à Hasan.",
                    color = HasanColors.TextSecondary,
                    fontSize = HasanDimens.TextCaption,
                    modifier = Modifier.padding(bottom = HasanDimens.SpacingM)
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
                        showDivider = true
                    )
                    SettingsRow(label = "État", showDivider = false) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .size(8.dp)
                                    .clip(CircleShape)
                                    .background(if (state.webUiLoggedIn) HasanColors.Accent else HasanColors.TextSecondary)
                            )
                            Spacer(modifier = Modifier.width(HasanDimens.SpacingS))
                            Text(
                                text = state.webUiConnectionStatus?.message
                                    ?: if (state.webUiLoggedIn) "Connecté" else "Non connecté",
                                color = if (state.webUiLoggedIn) HasanColors.Accent else HasanColors.TextSecondary,
                                fontFamily = IBMPlexMono,
                                fontSize = HasanDimens.TextLabelMedium
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(HasanDimens.SpacingM))

                CutCornerFilledButton(
                    text = if (state.webUiLoggedIn) "Se reconnecter" else "Se connecter",
                    onClick = callbacks.onWebUiConnect,
                    icon = com.hasan.v1.R.drawable.ic_refresh
                )

                Spacer(modifier = Modifier.height(HasanDimens.SpacingS))

                CutCornerOutlineButton(
                    text = "Gérer les certificats de confiance",
                    onClick = callbacks.onManageCerts
                )
            }
        }
    }
}

/** Relay bridge — canal séparé pour les actions téléphone (SMS, localisation, etc.), pas nécessaire pour discuter avec Hasan. */
@Composable
private fun RelayBridgeSection(state: SettingsUiState, callbacks: SettingsCallbacks) {
    Column {
        SectionTitle("RELAY BRIDGE — ACTIONS TÉLÉPHONE")
        CutCornerPanel(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(HasanDimens.SpacingL)) {
                Text(
                    text = "Optionnel — permet à Hasan d'envoyer des SMS, consulter la localisation, etc. Le chat fonctionne sans ça.",
                    color = HasanColors.TextSecondary,
                    fontSize = HasanDimens.TextCaption,
                    modifier = Modifier.padding(bottom = HasanDimens.SpacingM)
                )
                Column(modifier = Modifier.clip(HasanShapes.panelSmall()).background(HasanColors.BgSurface2)) {
                    SettingsRow(label = "Appareil appairé", showDivider = false) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .size(8.dp)
                                    .clip(CircleShape)
                                    .background(if (state.relayPaired) HasanColors.Accent else HasanColors.TextSecondary)
                            )
                            Spacer(modifier = Modifier.width(HasanDimens.SpacingS))
                            Text(
                                text = relayStatusLabel(state.relayPaired, state.relayConnectionStatus),
                                color = if (state.relayPaired) HasanColors.Accent else HasanColors.TextSecondary,
                                fontFamily = IBMPlexMono,
                                fontSize = HasanDimens.TextLabelMedium
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(HasanDimens.SpacingM))

                CutCornerOutlineButton(
                    text = if (state.relayPaired) "Réappairer un appareil (scanner QR)" else "Appairer un appareil (scanner QR)",
                    onClick = callbacks.onScanQrPairing
                )

                Spacer(modifier = Modifier.height(HasanDimens.SpacingL))

                Text(
                    text = "Ou saisir manuellement (URL + code affichés par le relay lors de la génération du QR).",
                    color = HasanColors.TextMutedA11y,
                    fontSize = HasanDimens.TextCaption,
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
                        showDivider = false
                    )
                }

                Spacer(modifier = Modifier.height(HasanDimens.SpacingM))

                CutCornerOutlineButton(
                    text = "Appairer manuellement",
                    onClick = callbacks.onPairManually
                )
            }
        }
    }
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
                    SettingsRowValue(text = if (server.enabled) "Actif" else "Inactif")
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
            .border(HasanDimens.BorderWidth, borderColor, shape)
            .clickable(onClick = onClick)
            .padding(horizontal = HasanDimens.SpacingM, vertical = HasanDimens.SpacingS),
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
    SettingsSection(title = "LOGS") {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = callbacks.onOpenLogs)
                .padding(horizontal = HasanDimens.SpacingM, vertical = HasanDimens.SpacingM),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Voir les logs",
                color = HasanColors.TextPrimary,
                fontFamily = IBMPlexSans,
                fontSize = HasanDimens.TextSubtitle,
                modifier = Modifier.weight(1f)
            )
            Text(
                text = "→",
                color = HasanColors.Accent,
                fontFamily = IBMPlexMono,
                fontSize = HasanDimens.TextBody
            )
        }
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
