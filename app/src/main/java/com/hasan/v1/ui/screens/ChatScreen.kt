package com.hasan.v1.ui.screens

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hasan.v1.db.Message
import com.hasan.v1.ui.components.AccentIconButton
import com.hasan.v1.ui.components.CutCornerIconButton
import com.hasan.v1.ui.components.MarkdownText
import com.hasan.v1.ui.theme.HasanColors
import com.hasan.v1.ui.theme.HasanShapes
import com.hasan.v1.ui.theme.IBMPlexMono
import com.hasan.v1.ui.theme.IBMPlexSans
import com.hasan.v1.webui.models.ModelOption
import com.hasan.v1.webui.models.UploadedAttachment
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** État de saisie/écoute affiché sous forme vocale ou texte — voir MainViewModel.VoiceState. */
data class ChatVoiceUi(
    val statusText: String,
    val isWaveActive: Boolean,
    val showStopTts: Boolean,
    val ringLightTick: Int
)

data class ChatInputUi(
    val isVoiceMode: Boolean,
    val isListening: Boolean,
    val sttVisualizerActive: Boolean,
    val degraded: Boolean,
    val hint: String,
    val availableModels: List<ModelOption> = emptyList(),
    val selectedModel: String? = null,
    /** Un tour hermes-webui est en cours côté serveur — affiche le bouton "Arrêter" (distinct de showStopTts, qui coupe seulement le TTS local). */
    val isStreaming: Boolean = false,
    /** Fichiers déjà uploadés (POST /api/upload), en attente d'être joints au prochain message envoyé. */
    val pendingAttachments: List<UploadedAttachment> = emptyList(),
    val attachmentUploading: Boolean = false
)

/** Clarification demandée par Hermes en cours — voir MainViewModel.PendingClarify. */
data class ChatClarifyUi(
    val question: String,
    val choices: List<String>?
)

/** Écran Chat complet — liste de messages + zone de saisie + ring light wake word. */
@Composable
fun ChatScreen(
    messages: List<Message>,
    ttsPlayingMessageId: Long?,
    voiceUi: ChatVoiceUi,
    inputUi: ChatInputUi,
    inputText: String,
    onInputTextChange: (String) -> Unit,
    onSend: () -> Unit,
    onMicClick: () -> Unit,
    onMicLongPress: () -> Unit,
    onSwitchToText: () -> Unit,
    onStopTts: () -> Unit,
    onUserLongPress: (Message) -> Unit,
    onHasanLongPress: (Message) -> Unit,
    onToggleTts: (Message) -> Unit,
    onCopy: (Message) -> Unit,
    onRetry: () -> Unit,
    clarify: ChatClarifyUi? = null,
    onClarifyResponse: (String) -> Unit = {},
    onModelSelected: (String) -> Unit = {},
    onCancelChat: () -> Unit = {},
    onAttachClick: () -> Unit = {},
    onRemoveAttachment: (UploadedAttachment) -> Unit = {},
    modifier: Modifier = Modifier
) {
    Box(modifier = modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            MessageList(
                messages = messages,
                ttsPlayingMessageId = ttsPlayingMessageId,
                onUserLongPress = onUserLongPress,
                onHasanLongPress = onHasanLongPress,
                onToggleTts = onToggleTts,
                onCopy = onCopy,
                onRetry = onRetry,
                modifier = Modifier.weight(1f)
            )
            InputBar(
                voiceUi = voiceUi,
                inputUi = inputUi,
                inputText = inputText,
                onInputTextChange = onInputTextChange,
                onSend = onSend,
                onMicClick = onMicClick,
                onMicLongPress = onMicLongPress,
                onSwitchToText = onSwitchToText,
                onStopTts = onStopTts,
                onModelSelected = onModelSelected,
                onCancelChat = onCancelChat,
                onAttachClick = onAttachClick,
                onRemoveAttachment = onRemoveAttachment
            )
        }
        RingLightOverlay(tick = voiceUi.ringLightTick)
        if (clarify != null) {
            ClarifyOverlay(clarify = clarify, onResponse = onClarifyResponse)
        }
    }
}

/**
 * Bandeau plein écran semi-opaque avec la question de Hermes et soit des boutons de
 * choix (agent.clarify_callback avec choices non-null), soit un champ texte libre
 * (question ouverte). Toujours un champ "Autre" en texte libre même avec des choix,
 * pour ne jamais bloquer l'utilisateur sur une liste incomplète.
 */
@Composable
private fun ClarifyOverlay(clarify: ChatClarifyUi, onResponse: (String) -> Unit) {
    var freeText by remember(clarify) { mutableStateOf("") }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.72f))
            .clickable(enabled = false) {}, // absorbe les clics derrière l'overlay
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = 340.dp)
                .padding(24.dp)
                .background(HasanColors.BgSurface, HasanShapes.panel())
                .padding(20.dp)
        ) {
            Text(
                text = clarify.question,
                color = HasanColors.TextPrimary,
                fontFamily = IBMPlexSans,
                fontSize = 15.sp
            )
            Spacer(modifier = Modifier.height(16.dp))
            clarify.choices?.forEach { choice ->
                CutCornerOutlineButton(
                    text = choice,
                    onClick = { onResponse(choice) },
                    modifier = Modifier.padding(vertical = 4.dp)
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
            OutlinedTextField(
                value = freeText,
                onValueChange = { freeText = it },
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text(if (clarify.choices.isNullOrEmpty()) "Votre réponse" else "Autre…") },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = HasanColors.TextPrimary,
                    unfocusedTextColor = HasanColors.TextPrimary
                )
            )
            Spacer(modifier = Modifier.height(8.dp))
            CutCornerOutlineButton(
                text = "Envoyer",
                onClick = { if (freeText.isNotBlank()) onResponse(freeText.trim()) }
            )
        }
    }
}

// ─────────────────────────── Liste de messages ────────────────────────────

@Composable
private fun MessageList(
    messages: List<Message>,
    ttsPlayingMessageId: Long?,
    onUserLongPress: (Message) -> Unit,
    onHasanLongPress: (Message) -> Unit,
    onToggleTts: (Message) -> Unit,
    onCopy: (Message) -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier
) {
    val listState = rememberLazyListStateAutoScroll(messages.size)

    LazyColumn(
        state = listState,
        modifier = modifier.fillMaxWidth(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(
            start = 16.dp, end = 16.dp, top = 8.dp, bottom = 8.dp
        ),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        items(messages, key = { it.id.takeIf { id -> id != 0L } ?: it.hashCode() }) { message ->
            when (message.role) {
                "user" -> UserBubble(message, onUserLongPress)
                "assistant" -> AssistantBubble(message, ttsPlayingMessageId, onHasanLongPress, onToggleTts, onCopy)
                "thinking" -> ThinkingBubble(message)
                "error" -> ErrorBubble(message, onRetry)
            }
        }
    }
}

/** Reste ancré en bas si on y était déjà lors de l'ajout d'un message (streaming inclus). */
@Composable
private fun rememberLazyListStateAutoScroll(itemCount: Int): LazyListState {
    val listState = androidx.compose.foundation.lazy.rememberLazyListState()
    val scope = rememberCoroutineScope()
    var prevCount by remember { mutableStateOf(0) }

    LaunchedEffect(itemCount) {
        if (itemCount == 0) { prevCount = 0; return@LaunchedEffect }
        val lastVisible = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1
        val wasAtBottom = prevCount == 0 || lastVisible >= prevCount - 2
        val grew = itemCount > prevCount
        if (prevCount == 0) {
            scope.launch { listState.scrollToItem(itemCount - 1) }
        } else if (wasAtBottom && grew) {
            scope.launch { listState.scrollToItem(itemCount - 1) }
        }
        prevCount = itemCount
    }
    return listState
}

private val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun UserBubble(message: Message, onLongPress: (Message) -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .widthIn(max = 300.dp),
        horizontalAlignment = Alignment.End
    ) {
        Box(
            modifier = Modifier
                .align(Alignment.End)
                .clip(HasanShapes.bubble())
                .background(HasanColors.BgSurface3)
                .combinedClickable(onClick = {}, onLongClick = { onLongPress(message) })
                .padding(horizontal = 14.dp, vertical = 10.dp)
        ) {
            Text(
                text = message.content,
                color = HasanColors.TextPrimary,
                fontFamily = IBMPlexSans,
                fontSize = 15.sp,
                lineHeight = 20.sp
            )
        }
        Text(
            text = timeFormat.format(Date(message.timestamp)),
            color = HasanColors.TextMutedA11y,
            fontSize = 11.sp,
            modifier = Modifier.padding(top = 4.dp, end = 4.dp)
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun AssistantBubble(
    message: Message,
    ttsPlayingMessageId: Long?,
    onLongPress: (Message) -> Unit,
    onToggleTts: (Message) -> Unit,
    onCopy: (Message) -> Unit
) {
    val isPending = message.isStreaming && message.content.isBlank()

    Column(modifier = Modifier.fillMaxWidth()) {
        if (!isPending) {
            Text(
                text = "HASAN",
                color = HasanColors.Accent,
                fontFamily = IBMPlexMono,
                fontSize = 9.sp,
                letterSpacing = 1.sp,
                modifier = Modifier.padding(start = 13.dp, bottom = 3.dp)
            )
        }
        Row(
            modifier = Modifier
                .height(IntrinsicSize.Min)
                .combinedClickable(
                    onClick = {},
                    onLongClick = if (isPending) null else { { onLongPress(message) } }
                )
        ) {
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .width(2.dp)
                    .background(HasanColors.Accent)
            )
            if (isPending) {
                PulsingDots(
                    modifier = Modifier.padding(start = 11.dp, end = 14.dp, top = 3.dp, bottom = 3.dp),
                    minAlpha = 0.3f,
                    durationMs = 600
                )
            } else {
                MarkdownText(
                    text = message.content,
                    selectable = true,
                    modifier = Modifier.padding(start = 11.dp, end = 14.dp, top = 3.dp, bottom = 3.dp)
                )
            }
        }

        if (!isPending) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp, start = 4.dp, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = timeFormat.format(Date(message.timestamp)),
                    color = HasanColors.TextMutedA11y,
                    fontSize = 11.sp
                )
                val metaText = buildMetadataText(message.metadata)
                if (metaText != null) {
                    Text(
                        text = metaText,
                        color = HasanColors.TextMutedA11y,
                        fontSize = 11.sp,
                        modifier = Modifier.padding(start = 6.dp)
                    )
                }
                Spacer(modifier = Modifier.weight(1f))
                val isPlaying = ttsPlayingMessageId == message.id
                MessageIconButton(
                    icon = if (isPlaying) com.hasan.v1.R.drawable.ic_volume_off else com.hasan.v1.R.drawable.ic_replay,
                    contentDescription = "Lire / arrêter",
                    onClick = { onToggleTts(message) }
                )
                MessageIconButton(
                    icon = com.hasan.v1.R.drawable.ic_copy,
                    contentDescription = "Copier",
                    onClick = { onCopy(message) },
                    modifier = Modifier.padding(start = 4.dp)
                )
            }
        }
    }
}

@Composable
private fun MessageIconButton(
    icon: Int,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .size(28.dp)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        androidx.compose.foundation.Image(
            painter = androidx.compose.ui.res.painterResource(icon),
            contentDescription = contentDescription,
            colorFilter = androidx.compose.ui.graphics.ColorFilter.tint(HasanColors.TextSecondary),
            modifier = Modifier.size(16.dp)
        )
    }
}

@Composable
private fun ThinkingBubble(message: Message) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .height(IntrinsicSize.Min)
            .widthIn(max = 280.dp)
    ) {
        Box(
            modifier = Modifier
                .fillMaxHeight()
                .width(2.dp)
                .background(HasanColors.Border)
        )
        Box(
            modifier = Modifier.padding(start = 11.dp, end = 14.dp, top = 3.dp, bottom = 3.dp)
        ) {
            Text(
                text = message.content,
                color = HasanColors.TextSecondary,
                fontSize = 13.sp,
                fontStyle = FontStyle.Italic
            )
        }
        PulsingDots(
            modifier = Modifier.padding(start = 6.dp),
            minAlpha = 0.2f,
            durationMs = 700,
            color = HasanColors.TextMutedA11y,
            fontSize = 18.sp
        )
    }
}

@Composable
private fun ErrorBubble(message: Message, onRetry: () -> Unit) {
    val retryShape = HasanShapes.panelSmall()
    Column(modifier = Modifier.fillMaxWidth()) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(HasanShapes.panel())
                .background(HasanColors.AccentGlowBg)
                .padding(horizontal = 14.dp, vertical = 10.dp)
        ) {
            Text(
                text = "⚠️ ${message.content}",
                color = HasanColors.TextPrimary,
                fontSize = 14.sp
            )
        }
        Box(
            modifier = Modifier
                .padding(top = 6.dp, start = 4.dp)
                .clip(retryShape)
                .background(HasanColors.Accent)
                .clickable(onClick = onRetry)
                .padding(horizontal = 16.dp, vertical = 8.dp)
        ) {
            Text(text = "Réessayer", color = HasanColors.TextPrimary, fontSize = 12.sp)
        }
    }
}

private fun buildMetadataText(metadata: String?): String? {
    if (metadata.isNullOrBlank()) return null
    return try {
        val obj = JSONObject(metadata)
        val durationMs = obj.optLong("duration_ms", -1L)
        val outputTokens = obj.optInt("output_tokens", 0)
        if (durationMs < 0 && outputTokens == 0) return null
        val parts = mutableListOf<String>()
        if (durationMs >= 0) parts.add("${"%.1f".format(durationMs / 1000.0)}s")
        if (outputTokens > 0) parts.add("$outputTokens tok")
        parts.joinToString(" · ")
    } catch (_: Exception) { null }
}

// ─────────────────────────── Dots animés ("•••") ──────────────────────────

@Composable
private fun PulsingDots(
    modifier: Modifier = Modifier,
    minAlpha: Float,
    durationMs: Int,
    color: Color = HasanColors.TextPrimary,
    fontSize: androidx.compose.ui.unit.TextUnit = 15.sp
) {
    val transition = rememberInfiniteTransition(label = "dots-pulse")
    val dotsAlpha by transition.animateFloat(
        initialValue = minAlpha,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = durationMs, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "dots-alpha"
    )
    Text(
        text = "•••",
        color = color,
        fontSize = fontSize,
        modifier = modifier.alpha(dotsAlpha)
    )
}

// ─────────────────────────── Ring light wake word ─────────────────────────

@Composable
private fun RingLightOverlay(tick: Int) {
    var alphaValue by remember { mutableStateOf(0f) }
    LaunchedEffect(tick) {
        if (tick == 0) return@LaunchedEffect
        val steps = listOf(0f, 0.12f, 0f)
        val stepDurationMs = 250L
        for (target in steps) {
            alphaValue = target
            kotlinx.coroutines.delay(stepDurationMs)
        }
        alphaValue = 0f
    }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .alpha(alphaValue)
            .background(HasanColors.Accent)
    )
}

// ─────────────────────────── Barre de saisie ──────────────────────────────

@Composable
private fun InputBar(
    voiceUi: ChatVoiceUi,
    inputUi: ChatInputUi,
    inputText: String,
    onInputTextChange: (String) -> Unit,
    onSend: () -> Unit,
    onMicClick: () -> Unit,
    onMicLongPress: () -> Unit,
    onSwitchToText: () -> Unit,
    onStopTts: () -> Unit,
    onModelSelected: (String) -> Unit,
    onCancelChat: () -> Unit,
    onAttachClick: () -> Unit,
    onRemoveAttachment: (UploadedAttachment) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(HasanColors.BgBase)
            .padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 12.dp)
    ) {
        if (!inputUi.isVoiceMode && (inputUi.pendingAttachments.isNotEmpty() || inputUi.attachmentUploading)) {
            PendingAttachmentsRow(
                attachments = inputUi.pendingAttachments,
                uploading = inputUi.attachmentUploading,
                onRemove = onRemoveAttachment
            )
        }
        if (inputUi.availableModels.isNotEmpty() || inputUi.isStreaming) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (inputUi.availableModels.isNotEmpty()) {
                    ModelPickerButton(
                        models = inputUi.availableModels,
                        selectedModel = inputUi.selectedModel,
                        onModelSelected = onModelSelected
                    )
                } else {
                    Spacer(modifier = Modifier.width(1.dp))
                }
                if (inputUi.isStreaming) {
                    CancelChatButton(onClick = onCancelChat)
                }
            }
        }
        if (inputUi.isVoiceMode) {
            VoiceModeRow(
                voiceUi = voiceUi,
                onSwitchToText = onSwitchToText,
                onStopTts = onStopTts
            )
        } else {
            TextModeRow(
                inputUi = inputUi,
                inputText = inputText,
                onInputTextChange = onInputTextChange,
                onSend = onSend,
                onMicClick = onMicClick,
                onMicLongPress = onMicLongPress,
                onAttachClick = onAttachClick
            )
        }
    }
}

/** Aperçu horizontal des pièces jointes déjà uploadées, en attente d'envoi — une pastille par fichier avec une croix pour la retirer. */
@Composable
private fun PendingAttachmentsRow(
    attachments: List<UploadedAttachment>,
    uploading: Boolean,
    onRemove: (UploadedAttachment) -> Unit
) {
    androidx.compose.foundation.lazy.LazyRow(
        modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        items(attachments, key = { it.path }) { attachment ->
            AttachmentChip(attachment = attachment, onRemove = { onRemove(attachment) })
        }
        if (uploading) {
            item(key = "uploading") { UploadingChip() }
        }
    }
}

@Composable
private fun AttachmentChip(attachment: UploadedAttachment, onRemove: () -> Unit) {
    Row(
        modifier = Modifier
            .clip(HasanShapes.panelSmall())
            .background(HasanColors.BgSurface2)
            .padding(start = 10.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = attachment.name,
            color = HasanColors.TextSecondary,
            fontFamily = IBMPlexMono,
            fontSize = 11.sp,
            modifier = Modifier.widthIn(max = 140.dp)
        )
        Text(
            text = "✕",
            color = HasanColors.TextMutedA11y,
            fontSize = 13.sp,
            modifier = Modifier.clickable(onClick = onRemove).padding(start = 8.dp)
        )
    }
}

@Composable
private fun UploadingChip() {
    Row(
        modifier = Modifier
            .clip(HasanShapes.panelSmall())
            .background(HasanColors.BgSurface2)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(text = "Envoi…", color = HasanColors.TextMutedA11y, fontFamily = IBMPlexMono, fontSize = 11.sp)
    }
}

/** Bouton compact affichant le modèle LLM sélectionné pour ce tour, ouvrant un menu de choix parmi [models]. */
@Composable
private fun ModelPickerButton(
    models: List<ModelOption>,
    selectedModel: String?,
    onModelSelected: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    var expanded by remember { mutableStateOf(false) }
    val label = models.firstOrNull { it.id == selectedModel }?.label ?: "Modèle par défaut"
    Box(modifier = modifier) {
        Row(
            modifier = Modifier
                .clip(HasanShapes.panelSmall())
                .background(HasanColors.BgSurface2)
                .clickable { expanded = true }
                .padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = label,
                color = HasanColors.TextSecondary,
                fontFamily = IBMPlexMono,
                fontSize = 11.sp
            )
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            models.forEach { option ->
                DropdownMenuItem(
                    text = { Text(option.label) },
                    onClick = {
                        expanded = false
                        onModelSelected(option.id)
                    }
                )
            }
        }
    }
}

/**
 * Bouton "Arrêter" — annule le tour hermes-webui en cours côté serveur
 * (MainViewModel.cancelActiveChat, GET /api/chat/cancel). Distinct du
 * bouton "⏹ Stop" de VoiceModeRow (onStopTts), qui ne coupe que la
 * synthèse vocale locale sans toucher au run serveur.
 */
@Composable
private fun CancelChatButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .clip(HasanShapes.panelSmall())
            .background(HasanColors.Accent)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp)
    ) {
        Text(text = "⏹ Arrêter", color = HasanColors.TextPrimary, fontFamily = IBMPlexMono, fontSize = 11.sp)
    }
}

@Composable
private fun VoiceModeRow(
    voiceUi: ChatVoiceUi,
    onSwitchToText: () -> Unit,
    onStopTts: () -> Unit
) {
    Box(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            EqualizerBars(active = voiceUi.isWaveActive)
            Text(
                text = voiceUi.statusText,
                color = HasanColors.TextSecondary,
                fontSize = 13.sp,
                modifier = Modifier.padding(top = 4.dp)
            )
            if (voiceUi.showStopTts) {
                Box(
                    modifier = Modifier
                        .padding(top = 4.dp)
                        .clip(HasanShapes.panelSmall())
                        .background(HasanColors.Accent)
                        .clickable(onClick = onStopTts)
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                ) {
                    Text(text = "⏹ Stop", color = HasanColors.TextPrimary, fontSize = 13.sp)
                }
            }
        }
        CutCornerIconButton(
            onClick = onSwitchToText,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .size(40.dp)
        ) {
            androidx.compose.foundation.Image(
                painter = androidx.compose.ui.res.painterResource(com.hasan.v1.R.drawable.ic_keyboard),
                contentDescription = "Basculer en mode texte",
                colorFilter = androidx.compose.ui.graphics.ColorFilter.tint(HasanColors.TextSecondary),
                modifier = Modifier.size(18.dp)
            )
        }
    }
}

@Composable
private fun EqualizerBars(active: Boolean, barHeight: androidx.compose.ui.unit.Dp = 28.dp) {
    Row(
        modifier = Modifier
            .height(48.dp)
            .fillMaxWidth(),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        repeat(5) { index ->
            WaveBar(active = active, delayMs = index * 75, height = barHeight)
            if (index < 4) Spacer(modifier = Modifier.width(6.dp))
        }
    }
}

@Composable
private fun WaveBar(active: Boolean, delayMs: Int, height: androidx.compose.ui.unit.Dp) {
    val transition = rememberInfiniteTransition(label = "wave-bar")
    val scaleY by transition.animateFloat(
        initialValue = 0.15f,
        targetValue = if (active) 1.0f else 0.15f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 380, delayMillis = if (active) delayMs else 0, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "wave-bar-scale"
    )
    Box(
        modifier = Modifier
            .width(4.dp)
            .height(height)
            .scale(scaleY = if (active) scaleY else 0.15f, scaleX = 1f)
            .background(HasanColors.Accent)
    )
}

@Composable
private fun TextModeRow(
    inputUi: ChatInputUi,
    inputText: String,
    onInputTextChange: (String) -> Unit,
    onSend: () -> Unit,
    onMicClick: () -> Unit,
    onMicLongPress: () -> Unit,
    onAttachClick: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (!inputUi.sttVisualizerActive) {
            CutCornerIconButton(
                onClick = onAttachClick,
                modifier = Modifier.size(48.dp).padding(end = 8.dp)
            ) {
                Text(text = "📎", fontSize = 18.sp)
            }
        }
        if (inputUi.sttVisualizerActive) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(48.dp)
                    .clip(HasanShapes.bubble())
                    .background(HasanColors.BgSurface2),
                contentAlignment = Alignment.Center
            ) {
                EqualizerBars(active = true, barHeight = 20.dp)
            }
        } else {
            OutlinedTextField(
                value = inputText,
                onValueChange = onInputTextChange,
                modifier = Modifier.weight(1f),
                enabled = !inputUi.degraded,
                placeholder = { Text(inputUi.hint, color = HasanColors.TextMutedA11y) },
                textStyle = TextStyle(color = HasanColors.TextPrimary, fontSize = 15.sp),
                shape = HasanShapes.bubble(),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedContainerColor = HasanColors.BgSurface2,
                    unfocusedContainerColor = HasanColors.BgSurface2,
                    disabledContainerColor = HasanColors.BgSurface2,
                    focusedBorderColor = HasanColors.Border,
                    unfocusedBorderColor = HasanColors.Border,
                    disabledBorderColor = HasanColors.Border
                ),
                maxLines = 4
            )
        }
        Spacer(modifier = Modifier.width(8.dp))
        MicOrSendButton(
            listening = inputUi.isListening,
            hasText = inputText.isNotBlank(),
            onSend = onSend,
            onMicClick = onMicClick,
            onMicLongPress = onMicLongPress
        )
    }
}

/**
 * Bouton unique à droite du champ de saisie — remplace les anciens boutons micro + envoyer
 * distincts. Bascule entre deux états selon `hasText` :
 *  - texte vide  → état "micro" (forme diagonale accent, badge crayon = indice long-press).
 *  - texte saisi → état "envoyer" (AccentIconButton, flèche haut).
 *
 * SÉCURITÉ UX : le long-press qui ouvre le mode mains libres (`onMicLongPress`) ne doit être
 * câblé QUE sur l'état "micro". Si on le laisse actif sur l'état "envoyer", un utilisateur qui
 * tape un message puis presse longuement par réflexe déclencherait par erreur le mode mains
 * libres au lieu d'envoyer — c'est le piège à ne pas réintroduire en modifiant ce composant.
 * Sur l'état "envoyer", `combinedClickable` n'a donc pas de `onLongClick` (aucun effet spécial).
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MicOrSendButton(
    listening: Boolean,
    hasText: Boolean,
    onSend: () -> Unit,
    onMicClick: () -> Unit,
    onMicLongPress: () -> Unit
) {
    androidx.compose.animation.AnimatedContent(
        targetState = hasText,
        label = "mic-send-toggle"
    ) { showSend ->
        if (showSend) {
            AccentIconButton(
                onClick = onSend,
                modifier = Modifier.size(48.dp)
            ) {
                androidx.compose.foundation.Image(
                    painter = androidx.compose.ui.res.painterResource(com.hasan.v1.R.drawable.ic_arrow_up),
                    contentDescription = "Envoyer le message",
                    colorFilter = androidx.compose.ui.graphics.ColorFilter.tint(HasanColors.Accent),
                    modifier = Modifier.size(20.dp)
                )
            }
        } else {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(HasanShapes.diagonal)
                    .background(HasanColors.Accent)
                    // Long-press actif uniquement ici (état micro) — voir note de sécurité UX ci-dessus.
                    .combinedClickable(onClick = onMicClick, onLongClick = onMicLongPress),
                contentAlignment = Alignment.Center
            ) {
                androidx.compose.foundation.Image(
                    painter = androidx.compose.ui.res.painterResource(
                        if (listening) com.hasan.v1.R.drawable.ic_stop_rounded else com.hasan.v1.R.drawable.ic_mic
                    ),
                    contentDescription = "Activer/désactiver le microphone",
                    colorFilter = androidx.compose.ui.graphics.ColorFilter.tint(Color.White),
                    modifier = Modifier.size(20.dp)
                )
                // Badge d'expansion discret — indique le point d'entrée mode mains libres (long-press).
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .size(14.dp)
                        .padding(1.dp)
                        .clip(RoundedCornerShape(3.dp))
                        .background(HasanColors.BgBase.copy(alpha = 0.85f))
                ) {
                    androidx.compose.foundation.Image(
                        painter = androidx.compose.ui.res.painterResource(com.hasan.v1.R.drawable.ic_edit_small),
                        contentDescription = null,
                        colorFilter = androidx.compose.ui.graphics.ColorFilter.tint(HasanColors.TextMutedA11y),
                        modifier = Modifier.padding(2.dp)
                    )
                }
            }
        }
    }
}
