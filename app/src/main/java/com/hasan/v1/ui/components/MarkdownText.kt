package com.hasan.v1.ui.components

import android.content.Context
import android.text.method.LinkMovementMethod
import android.widget.TextView
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.res.ResourcesCompat
import androidx.compose.ui.graphics.Color
import com.hasan.v1.ui.theme.HasanColors
import io.noties.markwon.Markwon
import io.noties.markwon.ext.strikethrough.StrikethroughPlugin
import io.noties.markwon.ext.tables.TablePlugin
import io.noties.markwon.linkify.LinkifyPlugin

/**
 * Rendu Markdown partagé (Markwon) — extrait de ChatScreen.kt pour être
 * réutilisable par tout écran affichant du contenu markdown côté serveur
 * (bulles de chat, contenu SKILL.md brut dans l'écran Skills, etc.).
 */
private var sharedMarkwon: Markwon? = null

private fun getMarkwon(context: Context): Markwon =
    sharedMarkwon ?: Markwon.builder(context)
        .usePlugin(StrikethroughPlugin.create())
        .usePlugin(TablePlugin.create(context))
        .usePlugin(LinkifyPlugin.create())
        .build()
        .also { sharedMarkwon = it }

@Composable
fun MarkdownText(
    text: String,
    selectable: Boolean,
    modifier: Modifier = Modifier,
    alphaValue: Float = 1f
) {
    val context = LocalContext.current
    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            TextView(ctx).apply {
                movementMethod = LinkMovementMethod.getInstance()
                setTextColor(HasanColors.TextPrimary.toArgbInt())
                textSize = 15f
                typeface = ResourcesCompat.getFont(ctx, com.hasan.v1.R.font.ibm_plex_sans_regular)
            }
        },
        update = { tv ->
            tv.setTextIsSelectable(selectable)
            tv.alpha = alphaValue
            getMarkwon(context).setMarkdown(tv, text)
        }
    )
}

private fun Color.toArgbInt(): Int = android.graphics.Color.argb(
    (alpha * 255).toInt(), (red * 255).toInt(), (green * 255).toInt(), (blue * 255).toInt()
)
