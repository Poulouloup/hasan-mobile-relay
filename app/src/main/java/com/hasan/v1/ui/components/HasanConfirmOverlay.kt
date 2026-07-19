package com.hasan.v1.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.hasan.v1.ui.screens.CutCornerOutlineButton
import com.hasan.v1.ui.theme.HasanColors
import com.hasan.v1.ui.theme.HasanDimens
import com.hasan.v1.ui.theme.HasanShapes
import com.hasan.v1.ui.theme.IBMPlexSans

/**
 * Bandeau de confirmation plein écran, même DA que [com.hasan.v1.ui.screens.ChatScreen]
 * (ApprovalOverlay/ClarifyOverlay) — remplace `HasanDialog.confirm` (android.app.Dialog
 * natif, thème système) pour les confirmations qui doivent visuellement appartenir à
 * l'app plutôt qu'au système. Composable générique (pas spécifique au chat) : posé au
 * niveau racine (MainActivity.setupDrawerRoot) pour rester au-dessus du drawer/contenu.
 */
@Composable
fun HasanConfirmOverlay(
    message: String,
    title: String? = null,
    confirmLabel: String = "Confirmer",
    cancelLabel: String = "Annuler",
    destructive: Boolean = false,
    onConfirm: () -> Unit,
    onCancel: () -> Unit
) {
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
                .padding(HasanDimens.SpacingXxl)
                .background(HasanColors.BgSurface, HasanShapes.panel())
                .padding(HasanDimens.SpacingXl)
        ) {
            if (title != null) {
                Text(
                    text = title,
                    color = HasanColors.TextPrimary,
                    fontFamily = IBMPlexSans,
                    fontSize = HasanDimens.TextDisplaySmall
                )
                Spacer(modifier = Modifier.height(HasanDimens.SpacingS))
            }
            Text(
                text = message,
                color = HasanColors.TextSecondary,
                fontFamily = IBMPlexSans,
                fontSize = HasanDimens.TextBodyMedium
            )
            Spacer(modifier = Modifier.height(HasanDimens.SpacingL))
            CutCornerOutlineButton(
                text = confirmLabel,
                onClick = onConfirm,
                borderColor = if (destructive) HasanColors.Accent else HasanColors.Border,
                contentColor = if (destructive) HasanColors.Accent else HasanColors.TextSecondary
            )
            Spacer(modifier = Modifier.height(HasanDimens.SpacingS))
            CutCornerOutlineButton(
                text = cancelLabel,
                onClick = onCancel
            )
        }
    }
}
