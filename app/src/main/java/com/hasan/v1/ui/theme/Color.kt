package com.hasan.v1.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * Design tokens couleur — extraits de docs/design/hasan-mockup-v2.html (référence
 * visuelle de l'étape 9, refonte UI). Ne pas réutiliser les couleurs XML de
 * res/values/colors.xml : cette palette diverge volontairement (bg-surface-2/3
 * n'ont pas d'équivalent XML) et les deux systèmes cohabitent le temps de la
 * migration progressive vers Compose.
 */
object HasanColors {
    val BgBase = Color(0xFF0A0A0A)
    val BgSurface = Color(0xFF131110)
    val BgSurface2 = Color(0xFF1A1716)
    val BgSurface3 = Color(0xFF211C1B)
    val Border = Color(0xFF2A211F)
    val Accent = Color(0xFFCC2936)
    val AccentDim = Color(0xFF3D1418)
    val AccentGlowBg = Color(0xFF1F0E0F)
    val TextPrimary = Color(0xFFF2EFEB)
    val TextSecondary = Color(0xFF918B86)

    /** Décoratif uniquement (bordures fines, séparateurs) — contraste 2.35:1 sur BgBase, sous le seuil WCAG AA. */
    val TextMuted = Color(0xFF524C49)

    /** À utiliser pour tout texte lisible (timestamps, labels mono, hints) — 4.57:1 sur BgBase, conforme WCAG AA. */
    val TextMutedA11y = Color(0xFF807873)

    /**
     * Couleurs fixes par colonne Kanban (mapping en dur côté app — les
     * colonnes serveur (BOARD_COLUMNS) n'ont ni nom ni couleur personnalisable,
     * voir docs/ARCHITECTURE.md#kanban). Issues du mockup fourni par l'utilisateur
     * (hasan-kanban-mockup-v2-grouped.html) ; KanbanGreen/KanbanPurple du mockup
     * non repris faute de 7e colonne côté serveur à leur associer.
     */
    val KanbanGray = Color(0xFF5C5652)
    val KanbanBlue = Color(0xFF5B8AB0)
    val KanbanGold = Color(0xFFB8862E)
    val KanbanRed = Color(0xFF7A3A3A)
    val KanbanMuted = Color(0xFF3A3632)
}
