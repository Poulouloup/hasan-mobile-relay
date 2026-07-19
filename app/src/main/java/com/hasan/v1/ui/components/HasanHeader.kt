package com.hasan.v1.ui.components

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hasan.v1.R
import com.hasan.v1.ui.theme.ChakraPetch
import com.hasan.v1.ui.theme.HasanColors
import com.hasan.v1.ui.theme.HasanDimens
import com.hasan.v1.ui.theme.HasanShapes
import com.hasan.v1.ui.theme.IBMPlexMono

/**
 * État de connexion affiché dans le header — dérivé de
 * [com.hasan.v1.network.RelayConnectionStatus] côté appelant (ConversationFragment),
 * pas un doublon d'enum : ce composant reste agnostique du type exact pour être
 * réutilisable indépendamment du réseau relay (ex: futur badge orchestrateur MCP).
 */
data class ConnectionBadgeState(
    val connected: Boolean,
    /** ex: "WSS · 42MS" — déjà formaté par l'appelant, ce composant ne fait pas de logique métier. */
    val readout: String
)

/** Header — hamburger (ouvre le drawer), logo à coin diagonal, wordmark HASAN, badge de
 * connexion avec point pulsant. Voir .header dans hasan-mockup-v2.html. */
@Composable
fun HasanHeader(
    connectionState: ConnectionBadgeState,
    onMenuClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = HasanDimens.SpacingXl, vertical = HasanDimens.SpacingM),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(HasanDimens.SpacingS)) {
            HasanIconButton(
                iconRes = R.drawable.ic_menu_hamburger,
                contentDescription = "Menu",
                onClick = onMenuClick
            )
            BrandMark()
            Text(
                text = "HASAN",
                color = HasanColors.TextPrimary,
                fontFamily = ChakraPetch,
                fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
                fontSize = HasanDimens.TextDisplaySmall,
                letterSpacing = 2.sp
            )
        }
        ConnectionBadge(connectionState)
    }
}

/**
 * Bouton icône à zone tactile 48dp (norme Material) — icône visuelle 24dp
 * centrée. Remplace les Image+clickable bruts dupliqués précédemment dans
 * HasanHeader (hamburger 22dp) et HasanDrawer (hamburger + close, 22dp/20dp),
 * qui n'offraient pas de marge tactile suffisante (zone cliquable = taille
 * exacte de l'icône, aucune marge d'erreur au toucher).
 */
@Composable
fun HasanIconButton(
    iconRes: Int,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tint: Color = HasanColors.TextPrimary
) {
    Box(
        modifier = modifier
            .size(HasanDimens.TouchTarget)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Image(
            painter = painterResource(iconRes),
            contentDescription = contentDescription,
            colorFilter = ColorFilter.tint(tint),
            modifier = Modifier.size(HasanDimens.IconMedium)
        )
    }
}

@Composable
private fun BrandMark() {
    Box(
        modifier = Modifier
            .size(30.dp)
            .clip(HasanShapes.diagonal)
            .background(HasanColors.Accent),
        contentAlignment = Alignment.Center
    ) {
        Image(
            painter = painterResource(R.drawable.ic_hasan_brand_glyph),
            contentDescription = null,
            modifier = Modifier.size(15.dp)
        )
    }
}

@Composable
private fun ConnectionBadge(state: ConnectionBadgeState) {
    val transition = rememberInfiniteTransition(label = "conn-dot-pulse")
    val dotAlpha by transition.animateFloat(
        initialValue = 1f,
        targetValue = 0.2f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 900, easing = androidx.compose.animation.core.EaseInOut),
            repeatMode = RepeatMode.Reverse
        ),
        label = "conn-dot-alpha"
    )
    val dotColor = if (state.connected) HasanColors.Accent else HasanColors.TextMutedA11y

    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        androidx.compose.foundation.layout.Box(
            modifier = Modifier
                .size(5.dp)
                .alpha(if (state.connected) dotAlpha else 1f)
                .clip(CircleShape)
                .background(dotColor)
        )
        Text(
            text = state.readout,
            color = HasanColors.TextMutedA11y,
            fontFamily = IBMPlexMono,
            fontSize = HasanDimens.TextLabelSmall,
            letterSpacing = 0.5.sp
        )
    }
}
