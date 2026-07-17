package com.hasan.v1.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hasan.v1.webui.models.SkillDetail
import com.hasan.v1.webui.models.SkillSummary
import com.hasan.v1.webui.models.SkillUsage
import com.hasan.v1.ui.components.CutCornerPanel
import com.hasan.v1.ui.components.HasanMinimalHeader
import com.hasan.v1.ui.components.MarkdownText
import com.hasan.v1.ui.components.TagPill
import com.hasan.v1.ui.theme.ChakraPetch
import com.hasan.v1.ui.theme.HasanColors
import com.hasan.v1.ui.theme.HasanShapes
import com.hasan.v1.ui.theme.IBMPlexMono

/** Nom de la catégorie de repli pour les skills sans catégorie (category == null côté serveur). */
private const val UNCATEGORIZED_LABEL = "Autres"

/** État affiché par l'écran Skills — reflète SkillsViewModel.uiState. Lecture seule. */
data class SkillsScreenUiState(
    val skills: List<SkillSummary>,
    val usage: Map<String, SkillUsage>,
    val loading: Boolean,
    val errorMessage: String?
)

class SkillsCallbacks(
    val onMenuClick: () -> Unit,
    val onRefresh: () -> Unit,
    val onSkillClick: (SkillSummary) -> Unit
)

@Composable
fun SkillsScreen(state: SkillsScreenUiState, callbacks: SkillsCallbacks) {
    Column(modifier = Modifier.fillMaxSize()) {
        HasanMinimalHeader(callbacks.onMenuClick)
        SkillsHeader(count = state.skills.size, onRefresh = callbacks.onRefresh)

        when {
            state.loading && state.skills.isEmpty() -> {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = HasanColors.Accent)
                }
            }
            state.skills.isEmpty() -> {
                Box(
                    modifier = Modifier.fillMaxSize().padding(24.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "Aucune skill installée",
                        color = HasanColors.TextMutedA11y,
                        textAlign = TextAlign.Center
                    )
                }
            }
            else -> {
                // Groupé par catégorie — le serveur trie déjà (catégorie, nom),
                // "Autres" pour les skills non catégorisées (category == null).
                // Sections dépliantes, TOUTES repliées par défaut (y compris les
                // petites catégories réelles) — la liste à plat de 838 skills
                // était jugée trop dense pour rester lisible même en ne repliant
                // que le gros bloc "Autres" (761/838 skills), donc repli total
                // au premier chargement, chaque section restant un tap.
                val grouped = remember(state.skills) {
                    state.skills.groupBy { it.category ?: UNCATEGORIZED_LABEL }
                }
                val expandedState = remember {
                    mutableStateMapOf<String, Boolean>().apply {
                        grouped.keys.forEach { category -> put(category, false) }
                    }
                }

                LazyColumn(
                    modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    grouped.forEach { (category, skillsInCategory) ->
                        val isExpanded = expandedState[category] ?: false
                        item(key = "header-$category") {
                            CategoryHeader(
                                category = category,
                                count = skillsInCategory.size,
                                expanded = isExpanded,
                                onToggle = { expandedState[category] = !isExpanded }
                            )
                        }
                        if (isExpanded) {
                            items(skillsInCategory, key = { it.name }) { skill ->
                                SkillCard(skill = skill, usage = state.usage[skill.name], onClick = { callbacks.onSkillClick(skill) })
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SkillsHeader(count: Int, onRefresh: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(16.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column {
            Text(
                text = count.toString(),
                color = HasanColors.TextPrimary,
                fontFamily = ChakraPetch,
                fontWeight = FontWeight.SemiBold,
                fontSize = 32.sp
            )
            Text(
                text = "skills installées",
                color = HasanColors.TextMutedA11y,
                fontFamily = IBMPlexMono,
                fontSize = 11.sp
            )
        }
        Box(
            modifier = Modifier
                .clickable(onClick = onRefresh)
                .padding(8.dp)
        ) {
            Text(text = "↻", color = HasanColors.Accent, fontSize = 18.sp)
        }
    }
}

@Composable
private fun CategoryHeader(category: String, count: Int, expanded: Boolean, onToggle: () -> Unit) {
    val rotation by androidx.compose.animation.core.animateFloatAsState(
        targetValue = if (expanded) 90f else 0f,
        label = "category-chevron-rotation"
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onToggle)
            .padding(top = 8.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = "▶",
            color = HasanColors.TextMutedA11y,
            fontSize = 9.sp,
            modifier = Modifier.rotate(rotation).padding(end = 6.dp)
        )
        Text(
            text = category.uppercase(),
            color = HasanColors.TextMutedA11y,
            fontFamily = IBMPlexMono,
            fontSize = 10.sp,
            letterSpacing = 1.sp,
            modifier = Modifier.weight(1f)
        )
        Text(
            text = count.toString(),
            color = HasanColors.TextMutedA11y,
            fontFamily = IBMPlexMono,
            fontSize = 10.sp
        )
    }
}

@Composable
private fun SkillCard(skill: SkillSummary, usage: SkillUsage?, onClick: () -> Unit) {
    CutCornerPanel(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = HasanShapes.panel()
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = skill.name,
                    color = HasanColors.TextPrimary,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f)
                )
                if (skill.disabled) {
                    TagPill(text = "DÉSACTIVÉE", backgroundColor = HasanColors.BgSurface3, contentColor = HasanColors.TextMutedA11y)
                } else if (usage != null && usage.useCount > 0) {
                    TagPill(text = "${usage.useCount}×", backgroundColor = HasanColors.AccentDim, contentColor = HasanColors.Accent)
                }
            }
            if (skill.description.isNotBlank()) {
                Text(
                    text = skill.description,
                    color = HasanColors.TextMutedA11y,
                    fontSize = 12.sp,
                    maxLines = 2,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
        }
    }
}

class SkillDetailCallbacks(val onBack: () -> Unit)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SkillDetailScreen(
    skillName: String,
    detail: SkillDetail?,
    loading: Boolean,
    callbacks: SkillDetailCallbacks
) {
    Column(modifier = Modifier.fillMaxSize().background(HasanColors.BgBase)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "←",
                color = HasanColors.TextSecondary,
                fontSize = 20.sp,
                modifier = Modifier.clickable(onClick = callbacks.onBack).padding(end = 12.dp)
            )
            Text(
                text = skillName,
                color = HasanColors.TextPrimary,
                fontFamily = ChakraPetch,
                fontWeight = FontWeight.SemiBold,
                fontSize = 18.sp
            )
        }

        when {
            loading -> {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = HasanColors.Accent)
                }
            }
            detail == null -> {
                Box(modifier = Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                    Text(
                        text = "Skill introuvable ou indisponible sur cette plateforme",
                        color = HasanColors.TextMutedA11y,
                        textAlign = TextAlign.Center
                    )
                }
            }
            else -> {
                Column(modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
                    if (detail.tags.isNotEmpty()) {
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                            modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp)
                        ) {
                            detail.tags.forEach { tag -> TagPill(text = tag) }
                        }
                    }
                    MarkdownText(
                        text = stripFrontmatter(detail.content),
                        selectable = true,
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }
        }
    }
}

/**
 * GET /api/skills/content renvoie le SKILL.md complet, frontmatter YAML
 * inclus (voir api/routes.py `_skill_view_from_file` — "content": full raw
 * SKILL.md text). name/description/tags sont déjà extraits séparément par
 * le serveur (champs dédiés de la réponse) donc le bloc frontmatter en tête
 * de [content] est redondant pour l'affichage — Markwon le rendrait sinon
 * comme du texte brut continu (bug observé en conditions réelles).
 */
private fun stripFrontmatter(content: String): String {
    val trimmed = content.trimStart()
    if (!trimmed.startsWith("---")) return content
    val closingIndex = trimmed.indexOf("\n---", startIndex = 3)
    if (closingIndex == -1) return content
    val afterClosing = trimmed.indexOf('\n', closingIndex + 1)
    return if (afterClosing == -1) "" else trimmed.substring(afterClosing + 1).trimStart('\n')
}
