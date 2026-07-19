package com.hasan.v1.ui.screens

import androidx.compose.animation.core.EaseOut
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hasan.v1.R
import com.hasan.v1.ui.theme.HasanColors
import com.hasan.v1.ui.theme.HasanDimens
import com.hasan.v1.ui.theme.HasanShapes
import com.hasan.v1.ui.theme.IBMPlexMono

/**
 * Mode mains libres — overlay plein écran (mockup #freehand-overlay). Transpose la
 * logique déjà validée de LightModeFragment (mute/unmute avec coupure TTS, rendu
 * d'état vocal, vibrations) sans la modifier — voir FreeHandUiState pour le contrat
 * exact attendu par ConversationFragment.
 */
data class FreeHandUiState(
    val statusText: String,
    val lastMessage: String,
    val isListening: Boolean,
    val isMuted: Boolean
)

@Composable
fun FreeHandScreen(
    state: FreeHandUiState,
    onExit: () -> Unit,
    onToggleMute: () -> Unit,
    onMicClick: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(HasanColors.BgBase)
            .padding(horizontal = HasanDimens.SpacingXl, vertical = HasanDimens.SpacingXxl),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                FreeHandMic(
                    isListening = state.isListening,
                    isMuted = state.isMuted,
                    onClick = onMicClick
                )
                if (state.lastMessage.isNotBlank()) {
                    Text(
                        text = state.lastMessage,
                        color = HasanColors.TextSecondary,
                        fontSize = HasanDimens.TextBody,
                        textAlign = TextAlign.Center,
                        maxLines = 3,
                        modifier = Modifier.padding(top = HasanDimens.SpacingXxl, start = HasanDimens.SpacingL, end = HasanDimens.SpacingL)
                    )
                }
                Text(
                    text = state.statusText,
                    color = HasanColors.TextMutedA11y,
                    fontFamily = IBMPlexMono,
                    fontSize = HasanDimens.TextCaption,
                    letterSpacing = 2.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = HasanDimens.SpacingL)
                )
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            FreeHandTextButton(
                label = stringResource(R.string.light_mode_exit_short),
                onClick = onExit
            )
            FreeHandTextButton(
                label = if (state.isMuted) {
                    stringResource(R.string.light_mode_muted)
                } else {
                    stringResource(R.string.light_mode_mute)
                },
                muted = state.isMuted,
                onClick = onToggleMute
            )
        }
    }
}

@Composable
private fun FreeHandTextButton(label: String, muted: Boolean = false, onClick: () -> Unit) {
    val backgroundColor = if (muted) HasanColors.AccentGlowBg else HasanColors.BgSurface
    val borderColor = if (muted) HasanColors.AccentDim else HasanColors.Border
    val contentColor = if (muted) HasanColors.Accent else HasanColors.TextSecondary
    val shape = HasanShapes.panelSmall()

    Row(
        modifier = Modifier
            .clip(shape)
            .background(backgroundColor)
            .border(HasanDimens.BorderWidth, borderColor, shape)
            .clickable(onClick = onClick)
            .padding(horizontal = HasanDimens.SpacingM, vertical = HasanDimens.SpacingS),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Text(
            text = label,
            color = contentColor,
            fontFamily = IBMPlexMono,
            fontSize = HasanDimens.TextLabelSmall,
            letterSpacing = 1.sp
        )
    }
}

@Composable
private fun FreeHandMic(isListening: Boolean, isMuted: Boolean, onClick: () -> Unit) {
    val transition = rememberInfiniteTransition(label = "fh-mic-ring")
    val ringScale by transition.animateFloat(
        initialValue = 1f,
        targetValue = 1.35f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1800, easing = EaseOut),
            repeatMode = RepeatMode.Restart
        ),
        label = "fh-mic-ring-scale"
    )
    val ringAlpha by transition.animateFloat(
        initialValue = 0.6f,
        targetValue = 0f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1800, easing = EaseOut),
            repeatMode = RepeatMode.Restart
        ),
        label = "fh-mic-ring-alpha"
    )

    val micColor = if (isMuted) HasanColors.BgSurface2 else HasanColors.Accent

    Box(contentAlignment = Alignment.Center) {
        if (!isMuted && isListening) {
            Box(
                modifier = Modifier
                    .size(148.dp)
                    .scale(ringScale)
                    .alpha(ringAlpha)
                    .clip(HasanShapes.diagonalLarge)
                    .background(HasanColors.Accent)
            )
        }
        Box(
            modifier = Modifier
                .size(128.dp)
                .clip(HasanShapes.diagonalLarge)
                .background(micColor)
                .clickable(onClick = onClick),
            contentAlignment = Alignment.Center
        ) {
            Image(
                painter = painterResource(if (isMuted) R.drawable.ic_volume_off else R.drawable.ic_mic),
                contentDescription = null,
                colorFilter = ColorFilter.tint(if (isMuted) HasanColors.TextMutedA11y else HasanColors.BgBase),
                modifier = Modifier.size(34.dp)
            )
        }
    }
}
