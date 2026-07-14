package com.hasan.v1.ui.theme

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection

/**
 * Reproduit les clip-path polygon(...) du mockup (docs/design/hasan-mockup-v2.html) —
 * un coin coupé sur chaque coin marqué. Les offsets sont en pixels, pas en fraction :
 * le mockup CSS utilise des tailles de coupe fixes (6px/8px/10px) indépendantes de la
 * taille du composant, pas un pourcentage — un GenericShape à coordonnées normalisées
 * donnerait une coupe qui grandit avec le composant, ce qui n'est pas le rendu voulu.
 *
 * clip-panel (10px)     → CutCornerShape(cut = 10.dp, corners = TopStart, BottomEnd)
 * clip-panel-sm (6px)   → CutCornerShape(cut = 6.dp, corners = TopStart, BottomEnd)
 * bulle user / input (8px, un seul coin) → CutCornerShape(cut = 8.dp, corners = TopStart)
 */
enum class CutCorner { TopStart, TopEnd, BottomStart, BottomEnd }

class CutCornerShape(
    private val cutPx: Float,
    private val corners: Set<CutCorner>
) : Shape {
    override fun createOutline(
        size: Size,
        layoutDirection: LayoutDirection,
        density: Density
    ): Outline {
        val cut = cutPx.coerceAtMost(minOf(size.width, size.height) / 2f)
        val w = size.width
        val h = size.height

        val path = Path().apply {
            moveTo(if (CutCorner.TopStart in corners) cut else 0f, 0f)
            lineTo(if (CutCorner.TopEnd in corners) w - cut else w, 0f)
            if (CutCorner.TopEnd in corners) lineTo(w, cut)
            lineTo(w, if (CutCorner.BottomEnd in corners) h - cut else h)
            if (CutCorner.BottomEnd in corners) lineTo(w - cut, h)
            lineTo(if (CutCorner.BottomStart in corners) cut else 0f, h)
            if (CutCorner.BottomStart in corners) lineTo(0f, h - cut)
            lineTo(0f, if (CutCorner.TopStart in corners) cut else 0f)
            if (CutCorner.TopStart in corners) lineTo(cut, 0f)
            close()
        }
        return Outline.Generic(path)
    }
}

/** Forme asymétrique du brand-mark / mic-btn / fh-mic — clip-path polygon(30% 0, 100% 0, 100% 70%, 70% 100%, 0 100%, 0 30%). */
class DiagonalCutShape(private val fraction: Float = 0.30f) : Shape {
    override fun createOutline(
        size: Size,
        layoutDirection: LayoutDirection,
        density: Density
    ): Outline {
        val w = size.width
        val h = size.height
        val path = Path().apply {
            moveTo(w * fraction, 0f)
            lineTo(w, 0f)
            lineTo(w, h * (1f - fraction))
            lineTo(w * (1f - fraction), h)
            lineTo(0f, h)
            lineTo(0f, h * fraction)
            close()
        }
        return Outline.Generic(path)
    }
}

object HasanShapes {
    /** clip-panel — 10px, coins top-start + bottom-end (panels settings, activity-row). */
    fun panel(cutPx: Float = 28f) = CutCornerShape(cutPx, setOf(CutCorner.TopStart, CutCorner.BottomEnd))

    /** clip-panel-sm — 6px (icon-btn, tag-pill, fh-btn). */
    fun panelSmall(cutPx: Float = 17f) = CutCornerShape(cutPx, setOf(CutCorner.TopStart, CutCorner.BottomEnd))

    /** msg-user / input-field — 8px, un seul coin top-start coupé. */
    fun bubble(cutPx: Float = 22f) = CutCornerShape(cutPx, setOf(CutCorner.TopStart))

    /** brand-mark / mic-btn — polygon asymétrique 30%. */
    val diagonal = DiagonalCutShape(0.30f)

    /** fh-mic (mode mains libres) — polygon asymétrique 20%. */
    val diagonalLarge = DiagonalCutShape(0.20f)
}
