package com.hasan.v1.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hasan.v1.webui.models.KANBAN_COLUMNS
import com.hasan.v1.webui.models.KANBAN_STATUS_RUNNING
import com.hasan.v1.webui.models.KanbanBoardSummary
import com.hasan.v1.webui.models.KanbanTask
import com.hasan.v1.ui.components.CutCornerPanel
import com.hasan.v1.ui.components.HasanMinimalHeader
import com.hasan.v1.ui.components.MarkdownText
import com.hasan.v1.ui.components.TagPill
import com.hasan.v1.ui.theme.ChakraPetch
import com.hasan.v1.ui.theme.HasanColors
import com.hasan.v1.ui.theme.HasanDimens
import com.hasan.v1.ui.theme.HasanShapes
import com.hasan.v1.ui.theme.IBMPlexMono
import kotlinx.coroutines.launch

/** État affiché par l'écran Kanban — reflète KanbanViewModel.uiState (hors détail tâche, écran séparé). */
data class KanbanScreenUiState(
    val board: com.hasan.v1.webui.models.KanbanBoard?,
    val boards: List<KanbanBoardSummary>,
    val selectedBoardSlug: String?,
    val loading: Boolean,
    val errorMessage: String?,
    val showCreateBoardDialog: Boolean,
    val showCreateTaskDialog: Boolean
)

class KanbanCallbacks(
    val onMenuClick: () -> Unit,
    val onRefresh: () -> Unit,
    val onTaskClick: (KanbanTask) -> Unit,
    val onMoveTask: (KanbanTask, String) -> Unit,
    val onSelectBoard: (String) -> Unit,
    val onShowCreateBoard: () -> Unit,
    val onDismissCreateBoard: () -> Unit,
    val onCreateBoard: (slug: String, name: String?) -> Unit,
    val onShowCreateTask: () -> Unit,
    val onDismissCreateTask: () -> Unit,
    val onCreateTask: (title: String, body: String?, status: String?) -> Unit,
    val onDismissError: () -> Unit
)

/** Nom lisible d'une colonne — les slugs serveur (triage, todo...) sont en anglais, affichés en français ici. */
private fun columnLabel(name: String): String = when (name) {
    "triage" -> "TRIAGE"
    "todo" -> "À FAIRE"
    "ready" -> "PRÊT"
    KANBAN_STATUS_RUNNING -> "EN COURS"
    "blocked" -> "BLOQUÉ"
    "done" -> "TERMINÉ"
    "archived" -> "ARCHIVÉ"
    else -> name.uppercase()
}

/**
 * Couleur fixe par colonne — les colonnes serveur (BOARD_COLUMNS) n'ont ni
 * nom ni couleur personnalisable, voir docs/ARCHITECTURE.md#kanban.
 */
private fun columnColor(name: String): androidx.compose.ui.graphics.Color = when (name) {
    "triage" -> HasanColors.KanbanGray
    "todo" -> HasanColors.Accent
    "ready" -> HasanColors.KanbanBlue
    KANBAN_STATUS_RUNNING -> HasanColors.KanbanGold
    "blocked" -> HasanColors.KanbanRed
    "done" -> HasanColors.KanbanMuted
    else -> HasanColors.TextMutedA11y
}

/** Colonnes repliées par défaut à l'ouverture de l'écran (écho du mockup où "Terminé" est collapsed). */
private val DEFAULT_COLLAPSED_COLUMNS = setOf("done")

@androidx.compose.material3.ExperimentalMaterial3Api
@Composable
fun KanbanScreen(state: KanbanScreenUiState, callbacks: KanbanCallbacks) {
    Box(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            HasanMinimalHeader(callbacks.onMenuClick, title = "Kanban")
            KanbanHeader(
                boards = state.boards,
                selectedBoardSlug = state.selectedBoardSlug,
                onRefresh = callbacks.onRefresh,
                onSelectBoard = callbacks.onSelectBoard,
                onShowCreateBoard = callbacks.onShowCreateBoard
            )

            state.errorMessage?.let { message ->
                KanbanErrorBanner(message = message, onDismiss = callbacks.onDismissError)
            }

            Box(modifier = Modifier.weight(1f)) {
                when {
                    state.loading && state.board == null -> {
                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(color = HasanColors.Accent)
                        }
                    }
                    state.board == null || state.board.columns.all { it.tasks.isEmpty() } -> {
                        Box(
                            modifier = Modifier.fillMaxSize().padding(HasanDimens.SpacingXxl),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "Aucune tâche sur ce board",
                                color = HasanColors.TextMutedA11y,
                                textAlign = TextAlign.Center
                            )
                        }
                    }
                    else -> {
                        KanbanGroupedList(
                            columns = state.board.columns,
                            onTaskClick = callbacks.onTaskClick,
                            onMoveTask = callbacks.onMoveTask
                        )
                    }
                }
            }
        }

        FloatingActionButton(
            onClick = callbacks.onShowCreateTask,
            containerColor = HasanColors.Accent,
            contentColor = HasanColors.TextPrimary,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(HasanDimens.SpacingL)
        ) {
            // lineHeight = fontSize : le glyphe "+" laisse sinon un espace de ligne
            // asymétrique au-dessus/en-dessous (line-height typographique par défaut),
            // qui décentre visuellement le "+" dans le FAB — voir
            // archive/2026-07-23-audit-boutons-masque-punch-hole-pixel10.md.
            Text(
                text = "+",
                fontSize = HasanDimens.TextTitleMedium,
                lineHeight = HasanDimens.TextTitleMedium,
                fontFamily = ChakraPetch,
                fontWeight = FontWeight.SemiBold
            )
        }
    }

    if (state.showCreateBoardDialog) {
        CreateBoardDialog(onDismiss = callbacks.onDismissCreateBoard, onCreate = callbacks.onCreateBoard)
    }
    if (state.showCreateTaskDialog) {
        CreateTaskDialog(onDismiss = callbacks.onDismissCreateTask, onCreate = callbacks.onCreateTask)
    }
}

/**
 * Liste verticale unique groupée par colonne — remplace l'ancienne LazyRow de
 * colonnes horizontales (illisible dès que plusieurs colonnes ont peu de
 * contenu, voir archive/2026-07-23-audit-4-volets-...md finding #1). Pills de
 * navigation en haut + sections collapsibles, sur le modèle du mockup fourni
 * par l'utilisateur (hasan-kanban-mockup-v2-grouped.html).
 */
@androidx.compose.material3.ExperimentalMaterial3Api
@Composable
private fun KanbanGroupedList(
    columns: List<com.hasan.v1.webui.models.KanbanColumn>,
    onTaskClick: (KanbanTask) -> Unit,
    onMoveTask: (KanbanTask, String) -> Unit
) {
    val expandedState = remember {
        mutableStateMapOf<String, Boolean>().apply {
            columns.forEach { column -> put(column.name, column.name !in DEFAULT_COLLAPSED_COLUMNS) }
        }
    }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()

    // Index (dans la liste plate) du header de chaque colonne — nécessaire pour que
    // le clic sur une pill scrolle vers le bon item malgré le nombre variable de
    // tâches visibles par section (repliées ou non) au-dessus.
    val headerIndexByColumn = remember(columns, expandedState.toMap()) {
        val map = mutableMapOf<String, Int>()
        var index = 0
        columns.forEach { column ->
            map[column.name] = index
            index += 1
            if (expandedState[column.name] == true) index += column.tasks.size
        }
        map
    }

    Column(modifier = Modifier.fillMaxSize()) {
        KanbanPillRow(columns = columns, listState = listState, headerIndexByColumn = headerIndexByColumn, scope = scope)

        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize().padding(horizontal = HasanDimens.SpacingL),
            verticalArrangement = Arrangement.spacedBy(HasanDimens.SpacingS)
        ) {
            columns.forEach { column ->
                val isExpanded = expandedState[column.name] ?: true
                item(key = "header-${column.name}") {
                    KanbanSectionHeader(
                        name = column.name,
                        count = column.tasks.size,
                        expanded = isExpanded,
                        onToggle = { expandedState[column.name] = !isExpanded }
                    )
                }
                if (isExpanded) {
                    if (column.tasks.isEmpty()) {
                        item(key = "empty-${column.name}") {
                            Text(
                                text = "Aucune tâche",
                                color = HasanColors.TextMutedA11y,
                                fontSize = HasanDimens.TextBodyMedium,
                                modifier = Modifier.padding(bottom = HasanDimens.SpacingS)
                            )
                        }
                    } else {
                        items(column.tasks, key = { it.id }) { task ->
                            KanbanTaskCard(task = task, onClick = { onTaskClick(task) }, onMoveTask = onMoveTask)
                        }
                    }
                }
            }
            item { Spacer(modifier = Modifier.height(HasanDimens.SpacingXxxl)) }
        }
    }
}

@Composable
private fun KanbanPillRow(
    columns: List<com.hasan.v1.webui.models.KanbanColumn>,
    listState: LazyListState,
    headerIndexByColumn: Map<String, Int>,
    scope: kotlinx.coroutines.CoroutineScope
) {
    LazyRow(
        modifier = Modifier.fillMaxWidth().padding(horizontal = HasanDimens.SpacingL, vertical = HasanDimens.SpacingS),
        horizontalArrangement = Arrangement.spacedBy(HasanDimens.SpacingS)
    ) {
        items(columns, key = { it.name }) { column ->
            val targetIndex = headerIndexByColumn[column.name] ?: 0
            Row(
                modifier = Modifier
                    .clip(HasanShapes.panelSmall())
                    .background(HasanColors.BgSurface2)
                    .border(HasanDimens.BorderWidth, HasanColors.Border, HasanShapes.panelSmall())
                    .clickable { scope.launch { listState.animateScrollToItem(targetIndex) } }
                    .padding(horizontal = HasanDimens.SpacingS, vertical = HasanDimens.SpacingXs),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(modifier = Modifier.size(6.dp).clip(CircleShape).background(columnColor(column.name)))
                Text(
                    text = columnLabel(column.name),
                    color = HasanColors.TextSecondary,
                    fontFamily = IBMPlexMono,
                    fontSize = HasanDimens.TextLabelSmall,
                    modifier = Modifier.padding(start = HasanDimens.SpacingXs, end = HasanDimens.SpacingXxs)
                )
                Text(
                    text = column.tasks.size.toString(),
                    color = HasanColors.TextMutedA11y,
                    fontFamily = IBMPlexMono,
                    fontSize = HasanDimens.TextLabelSmall
                )
            }
        }
    }
}

@Composable
private fun KanbanSectionHeader(name: String, count: Int, expanded: Boolean, onToggle: () -> Unit) {
    val rotation by androidx.compose.animation.core.animateFloatAsState(
        targetValue = if (expanded) 90f else 0f,
        label = "kanban-section-chevron-rotation"
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onToggle)
            .padding(top = HasanDimens.SpacingS, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = "▶",
            color = HasanColors.TextMutedA11y,
            fontSize = HasanDimens.TextLabelSmall,
            modifier = Modifier.rotate(rotation).padding(end = 6.dp)
        )
        Box(modifier = Modifier.size(7.dp).clip(CircleShape).background(columnColor(name)))
        Text(
            text = columnLabel(name),
            color = HasanColors.TextMutedA11y,
            fontFamily = IBMPlexMono,
            fontSize = HasanDimens.TextLabelMedium,
            letterSpacing = 1.sp,
            modifier = Modifier.padding(start = HasanDimens.SpacingXs).weight(1f)
        )
        Text(
            text = count.toString(),
            color = HasanColors.TextMutedA11y,
            fontFamily = IBMPlexMono,
            fontSize = HasanDimens.TextLabelMedium
        )
    }
}

@Composable
private fun KanbanErrorBanner(message: String, onDismiss: () -> Unit) {
    CutCornerPanel(
        modifier = Modifier.fillMaxWidth().padding(horizontal = HasanDimens.SpacingL, vertical = HasanDimens.SpacingXs),
        backgroundColor = HasanColors.BgSurface,
        borderColor = HasanColors.Accent
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(HasanDimens.SpacingM),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = message,
                color = HasanColors.Accent,
                fontSize = HasanDimens.TextBodyMedium,
                modifier = Modifier.weight(1f)
            )
            androidx.compose.foundation.Image(
                painter = androidx.compose.ui.res.painterResource(com.hasan.v1.R.drawable.ic_close),
                contentDescription = "Fermer",
                colorFilter = androidx.compose.ui.graphics.ColorFilter.tint(HasanColors.TextMutedA11y),
                modifier = Modifier
                    .size(HasanDimens.IconSmall)
                    .padding(start = HasanDimens.SpacingS)
                    .clickable(onClick = onDismiss)
            )
        }
    }
}

@Composable
private fun KanbanHeader(
    boards: List<KanbanBoardSummary>,
    selectedBoardSlug: String?,
    onRefresh: () -> Unit,
    onSelectBoard: (String) -> Unit,
    onShowCreateBoard: () -> Unit
) {
    var boardMenuExpanded by remember { mutableStateOf(false) }
    val activeLabel = boards.firstOrNull { it.slug == selectedBoardSlug }?.name
        ?: selectedBoardSlug
        ?: "default"

    Row(
        modifier = Modifier.fillMaxWidth().padding(HasanDimens.SpacingL),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box {
            Row(
                modifier = Modifier.clickable { boardMenuExpanded = true },
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = activeLabel,
                        color = HasanColors.TextPrimary,
                        fontFamily = ChakraPetch,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = HasanDimens.TextTitleMedium
                    )
                    Text(
                        text = "board kanban",
                        color = HasanColors.TextMutedA11y,
                        fontFamily = IBMPlexMono,
                        fontSize = HasanDimens.TextCaption
                    )
                }
                Text(text = "▾", color = HasanColors.TextMutedA11y, fontSize = HasanDimens.TextHeading, modifier = Modifier.padding(start = HasanDimens.SpacingS))
            }
            DropdownMenu(expanded = boardMenuExpanded, onDismissRequest = { boardMenuExpanded = false }) {
                boards.forEach { board ->
                    DropdownMenuItem(
                        text = { Text(board.name ?: board.slug) },
                        onClick = { boardMenuExpanded = false; onSelectBoard(board.slug) }
                    )
                }
                DropdownMenuItem(
                    text = { Text("+ Nouveau board") },
                    onClick = { boardMenuExpanded = false; onShowCreateBoard() }
                )
            }
        }
        Box(
            modifier = Modifier
                .clickable(onClick = onRefresh)
                .padding(HasanDimens.SpacingS)
        ) {
            Text(text = "↻", color = HasanColors.Accent, fontSize = HasanDimens.TextHeading)
        }
    }
}

/** Âge relatif d'une tâche à partir de KanbanTask.ageSeconds (pré-calculé côté serveur). */
private fun formatAge(ageSeconds: Long?): String? {
    if (ageSeconds == null) return null
    return when {
        ageSeconds < 60 -> "à l'instant"
        ageSeconds < 3600 -> "il y a ${ageSeconds / 60}min"
        ageSeconds < 86400 -> "il y a ${ageSeconds / 3600}h"
        else -> "il y a ${ageSeconds / 86400}j"
    }
}

/**
 * Couleur de bande de priorité — aucune échelle documentée côté serveur
 * (priority: Int, défaut 0, aucun mapping label/couleur préexistant). Choix
 * app raisonnable : >=2 haute (accent), ==1 moyenne, <=0 aucune bande visible.
 */
private fun priorityColor(priority: Int): androidx.compose.ui.graphics.Color? = when {
    priority >= 2 -> HasanColors.Accent
    priority == 1 -> HasanColors.KanbanGold
    else -> null
}

@androidx.compose.material3.ExperimentalMaterial3Api
@Composable
private fun KanbanTaskCard(task: KanbanTask, onClick: () -> Unit, onMoveTask: (KanbanTask, String) -> Unit) {
    var moveSheetOpen by remember { mutableStateOf(false) }
    val barColor = priorityColor(task.priority)

    CutCornerPanel(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        shape = HasanShapes.panel()
    ) {
        Row(modifier = Modifier.fillMaxWidth()) {
            if (barColor != null) {
                Box(modifier = Modifier.fillMaxHeight().width(3.dp).background(barColor))
            }
            Column(modifier = Modifier.fillMaxWidth().padding(HasanDimens.SpacingM)) {
                Text(
                    text = task.title,
                    color = HasanColors.TextPrimary,
                    fontSize = HasanDimens.TextBodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 3
                )
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = HasanDimens.SpacingXs),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(horizontalArrangement = Arrangement.spacedBy(HasanDimens.SpacingXs)) {
                        task.tenant?.let { tenant ->
                            TagPill(text = tenant.uppercase(), backgroundColor = HasanColors.AccentDim, contentColor = HasanColors.Accent)
                        }
                        task.assignee?.let { assignee ->
                            TagPill(text = assignee, backgroundColor = HasanColors.BgSurface3, contentColor = HasanColors.TextSecondary)
                        }
                        if (task.priority > 0) {
                            TagPill(text = "P${task.priority}", backgroundColor = HasanColors.AccentDim, contentColor = HasanColors.Accent)
                        }
                    }
                    formatAge(task.ageSeconds)?.let { age ->
                        Text(text = age, color = HasanColors.TextMutedA11y, fontFamily = IBMPlexMono, fontSize = HasanDimens.TextLabelSmall)
                    }
                }
            }
            com.hasan.v1.ui.components.CutCornerIconButton(
                onClick = { moveSheetOpen = true },
                backgroundColor = HasanColors.BgSurface2,
                borderColor = HasanColors.Border,
                contentColor = HasanColors.TextPrimary,
                modifier = Modifier
                    .padding(top = HasanDimens.SpacingS, end = HasanDimens.SpacingS)
                    .size(HasanDimens.TouchTarget)
            ) {
                Text(text = "⋮", fontSize = HasanDimens.TextTitleMedium)
            }
        }
    }

    if (moveSheetOpen) {
        MoveTaskSheet(
            task = task,
            onDismiss = { moveSheetOpen = false },
            onMove = { targetStatus -> moveSheetOpen = false; onMoveTask(task, targetStatus) }
        )
    }
}

@androidx.compose.material3.ExperimentalMaterial3Api
@Composable
private fun MoveTaskSheet(task: KanbanTask, onDismiss: () -> Unit, onMove: (String) -> Unit) {
    val sheetState = rememberModalBottomSheetState()
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = HasanColors.BgSurface
    ) {
        Column(modifier = Modifier.padding(bottom = HasanDimens.SpacingXl)) {
            Text(
                text = "DÉPLACER VERS",
                color = HasanColors.TextMutedA11y,
                fontFamily = IBMPlexMono,
                fontSize = HasanDimens.TextLabelSmall,
                letterSpacing = 1.sp,
                modifier = Modifier.padding(horizontal = HasanDimens.SpacingL, vertical = HasanDimens.SpacingS)
            )
            KANBAN_COLUMNS
                .filter { it != task.status && it != KANBAN_STATUS_RUNNING }
                .forEach { targetStatus ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onMove(targetStatus) }
                            .padding(horizontal = HasanDimens.SpacingL, vertical = HasanDimens.SpacingM),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(modifier = Modifier.size(8.dp).clip(CircleShape).background(columnColor(targetStatus)))
                        Text(
                            text = columnLabel(targetStatus),
                            color = HasanColors.TextPrimary,
                            fontSize = HasanDimens.TextBody,
                            modifier = Modifier.padding(start = HasanDimens.SpacingM)
                        )
                    }
                }
        }
    }
}

class KanbanDetailCallbacks(val onBack: () -> Unit)

@Composable
fun KanbanTaskDetailScreen(task: KanbanTask?, loading: Boolean, callbacks: KanbanDetailCallbacks) {
    Column(modifier = Modifier.fillMaxSize().background(HasanColors.BgBase)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(HasanDimens.SpacingL),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "←",
                color = HasanColors.TextSecondary,
                fontSize = HasanDimens.TextTitleMedium,
                modifier = Modifier.clickable(onClick = callbacks.onBack).padding(end = HasanDimens.SpacingM)
            )
            Text(
                text = task?.title ?: "Tâche",
                color = HasanColors.TextPrimary,
                fontFamily = ChakraPetch,
                fontWeight = FontWeight.SemiBold,
                fontSize = HasanDimens.TextHeading,
                maxLines = 1
            )
        }

        when {
            loading -> {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = HasanColors.Accent)
                }
            }
            task == null -> {
                Box(modifier = Modifier.fillMaxSize().padding(HasanDimens.SpacingXxl), contentAlignment = Alignment.Center) {
                    Text(
                        text = "Tâche introuvable ou indisponible",
                        color = HasanColors.TextMutedA11y,
                        textAlign = TextAlign.Center
                    )
                }
            }
            else -> {
                Column(modifier = Modifier.fillMaxSize().padding(horizontal = HasanDimens.SpacingL)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(HasanDimens.SpacingXs)) {
                        TagPill(text = columnLabel(task.status))
                        task.assignee?.let { TagPill(text = it, backgroundColor = HasanColors.BgSurface3, contentColor = HasanColors.TextSecondary) }
                        if (task.priority > 0) TagPill(text = "P${task.priority}")
                    }
                    if (!task.body.isNullOrBlank()) {
                        MarkdownText(
                            text = task.body,
                            selectable = true,
                            modifier = Modifier.fillMaxSize().padding(top = HasanDimens.SpacingM)
                        )
                    } else {
                        Text(
                            text = "Pas de description",
                            color = HasanColors.TextMutedA11y,
                            fontSize = HasanDimens.TextBodyMedium,
                            modifier = Modifier.padding(top = HasanDimens.SpacingM)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun CreateBoardDialog(onDismiss: () -> Unit, onCreate: (slug: String, name: String?) -> Unit) {
    var slug by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("") }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.6f))
            .clickable(onClick = onDismiss),
        contentAlignment = Alignment.Center
    ) {
        CutCornerPanel(
            modifier = Modifier
                .widthIn(max = 340.dp)
                .padding(HasanDimens.SpacingL)
                .clickable(enabled = false) {}
        ) {
            Column(modifier = Modifier.padding(HasanDimens.SpacingL)) {
                Text(
                    text = "Nouveau board",
                    color = HasanColors.TextPrimary,
                    fontFamily = ChakraPetch,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = HasanDimens.TextTitleMedium
                )
                Spacer(modifier = Modifier.height(HasanDimens.SpacingM))
                OutlinedTextField(
                    value = slug,
                    onValueChange = { slug = it },
                    label = { Text("Identifiant (slug)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(HasanDimens.SpacingS))
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Nom (optionnel)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(HasanDimens.SpacingM))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    Text(
                        text = "Annuler",
                        color = HasanColors.TextMutedA11y,
                        fontFamily = IBMPlexMono,
                        modifier = Modifier.clickable(onClick = onDismiss).padding(HasanDimens.SpacingS)
                    )
                    Spacer(modifier = Modifier.width(HasanDimens.SpacingM))
                    Text(
                        text = "Créer",
                        color = if (slug.isNotBlank()) HasanColors.Accent else HasanColors.TextMutedA11y,
                        fontFamily = IBMPlexMono,
                        modifier = Modifier
                            .clickable(enabled = slug.isNotBlank()) { onCreate(slug.trim(), name.trim().takeIf { it.isNotBlank() }) }
                            .padding(HasanDimens.SpacingS)
                    )
                }
            }
        }
    }
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun CreateTaskDialog(onDismiss: () -> Unit, onCreate: (title: String, body: String?, status: String?) -> Unit) {
    var title by remember { mutableStateOf("") }
    var body by remember { mutableStateOf("") }
    var selectedStatus by remember { mutableStateOf<String?>(null) }
    val availableStatuses = remember { KANBAN_COLUMNS.filter { it != KANBAN_STATUS_RUNNING } }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.6f))
            .clickable(onClick = onDismiss),
        contentAlignment = Alignment.Center
    ) {
        CutCornerPanel(
            modifier = Modifier
                .widthIn(max = 340.dp)
                .padding(HasanDimens.SpacingL)
                .clickable(enabled = false) {}
        ) {
            Column(modifier = Modifier.padding(HasanDimens.SpacingL)) {
                Text(
                    text = "Nouvelle tâche",
                    color = HasanColors.TextPrimary,
                    fontFamily = ChakraPetch,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = HasanDimens.TextTitleMedium
                )
                Spacer(modifier = Modifier.height(HasanDimens.SpacingM))
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text("Titre") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(HasanDimens.SpacingS))
                OutlinedTextField(
                    value = body,
                    onValueChange = { body = it },
                    label = { Text("Description (optionnel)") },
                    minLines = 3,
                    maxLines = 6,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(HasanDimens.SpacingM))
                Text(
                    text = "CATÉGORIE",
                    color = HasanColors.TextMutedA11y,
                    fontFamily = IBMPlexMono,
                    fontSize = HasanDimens.TextLabelSmall,
                    letterSpacing = 1.sp
                )
                Spacer(modifier = Modifier.height(HasanDimens.SpacingXs))
                androidx.compose.foundation.layout.FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(HasanDimens.SpacingXs),
                    verticalArrangement = Arrangement.spacedBy(HasanDimens.SpacingXs)
                ) {
                    availableStatuses.forEach { status ->
                        val isSelected = selectedStatus == status
                        Row(
                            modifier = Modifier
                                .clip(HasanShapes.panelSmall())
                                .background(if (isSelected) HasanColors.AccentDim else HasanColors.BgSurface2)
                                .border(
                                    HasanDimens.BorderWidth,
                                    if (isSelected) HasanColors.Accent else HasanColors.Border,
                                    HasanShapes.panelSmall()
                                )
                                .clickable { selectedStatus = if (isSelected) null else status }
                                .padding(horizontal = HasanDimens.SpacingS, vertical = HasanDimens.SpacingXs),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(modifier = Modifier.size(6.dp).clip(CircleShape).background(columnColor(status)))
                            Text(
                                text = columnLabel(status),
                                color = if (isSelected) HasanColors.Accent else HasanColors.TextSecondary,
                                fontFamily = IBMPlexMono,
                                fontSize = HasanDimens.TextLabelSmall,
                                modifier = Modifier.padding(start = HasanDimens.SpacingXs)
                            )
                        }
                    }
                }
                Spacer(modifier = Modifier.height(HasanDimens.SpacingM))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    Text(
                        text = "Annuler",
                        color = HasanColors.TextMutedA11y,
                        fontFamily = IBMPlexMono,
                        modifier = Modifier.clickable(onClick = onDismiss).padding(HasanDimens.SpacingS)
                    )
                    Spacer(modifier = Modifier.width(HasanDimens.SpacingM))
                    Text(
                        text = "Créer",
                        color = if (title.isNotBlank()) HasanColors.Accent else HasanColors.TextMutedA11y,
                        fontFamily = IBMPlexMono,
                        modifier = Modifier
                            .clickable(enabled = title.isNotBlank()) {
                                onCreate(title.trim(), body.trim().takeIf { it.isNotBlank() }, selectedStatus)
                            }
                            .padding(HasanDimens.SpacingS)
                    )
                }
            }
        }
    }
}
