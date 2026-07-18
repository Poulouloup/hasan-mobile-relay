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
import com.hasan.v1.MemoryFile
import com.hasan.v1.MemoryTab
import com.hasan.v1.ui.components.CutCornerPanel
import com.hasan.v1.ui.components.HasanMinimalHeader
import com.hasan.v1.ui.components.MarkdownText
import com.hasan.v1.ui.theme.ChakraPetch
import com.hasan.v1.ui.theme.HasanColors
import com.hasan.v1.ui.theme.HasanShapes
import com.hasan.v1.ui.theme.IBMPlexMono
import com.hasan.v1.webui.models.DailyInsight
import com.hasan.v1.webui.models.DayActivity
import com.hasan.v1.webui.models.HermesMemory
import com.hasan.v1.webui.models.HourActivity
import com.hasan.v1.webui.models.InsightsSummary
import com.hasan.v1.webui.models.ModelInsight
import java.util.Locale

/** État affiché par l'écran Memory & Insights — reflète MemoryViewModel.uiState. Lecture seule. */
data class MemoryScreenUiState(
    val selectedTab: MemoryTab,
    val selectedFile: MemoryFile?,
    val memory: HermesMemory?,
    val insights: InsightsSummary?,
    val loading: Boolean,
    val errorMessage: String?
)

class MemoryCallbacks(
    val onMenuClick: () -> Unit,
    val onSelectTab: (MemoryTab) -> Unit,
    val onOpenFile: (MemoryFile) -> Unit,
    val onCloseFile: () -> Unit,
    val onRefresh: () -> Unit
)

@Composable
fun MemoryScreen(state: MemoryScreenUiState, callbacks: MemoryCallbacks) {
    if (state.selectedFile != null) {
        MemoryFileDetailScreen(
            file = state.selectedFile,
            content = contentFor(state.memory, state.selectedFile),
            onClose = callbacks.onCloseFile
        )
        return
    }

    Column(modifier = Modifier.fillMaxSize()) {
        HasanMinimalHeader(callbacks.onMenuClick)
        MemoryTabSwitcher(selectedTab = state.selectedTab, onSelectTab = callbacks.onSelectTab)

        when {
            state.loading && state.memory == null && state.insights == null -> {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = HasanColors.Accent)
                }
            }
            state.selectedTab == MemoryTab.MEMORY -> MemoryTabContent(state.memory, callbacks.onOpenFile)
            else -> InsightsTabContent(state.insights)
        }
    }
}

private fun contentFor(memory: HermesMemory?, file: MemoryFile): String? = when (file) {
    MemoryFile.MEMORY -> memory?.memory
    MemoryFile.USER -> memory?.user
    MemoryFile.SOUL -> memory?.soul
}

private fun titleFor(file: MemoryFile): String = when (file) {
    MemoryFile.MEMORY -> "MEMORY.md"
    MemoryFile.USER -> "USER.md"
    MemoryFile.SOUL -> "SOUL.md"
}

@Composable
private fun MemoryFileDetailScreen(file: MemoryFile, content: String?, onClose: () -> Unit) {
    Column(modifier = Modifier.fillMaxSize().background(HasanColors.BgBase)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = titleFor(file),
                color = HasanColors.TextPrimary,
                fontFamily = ChakraPetch,
                fontWeight = FontWeight.SemiBold,
                fontSize = 18.sp
            )
            Text(
                text = "✕",
                color = HasanColors.TextSecondary,
                fontSize = 20.sp,
                modifier = Modifier.clickable(onClick = onClose).padding(8.dp)
            )
        }

        if (content.isNullOrBlank()) {
            Box(modifier = Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                Text(text = "Aucun contenu", color = HasanColors.TextMutedA11y, textAlign = TextAlign.Center)
            }
        } else {
            Box(modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
                MarkdownText(text = content, selectable = true, modifier = Modifier.fillMaxSize())
            }
        }
    }
}

@Composable
private fun MemoryTabSwitcher(selectedTab: MemoryTab, onSelectTab: (MemoryTab) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        MemoryTabPill(
            label = "MEMORY",
            selected = selectedTab == MemoryTab.MEMORY,
            onClick = { onSelectTab(MemoryTab.MEMORY) },
            modifier = Modifier.weight(1f)
        )
        MemoryTabPill(
            label = "INSIGHTS",
            selected = selectedTab == MemoryTab.INSIGHTS,
            onClick = { onSelectTab(MemoryTab.INSIGHTS) },
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
private fun MemoryTabPill(label: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    CutCornerPanel(
        modifier = modifier.clickable(onClick = onClick),
        shape = HasanShapes.panelSmall(),
        backgroundColor = if (selected) HasanColors.AccentDim else HasanColors.BgSurface,
        borderColor = if (selected) HasanColors.Accent else HasanColors.Border
    ) {
        Text(
            text = label,
            color = if (selected) HasanColors.Accent else HasanColors.TextMutedA11y,
            fontFamily = IBMPlexMono,
            fontSize = 11.sp,
            letterSpacing = 1.sp,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp)
        )
    }
}

@Composable
private fun MemorySectionTitle(text: String) {
    Text(
        text = text,
        color = HasanColors.TextMutedA11y,
        fontFamily = IBMPlexMono,
        fontSize = 10.sp,
        letterSpacing = 1.sp,
        modifier = Modifier.padding(bottom = 8.dp)
    )
}

@Composable
private fun MemoryTabContent(memory: HermesMemory?, onOpenFile: (MemoryFile) -> Unit) {
    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item { MemoryFileRow(title = "MEMORY.md", content = memory?.memory, onClick = { onOpenFile(MemoryFile.MEMORY) }) }
        item { MemoryFileRow(title = "USER.md", content = memory?.user, onClick = { onOpenFile(MemoryFile.USER) }) }
        item { MemoryFileRow(title = "SOUL.md", content = memory?.soul, onClick = { onOpenFile(MemoryFile.SOUL) }) }
    }
}

@Composable
private fun MemoryFileRow(title: String, content: String?, onClick: () -> Unit) {
    CutCornerPanel(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        shape = HasanShapes.panel()
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(14.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    color = HasanColors.TextPrimary,
                    fontFamily = ChakraPetch,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 14.sp
                )
                Text(
                    text = if (content.isNullOrBlank()) "Aucun contenu" else "Toucher pour afficher",
                    color = HasanColors.TextMutedA11y,
                    fontFamily = IBMPlexMono,
                    fontSize = 11.sp,
                    modifier = Modifier.padding(top = 2.dp)
                )
            }
            Text(text = "→", color = HasanColors.TextMutedA11y, fontSize = 16.sp)
        }
    }
}

@Composable
private fun InsightsTabContent(insights: InsightsSummary?) {
    if (insights == null) {
        Box(modifier = Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
            Text(
                text = "Statistiques indisponibles",
                color = HasanColors.TextMutedA11y,
                textAlign = TextAlign.Center
            )
        }
        return
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item { InsightsSummaryPanel(insights) }
        if (insights.models.isNotEmpty()) {
            item { MemorySectionTitle("PAR MODÈLE") }
            items(insights.models, key = { it.model }) { model -> ModelInsightRow(model) }
        }
        if (insights.dailyTokens.isNotEmpty()) {
            item { MemorySectionTitle("TOKENS PAR JOUR") }
            items(insights.dailyTokens, key = { it.date }) { daily -> DailyInsightRow(daily) }
        }
        if (insights.activityByDay.isNotEmpty()) {
            item { MemorySectionTitle("ACTIVITÉ PAR JOUR DE SEMAINE") }
            item { DayActivityPanel(insights.activityByDay) }
        }
        if (insights.activityByHour.isNotEmpty()) {
            item { MemorySectionTitle("ACTIVITÉ PAR HEURE") }
            item { HourActivityPanel(insights.activityByHour) }
        }
    }
}

@Composable
private fun InsightsSummaryPanel(insights: InsightsSummary) {
    CutCornerPanel(modifier = Modifier.fillMaxWidth(), shape = HasanShapes.panel()) {
        Column(modifier = Modifier.fillMaxWidth().padding(14.dp)) {
            MemorySectionTitle("${insights.periodDays} DERNIERS JOURS")
            InsightStatRow("Sessions", insights.totalSessions.toString())
            InsightStatRow("Messages", insights.totalMessages.toString())
            InsightStatRow("Tokens entrée", insights.totalInputTokens.toString())
            InsightStatRow("Tokens sortie", insights.totalOutputTokens.toString())
            InsightStatRow("Tokens total", insights.totalTokens.toString())
            insights.totalCacheHitPercent?.let { InsightStatRow("Cache hit", "$it%") }
            InsightStatRow("Coût", formatCost(insights.totalCost))
        }
    }
}

@Composable
private fun InsightStatRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(text = label, color = HasanColors.TextMutedA11y, fontFamily = IBMPlexMono, fontSize = 12.sp)
        Text(text = value, color = HasanColors.TextPrimary, fontFamily = IBMPlexMono, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun ModelInsightRow(model: ModelInsight) {
    CutCornerPanel(modifier = Modifier.fillMaxWidth(), shape = HasanShapes.panel()) {
        Column(modifier = Modifier.fillMaxWidth().padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = model.model,
                    color = HasanColors.TextPrimary,
                    fontFamily = ChakraPetch,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f)
                )
                Text(text = "${model.costShare}%", color = HasanColors.Accent, fontFamily = IBMPlexMono, fontSize = 11.sp)
            }
            Text(
                text = "${model.sessions} sessions · ${model.totalTokens} tokens · ${formatCost(model.cost)}",
                color = HasanColors.TextMutedA11y,
                fontFamily = IBMPlexMono,
                fontSize = 11.sp,
                modifier = Modifier.padding(top = 2.dp)
            )
        }
    }
}

@Composable
private fun DailyInsightRow(daily: DailyInsight) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(text = daily.date, color = HasanColors.TextSecondary, fontFamily = IBMPlexMono, fontSize = 11.sp)
        Text(
            text = "${daily.sessions} sess. · ${daily.inputTokens + daily.outputTokens} tok · ${formatCost(daily.cost)}",
            color = HasanColors.TextMutedA11y,
            fontFamily = IBMPlexMono,
            fontSize = 11.sp
        )
    }
}

@Composable
private fun DayActivityPanel(days: List<DayActivity>) {
    CutCornerPanel(modifier = Modifier.fillMaxWidth(), shape = HasanShapes.panel()) {
        Column(modifier = Modifier.fillMaxWidth().padding(12.dp)) {
            days.forEach { day -> InsightStatRow(day.day, "${day.sessions} sessions") }
        }
    }
}

@Composable
private fun HourActivityPanel(hours: List<HourActivity>) {
    CutCornerPanel(modifier = Modifier.fillMaxWidth(), shape = HasanShapes.panel()) {
        Column(modifier = Modifier.fillMaxWidth().padding(12.dp)) {
            hours.forEach { hour ->
                InsightStatRow(String.format(Locale.getDefault(), "%02dh", hour.hour), "${hour.sessions} sessions")
            }
        }
    }
}

private fun formatCost(cost: Double): String = String.format(Locale.getDefault(), "$%.2f", cost)
