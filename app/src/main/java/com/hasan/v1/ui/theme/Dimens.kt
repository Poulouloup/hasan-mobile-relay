package com.hasan.v1.ui.theme

import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Constantes de dimensions Compose — même esprit que HasanColors/HasanShapes
 * (object simple, pas de dépendance à LocalDensity/@Composable). Introduit
 * pour remplacer les dp/sp en dur dispersés dans ui/screens et ui/components
 * (valeurs incohérentes d'un écran à l'autre avant ce fichier).
 *
 * Les tailles de texte alignées sur HasanTypography (Type.kt) sont de
 * simples alias TextUnit — HasanTypography reste la source de vérité pour
 * le texte porteur de sens ; ces alias servent aux call sites qui gardent
 * un fontFamily/letterSpacing custom incompatible avec un TextStyle complet.
 */
object HasanDimens {

    // ─────────────────────────── Spacing ───────────────────────────────────
    val SpacingXxs = 2.dp
    val SpacingXs = 4.dp
    val SpacingS = 8.dp
    val SpacingM = 12.dp
    val SpacingL = 16.dp
    val SpacingXl = 20.dp
    val SpacingXxl = 24.dp
    val SpacingXxxl = 32.dp

    // ─────────────────────────── Touch targets & icônes ────────────────────
    /** Zone tactile minimale conforme Material (hamburger, close, tout IconButton). */
    val TouchTarget = 48.dp
    val IconSmall = 16.dp
    val IconMedium = 24.dp
    val IconLarge = 32.dp

    val BorderWidth = 1.dp

    // ─────────────────────────── Texte — alias HasanTypography ─────────────
    val TextLabelSmall = 9.sp        // == HasanTypography.labelSmall
    val TextLabelMedium = 10.5.sp    // == HasanTypography.labelMedium
    val TextBodyMedium = 12.sp       // == HasanTypography.bodyMedium
    val TextBodyLarge = 12.5.sp      // == HasanTypography.bodyLarge
    val TextDisplaySmall = 15.sp     // == HasanTypography.displaySmall
    val TextTitleMedium = 20.sp      // == HasanTypography.titleMedium

    // ─────────────────────────── Texte — paliers complémentaires ───────────
    // Valeurs récurrentes hors des 6 styles Typography ci-dessus.
    val TextCaption = 11.sp
    val TextSubtitle = 13.sp
    val TextBody = 14.sp
    val TextTitle = 16.sp
    val TextHeading = 18.sp
    val TextDisplay = 32.sp

    // ─────────────────────────── Tokens dédiés ──────────────────────────────
    /** Padding des bulles de chat (UserBubble/AssistantBubble) — hors échelle Spacing générique. */
    val BubblePaddingH = 14.dp
    val BubblePaddingV = 10.dp
}
