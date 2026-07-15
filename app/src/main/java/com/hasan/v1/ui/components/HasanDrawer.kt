package com.hasan.v1.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.Text
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hasan.v1.R
import com.hasan.v1.ui.screens.CutCornerOutlineButton
import com.hasan.v1.ui.theme.ChakraPetch
import com.hasan.v1.ui.theme.HasanColors
import com.hasan.v1.ui.theme.IBMPlexMono

enum class HasanNavTab { CHAT, ACTIVITY, SETTINGS }

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
            .padding(horizontal = 18.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Image(
            painter = painterResource(R.drawable.ic_menu_hamburger),
            contentDescription = "Menu",
            colorFilter = ColorFilter.tint(HasanColors.TextPrimary),
            modifier = Modifier.size(22.dp).clickable(onClick = onMenuClick)
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
 * swipe depuis le bord gauche (comportement natif du composant) ou via [drawerState]
 * piloté par l'appelant (MainActivity.openDrawer()).
 *
 * Accessible depuis les 3 écrans principaux (Chat via HasanHeader, Activité/Paramètres
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
    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet(drawerContainerColor = HasanColors.BgBase) {
                HasanDrawerContent(state = state, callbacks = callbacks)
            }
        },
        content = content
    )
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
            .padding(vertical = 20.dp)
    ) {
        DrawerHeader(onClose = callbacks.onClose)

        Spacer(modifier = Modifier.height(24.dp))
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

        Spacer(modifier = Modifier.height(24.dp))
        DrawerSectionTitle("SESSIONS ACTIVES")
        LazyColumn(modifier = Modifier.weight(1f)) {
            items(state.sessions, key = { it.id }) { session ->
                DrawerSessionRow(
                    session = session,
                    onClick = { callbacks.onSessionClick(session.id) },
                    onLongClick = { callbacks.onSessionRename(session.id) }
                )
            }
        }

        Spacer(modifier = Modifier.height(12.dp))
        Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(HasanColors.Border).padding(horizontal = 20.dp))
        Spacer(modifier = Modifier.height(12.dp))

        CutCornerOutlineButton(
            text = "+ Nouvelle Session",
            onClick = callbacks.onNewSession,
            modifier = Modifier.padding(horizontal = 20.dp)
        )
        Spacer(modifier = Modifier.height(8.dp))
        DrawerQuitRow(onClick = callbacks.onQuit)
    }
}

@Composable
private fun DrawerHeader(onClose: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = "HASAN",
            color = HasanColors.TextPrimary,
            fontFamily = ChakraPetch,
            fontWeight = FontWeight.SemiBold,
            fontSize = 15.sp,
            letterSpacing = 2.sp
        )
        Image(
            painter = painterResource(R.drawable.ic_close),
            contentDescription = "Fermer",
            colorFilter = ColorFilter.tint(HasanColors.TextMutedA11y),
            modifier = Modifier.size(20.dp).clickable(onClick = onClose)
        )
    }
}

@Composable
private fun DrawerSectionTitle(text: String) {
    Text(
        text = text,
        color = HasanColors.TextMutedA11y,
        fontFamily = IBMPlexMono,
        fontSize = 10.sp,
        letterSpacing = 1.sp,
        modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)
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
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .width(3.dp)
                .height(16.dp)
                .background(if (isActive) HasanColors.Accent else androidx.compose.ui.graphics.Color.Transparent)
        )
        Spacer(modifier = Modifier.width(12.dp))
        Image(
            painter = painterResource(item.iconRes),
            contentDescription = null,
            colorFilter = ColorFilter.tint(contentColor),
            modifier = Modifier.size(18.dp)
        )
        Spacer(modifier = Modifier.width(12.dp))
        Text(text = item.label, color = contentColor, fontFamily = IBMPlexMono, fontSize = 13.sp)
    }
}

@Composable
private fun DrawerSessionRow(
    session: DrawerSessionItem,
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
    val contentColor = if (session.isActive) HasanColors.Accent else HasanColors.TextPrimary
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = session.label,
            color = contentColor,
            fontFamily = IBMPlexMono,
            fontSize = 12.sp,
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
}

@Composable
private fun DrawerQuitRow(onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Image(
            painter = painterResource(R.drawable.ic_logout),
            contentDescription = null,
            colorFilter = ColorFilter.tint(HasanColors.TextMutedA11y),
            modifier = Modifier.size(18.dp)
        )
        Spacer(modifier = Modifier.width(12.dp))
        Text(text = "Quitter l'app", color = HasanColors.TextMutedA11y, fontFamily = IBMPlexMono, fontSize = 13.sp)
    }
}
