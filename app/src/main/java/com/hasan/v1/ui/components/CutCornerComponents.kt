package com.hasan.v1.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.runtime.getValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hasan.v1.ui.theme.HasanColors
import com.hasan.v1.ui.theme.HasanDimens
import com.hasan.v1.ui.theme.HasanShapes
import com.hasan.v1.ui.theme.IBMPlexMono

/**
 * Panel générique à coin coupé — équivalent Compose de .clip-panel / .settings-panel /
 * .activity-row du mockup (docs/design/hasan-mockup-v2.html).
 */
@Composable
fun CutCornerPanel(
    modifier: Modifier = Modifier,
    shape: Shape = HasanShapes.panel(),
    backgroundColor: Color = HasanColors.BgSurface,
    borderColor: Color = HasanColors.Border,
    content: @Composable () -> Unit
) {
    Box(
        modifier = modifier
            .clip(shape)
            .background(backgroundColor)
            .border(HasanDimens.BorderWidth, borderColor, shape)
    ) {
        content()
    }
}

/** Icon-btn / fh-btn — bouton carré ou compact à coin coupé, fond neutre. */
@Composable
fun CutCornerIconButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    backgroundColor: Color = HasanColors.BgSurface,
    borderColor: Color = HasanColors.Border,
    contentColor: Color = HasanColors.TextSecondary,
    content: @Composable () -> Unit
) {
    val shape = HasanShapes.panelSmall()
    Box(
        modifier = modifier
            .clip(shape)
            .background(backgroundColor)
            .border(HasanDimens.BorderWidth, borderColor, shape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        androidx.compose.runtime.CompositionLocalProvider(
            androidx.compose.material3.LocalContentColor provides contentColor,
            content = content
        )
    }
}

/** icon-btn.send — même forme, palette accent (fond accent-glow-bg, bordure accent-dim, icône accent). */
@Composable
fun AccentIconButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    CutCornerIconButton(
        onClick = onClick,
        modifier = modifier,
        backgroundColor = HasanColors.AccentGlowBg,
        borderColor = HasanColors.AccentDim,
        contentColor = HasanColors.Accent,
        content = content
    )
}

/** tag-pill — CRON/PUSH/AUTH badge coloré, texte mono. */
@Composable
fun TagPill(
    text: String,
    modifier: Modifier = Modifier,
    backgroundColor: Color = HasanColors.AccentDim,
    contentColor: Color = HasanColors.Accent
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(2.dp))
            .background(backgroundColor)
            .padding(horizontal = 6.dp, vertical = 3.dp)
    ) {
        Text(
            text = text,
            color = contentColor,
            fontFamily = IBMPlexMono,
            fontSize = HasanDimens.TextLabelSmall,
            letterSpacing = 0.5.sp
        )
    }
}

/**
 * Toggle custom — rail sombre + knob glissant, accent rouge actif. Ne réutilise pas
 * Switch Material par défaut (le mockup a un style rail/knob non standard, voir .toggle
 * / .toggle-knob dans hasan-mockup-v2.html).
 */
@Composable
fun HasanToggle(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    val railColor = if (checked) HasanColors.AccentDim else HasanColors.BgSurface3
    val borderColor = if (checked) HasanColors.Accent else HasanColors.Border
    val knobColor = if (checked) HasanColors.Accent else HasanColors.TextMutedA11y
    val knobOffset by androidx.compose.animation.core.animateDpAsState(
        targetValue = if (checked) 18.dp else 2.dp,
        label = "toggle-knob-offset"
    )

    Box(
        modifier = modifier
            .size(width = 34.dp, height = 18.dp)
            .clip(RoundedCornerShape(2.dp))
            .background(railColor)
            .border(1.dp, borderColor, RoundedCornerShape(2.dp))
            .clickable { onCheckedChange(!checked) }
    ) {
        Box(
            modifier = Modifier
                .padding(start = knobOffset, top = 2.dp)
                .size(12.dp)
                .clip(RoundedCornerShape(1.dp))
                .background(knobColor)
        )
    }
}
