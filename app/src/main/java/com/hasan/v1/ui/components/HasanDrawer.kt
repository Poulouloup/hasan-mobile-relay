package com.hasan.v1.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.DrawerState
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.Text
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hasan.v1.R
import com.hasan.v1.ui.screens.CutCornerOutlineButton
import com.hasan.v1.ui.theme.ChakraPetch
import com.hasan.v1.ui.theme.HasanColors
import com.hasan.v1.ui.theme.HasanDimens
import com.hasan.v1.ui.theme.IBMPlexMono
import kotlinx.coroutines.launch

enum class HasanNavTab { CHAT, TASKS, SKILLS, MEMORY, TOOLS, SETTINGS }

data class HasanNavItem(val tab: HasanNavTab, val iconRes: Int, val label: String)

/**
 * Header minimal (juste le hamburger) pour les écrans qui n'ont pas de HasanHeader
 * complet (Activité, Paramètres) — le drawer doit rester accessible depuis tous les
 * écrans, pas seulement Chat, sinon impasse de navigation une fois sur un autre onglet.
 */
@Composable
fun HasanMinimalHeader(onMenuClick: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = HasanDimens.SpacingXl, vertical = HasanDimens.SpacingM),
        verticalAlignment = Alignment.CenterVertically
    ) {
        HasanIconButton(
            iconRes = R.drawable.ic_menu_hamburger,
            contentDescription = "Menu",
            onClick = onMenuClick
        )
    }
}

/** Session affichée dans le drawer — label déjà formaté par l'appelant ("01. Monte-Cristo"). */
data class DrawerSessionItem(
    val id: String,
    val label: String,
    val isActive: Boolean
)

data class DrawerUiState(
    val navItems: List<HasanNavItem>,
    val selectedTab: HasanNavTab,
    val sessions: List<DrawerSessionItem>
)

class DrawerCallbacks(
    val onNavItemClick: (HasanNavTab) -> Unit,
    val onSessionClick: (String) -> Unit,
    val onSessionRename: (String) -> Unit,
    val onSessionDelete: (String) -> Unit,
    val onNewSession: () -> Unit,
    val onQuit: () -> Unit,
    val onClose: () -> Unit
)

/**
 * Englobe tout [content] dans un [ModalNavigationDrawer] Material3 — ouverture par
 * swipe ou via [drawerState] piloté par l'appelant (MainActivity.openDrawer()).
 *
 * Le geste de swipe natif de [ModalNavigationDrawer] réagit sur toute la largeur de
 * l'écran, ce qui entre en conflit avec le scroll horizontal du contenu (ex: liste de
 * messages) côté droit lorsqu'il est FERMÉ — remplacé dans ce cas par un détecteur de
 * drag manuel scopé à la moitié gauche de l'écran uniquement (zone où l'utilisateur ne
 * scrolle quasiment jamais), voir [leftHalfSwipeToOpen]. Une fois OUVERT, le geste natif
 * est réactivé (gesturesEnabled = drawerState.isOpen) : fermer en swipant vers la gauche
 * depuis le panneau lui-même ne présente aucun conflit avec un scroll de contenu (le
 * contenu de fond n'est plus interactif tant que le drawer est ouvert).
 *
 * Accessible depuis les écrans principaux (Chat via HasanHeader, Tools/Paramètres/etc.
 * via HasanMinimalHeader) — sinon impasse de navigation une fois sorti de Chat, aucun
 * moyen d'y revenir. Englobé au niveau MainActivity plutôt que par fragment car
 * "Drawer latéral géré par MainActivity, pas par les fragments" (.claude/rules/architecture.md).
 */
@Composable
fun HasanDrawerScaffold(
    state: DrawerUiState,
    callbacks: DrawerCallbacks,
    drawerState: DrawerState = rememberDrawerState(DrawerValue.Closed),
    content: @Composable () -> Unit
) {
    val scope = rememberCoroutineScope()
    ModalNavigationDrawer(
        drawerState = drawerState,
        gesturesEnabled = drawerState.isOpen,
        drawerContent = {
            ModalDrawerSheet(drawerContainerColor = HasanColors.BgBase) {
                HasanDrawerContent(state = state, callbacks = callbacks)
            }
        }
    ) {
        Box(modifier = Modifier.leftHalfSwipeToOpen(drawerState, scope)) {
            content()
        }
    }
}

/**
 * Ouvre le drawer sur un drag horizontal vers la droite démarré dans la moitié
 * gauche de l'écran — évite le conflit avec le scroll horizontal du contenu
 * (ex: bulles de code, liste de messages) qui se produit surtout côté droit.
 * Un drag qui démarre dans la moitié droite, ou qui va vers la gauche, est
 * ignoré (laissé au contenu en dessous, `awaitFirstDown(requireUnconsumed = false)`
 * ne bloque rien tant que le seuil horizontal n'est pas dépassé).
 */
private fun Modifier.leftHalfSwipeToOpen(drawerState: DrawerState, scope: kotlinx.coroutines.CoroutineScope): Modifier =
    this.pointerInput(drawerState) {
        val dragThreshold = 24.dp.toPx()
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            if (drawerState.isOpen || down.position.x > size.width / 2f) return@awaitEachGesture

            var dragged = false
            while (true) {
                val event = awaitPointerEvent()
                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                if (!change.pressed) break

                val deltaX = change.position.x - down.position.x
                val deltaY = change.position.y - down.position.y
                if (!dragged) {
                    if (deltaX > dragThreshold && kotlin.math.abs(deltaX) > kotlin.math.abs(deltaY) * 1.5f) {
                        dragged = true
                        change.consume()
                        scope.launch { drawerState.open() }
                    } else if (kotlin.math.abs(deltaY) > dragThreshold) {
                        break // scroll vertical — on laisse la main au contenu
                    }
                } else {
                    change.consume()
                }
            }
        }
    }

@Composable
fun HasanDrawerContent(
    state: DrawerUiState,
    callbacks: DrawerCallbacks
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(HasanColors.BgBase)
            .padding(vertical = HasanDimens.SpacingXl)
    ) {
        DrawerHeader(onClose = callbacks.onClose)

        Spacer(modifier = Modifier.height(HasanDimens.SpacingXxl))
        DrawerSectionTitle("NAVIGATION")
        Column {
            state.navItems.forEach { item ->
                DrawerNavRow(
                    item = item,
                    isActive = item.tab == state.selectedTab,
                    onClick = { callbacks.onNavItemClick(item.tab) }
                )
            }
        }

        Spacer(modifier = Modifier.height(HasanDimens.SpacingXxl))
        DrawerSectionTitle("SESSIONS ACTIVES")
        LazyColumn(modifier = Modifier.weight(1f)) {
            items(state.sessions, key = { it.id }) { session ->
                DrawerSessionRow(
                    session = session,
                    onClick = { callbacks.onSessionClick(session.id) },
                    onRename = { callbacks.onSessionRename(session.id) },
                    onDelete = { callbacks.onSessionDelete(session.id) }
                )
            }
        }

        Spacer(modifier = Modifier.height(HasanDimens.SpacingM))
        Box(modifier = Modifier.fillMaxWidth().height(HasanDimens.BorderWidth).background(HasanColors.Border).padding(horizontal = HasanDimens.SpacingXl))
        Spacer(modifier = Modifier.height(HasanDimens.SpacingM))

        CutCornerOutlineButton(
            text = "+ Nouvelle Session",
            onClick = callbacks.onNewSession,
            modifier = Modifier.padding(horizontal = HasanDimens.SpacingXl)
        )
        Spacer(modifier = Modifier.height(HasanDimens.SpacingS))
        DrawerQuitRow(onClick = callbacks.onQuit)
    }
}

@Composable
private fun DrawerHeader(onClose: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = HasanDimens.SpacingXl),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = "HASAN",
            color = HasanColors.TextPrimary,
            fontFamily = ChakraPetch,
            fontWeight = FontWeight.SemiBold,
            fontSize = HasanDimens.TextDisplaySmall,
            letterSpacing = 2.sp
        )
        HasanIconButton(
            iconRes = R.drawable.ic_close,
            contentDescription = "Fermer",
            onClick = onClose,
            tint = HasanColors.TextMutedA11y
        )
    }
}

@Composable
private fun DrawerSectionTitle(text: String) {
    Text(
        text = text,
        color = HasanColors.TextMutedA11y,
        fontFamily = IBMPlexMono,
        fontSize = HasanDimens.TextLabelMedium,
        letterSpacing = 1.sp,
        modifier = Modifier.padding(horizontal = HasanDimens.SpacingXl, vertical = HasanDimens.SpacingS)
    )
}

@Composable
private fun DrawerNavRow(item: HasanNavItem, isActive: Boolean, onClick: () -> Unit) {
    val contentColor = if (isActive) HasanColors.Accent else HasanColors.TextPrimary
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(if (isActive) HasanColors.AccentDim else androidx.compose.ui.graphics.Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = HasanDimens.SpacingXl, vertical = HasanDimens.SpacingM),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .width(3.dp)
                .height(HasanDimens.IconSmall)
                .background(if (isActive) HasanColors.Accent else androidx.compose.ui.graphics.Color.Transparent)
        )
        Spacer(modifier = Modifier.width(HasanDimens.SpacingM))
        Image(
            painter = painterResource(item.iconRes),
            contentDescription = null,
            colorFilter = ColorFilter.tint(contentColor),
            modifier = Modifier.size(HasanDimens.IconSmall)
        )
        Spacer(modifier = Modifier.width(HasanDimens.SpacingM))
        Text(text = item.label, color = contentColor, fontFamily = IBMPlexMono, fontSize = HasanDimens.TextSubtitle)
    }
}

/** Appui long ouvre un menu Renommer/Supprimer — même geste que l'ancien SessionRow de SettingsScreen.kt. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun DrawerSessionRow(
    session: DrawerSessionItem,
    onClick: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit
) {
    var menuExpanded by remember(session.id) { mutableStateOf(false) }
    val contentColor = if (session.isActive) HasanColors.Accent else HasanColors.TextPrimary
    Box {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .combinedClickable(onClick = onClick, onLongClick = { menuExpanded = true })
                .padding(horizontal = HasanDimens.SpacingXl, vertical = HasanDimens.SpacingS),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = session.label,
                color = contentColor,
                fontFamily = IBMPlexMono,
                fontSize = HasanDimens.TextBodyMedium,
                modifier = Modifier.weight(1f)
            )
            if (session.isActive) {
                Box(
                    modifier = Modifier
                        .size(6.dp)
                        .clip(CircleShape)
                        .background(HasanColors.Accent)
                )
            }
        }
        DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
            DropdownMenuItem(
                text = { Text("Renommer") },
                onClick = { menuExpanded = false; onRename() }
            )
            DropdownMenuItem(
                text = { Text("Supprimer") },
                onClick = { menuExpanded = false; onDelete() }
            )
        }
    }
}

@Composable
private fun DrawerQuitRow(onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = HasanDimens.SpacingXl, vertical = HasanDimens.SpacingM),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Image(
            painter = painterResource(R.drawable.ic_logout),
            contentDescription = null,
            colorFilter = ColorFilter.tint(HasanColors.TextMutedA11y),
            modifier = Modifier.size(HasanDimens.IconSmall)
        )
        Spacer(modifier = Modifier.width(HasanDimens.SpacingM))
        Text(text = "Quitter l'app", color = HasanColors.TextMutedA11y, fontFamily = IBMPlexMono, fontSize = HasanDimens.TextSubtitle)
    }
}
