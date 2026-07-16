package com.hasan.v1.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hasan.v1.webui.models.CronJob
import com.hasan.v1.ui.components.AccentIconButton
import com.hasan.v1.ui.components.CutCornerPanel
import com.hasan.v1.ui.components.HasanMinimalHeader
import com.hasan.v1.ui.components.HasanToggle
import com.hasan.v1.ui.components.TagPill
import com.hasan.v1.ui.theme.ChakraPetch
import com.hasan.v1.ui.theme.HasanColors
import com.hasan.v1.ui.theme.HasanShapes
import com.hasan.v1.ui.theme.IBMPlexMono

/** État affiché par l'écran Tasks — reflète TasksViewModel.uiState. */
data class TasksScreenUiState(
    val jobs: List<CronJob>,
    val loading: Boolean,
    val errorMessage: String?,
    val runningJobIds: Set<String>
)

/** Callbacks délégués au Fragment — aucune logique métier dans les composables. */
class TasksCallbacks(
    val onMenuClick: () -> Unit,
    val onRefresh: () -> Unit,
    val onNewTask: () -> Unit,
    val onEditTask: (CronJob) -> Unit,
    val onToggleEnabled: (CronJob) -> Unit,
    val onRunNow: (CronJob) -> Unit,
    val onShowHistory: (CronJob) -> Unit,
    val onDeleteTask: (CronJob) -> Unit,
    val onDismissError: () -> Unit
)

@Composable
fun TasksScreen(state: TasksScreenUiState, callbacks: TasksCallbacks) {
    Column(modifier = Modifier.fillMaxSize()) {
        HasanMinimalHeader(callbacks.onMenuClick)
        TasksHeader(jobCount = state.jobs.size, onRefresh = callbacks.onRefresh, onNewTask = callbacks.onNewTask)

        state.errorMessage?.let { message ->
            TasksErrorBanner(message = message, onDismiss = callbacks.onDismissError)
        }

        when {
            state.loading && state.jobs.isEmpty() -> {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = HasanColors.Accent)
                }
            }
            state.jobs.isEmpty() -> {
                Box(
                    modifier = Modifier.fillMaxSize().padding(24.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "Aucune tâche programmée",
                        color = HasanColors.TextMutedA11y,
                        textAlign = TextAlign.Center
                    )
                }
            }
            else -> {
                LazyColumn(
                    modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(state.jobs, key = { it.id }) { job ->
                        TaskCard(
                            job = job,
                            running = job.id in state.runningJobIds,
                            callbacks = callbacks
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun TasksHeader(jobCount: Int, onRefresh: () -> Unit, onNewTask: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(16.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column {
            Text(
                text = jobCount.toString(),
                color = HasanColors.TextPrimary,
                fontFamily = ChakraPetch,
                fontWeight = FontWeight.SemiBold,
                fontSize = 32.sp
            )
            Text(
                text = "tâches programmées",
                color = HasanColors.TextMutedA11y,
                fontFamily = IBMPlexMono,
                fontSize = 11.sp
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AccentIconButton(onClick = onRefresh, modifier = Modifier.size(36.dp)) {
                Text(text = "↻", color = HasanColors.Accent, fontSize = 18.sp)
            }
            AccentIconButton(onClick = onNewTask, modifier = Modifier.size(36.dp)) {
                Text(text = "+", color = HasanColors.Accent, fontSize = 20.sp)
            }
        }
    }
}

@Composable
private fun TasksErrorBanner(message: String, onDismiss: () -> Unit) {
    CutCornerPanel(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        backgroundColor = HasanColors.BgSurface,
        borderColor = HasanColors.Accent
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = message,
                color = HasanColors.Accent,
                fontSize = 12.sp,
                modifier = Modifier.weight(1f)
            )
            Text(
                text = "✕",
                color = HasanColors.TextMutedA11y,
                modifier = Modifier.padding(start = 8.dp).clickableTextPadding(onDismiss)
            )
        }
    }
}

@Composable
private fun TaskCard(job: CronJob, running: Boolean, callbacks: TasksCallbacks) {
    CutCornerPanel(
        modifier = Modifier.fillMaxWidth(),
        shape = HasanShapes.panel()
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = job.name,
                    color = HasanColors.TextPrimary,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f)
                )
                TaskStatusPill(job = job, running = running)
            }

            Text(
                text = job.scheduleDisplay,
                color = HasanColors.TextMutedA11y,
                fontFamily = IBMPlexMono,
                fontSize = 11.sp,
                modifier = Modifier.padding(top = 4.dp)
            )

            job.lastError?.let { error ->
                Text(
                    text = error,
                    color = HasanColors.Accent,
                    fontSize = 11.sp,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                HasanToggle(checked = job.enabled, onCheckedChange = { callbacks.onToggleEnabled(job) })
                Text(
                    text = if (job.enabled) "Actif" else "En pause",
                    color = HasanColors.TextSecondary,
                    fontSize = 11.sp,
                    maxLines = 1,
                    modifier = Modifier.weight(1f)
                )
                AccentIconButton(
                    onClick = { callbacks.onRunNow(job) },
                    modifier = Modifier.size(28.dp)
                ) {
                    if (running) {
                        CircularProgressIndicator(color = HasanColors.Accent, strokeWidth = 2.dp, modifier = Modifier.size(14.dp))
                    } else {
                        Text(text = "▶", color = HasanColors.Accent, fontSize = 12.sp)
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Text(
                    text = "Historique",
                    color = HasanColors.TextMutedA11y,
                    fontFamily = IBMPlexMono,
                    fontSize = 10.sp,
                    modifier = Modifier.clickableTextPadding { callbacks.onShowHistory(job) }
                )
                Text(
                    text = "Modifier",
                    color = HasanColors.TextMutedA11y,
                    fontFamily = IBMPlexMono,
                    fontSize = 10.sp,
                    modifier = Modifier.clickableTextPadding { callbacks.onEditTask(job) }
                )
                Text(
                    text = "Supprimer",
                    color = HasanColors.Accent,
                    fontFamily = IBMPlexMono,
                    fontSize = 10.sp,
                    modifier = Modifier.clickableTextPadding { callbacks.onDeleteTask(job) }
                )
            }
        }
    }
}

@Composable
private fun TaskStatusPill(job: CronJob, running: Boolean) {
    val (label, bg, fg) = when {
        running -> Triple("EN COURS", HasanColors.AccentDim, HasanColors.Accent)
        !job.enabled -> Triple("PAUSE", HasanColors.BgSurface3, HasanColors.TextMutedA11y)
        job.lastStatus == "error" -> Triple("ERREUR", HasanColors.AccentDim, HasanColors.Accent)
        else -> Triple("ACTIF", HasanColors.BgSurface3, HasanColors.TextSecondary)
    }
    TagPill(text = label, backgroundColor = bg, contentColor = fg)
}

private fun Modifier.clickableTextPadding(onClick: () -> Unit): Modifier =
    this.padding(4.dp).clickable(onClick = onClick)
