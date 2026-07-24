package com.hasan.v1.ui.screens

import androidx.compose.foundation.Image
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
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.hasan.v1.R
import com.hasan.v1.ui.components.CutCornerIconButton
import com.hasan.v1.ui.components.CutCornerPanel
import com.hasan.v1.ui.components.HasanIconButton
import com.hasan.v1.ui.theme.HasanColors
import com.hasan.v1.ui.theme.HasanDimens
import com.hasan.v1.webui.models.WorkspaceEntry

data class FilesScreenUiState(
    val entries: List<WorkspaceEntry>,
    val currentPath: String,
    val loading: Boolean,
    val errorMessage: String?
)

class FilesCallbacks(
    val onBack: () -> Unit,
    val onRefresh: () -> Unit,
    val onEntryClick: (WorkspaceEntry) -> Unit,
    val onDownload: (WorkspaceEntry) -> Unit,
    val onNavigateUp: () -> Unit,
    val onDismissError: () -> Unit
)

@Composable
fun FilesScreen(state: FilesScreenUiState, callbacks: FilesCallbacks) {
    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = HasanDimens.SpacingS, vertical = HasanDimens.SpacingXs),
            verticalAlignment = Alignment.CenterVertically
        ) {
            HasanIconButton(
                iconRes = R.drawable.ic_close,
                contentDescription = "Retour",
                onClick = callbacks.onBack
            )
            Text(
                text = "Fichiers",
                color = HasanColors.TextPrimary,
                fontFamily = com.hasan.v1.ui.theme.ChakraPetch,
                fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
                fontSize = HasanDimens.TextTitleMedium,
                modifier = Modifier.padding(start = HasanDimens.SpacingS)
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = HasanDimens.SpacingL, vertical = HasanDimens.SpacingS),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = if (state.currentPath == ".") "Workspace" else state.currentPath,
                color = HasanColors.TextSecondary,
                fontSize = HasanDimens.TextBodyMedium,
                modifier = Modifier.weight(1f)
            )
            if (state.currentPath != ".") {
                CutCornerIconButton(onClick = callbacks.onNavigateUp) {
                    Image(
                        painter = painterResource(R.drawable.ic_arrow_up),
                        contentDescription = "Remonter",
                        colorFilter = ColorFilter.tint(HasanColors.TextSecondary),
                        modifier = Modifier.size(HasanDimens.IconSmall)
                    )
                }
            }
        }

        state.errorMessage?.let { message ->
            FilesErrorBanner(message = message, onDismiss = callbacks.onDismissError)
        }

        when {
            state.loading && state.entries.isEmpty() -> {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = HasanColors.Accent)
                }
            }
            state.entries.isEmpty() -> {
                Box(
                    modifier = Modifier.fillMaxSize().padding(HasanDimens.SpacingXxl),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "Workspace vide",
                        color = HasanColors.TextMutedA11y,
                        textAlign = TextAlign.Center
                    )
                }
            }
            else -> {
                LazyColumn(
                    modifier = Modifier.fillMaxSize().padding(horizontal = HasanDimens.SpacingL),
                    verticalArrangement = Arrangement.spacedBy(HasanDimens.SpacingS)
                ) {
                    items(state.entries, key = { it.path }) { entry ->
                        FileEntryRow(entry, onEntryClick = { callbacks.onEntryClick(entry) }, onDownload = { callbacks.onDownload(entry) })
                    }
                }
            }
        }
    }
}

@Composable
private fun FileEntryRow(entry: WorkspaceEntry, onEntryClick: () -> Unit, onDownload: () -> Unit) {
    CutCornerPanel(
        modifier = Modifier.fillMaxWidth().clickable(enabled = entry.isDir, onClick = onEntryClick)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(HasanDimens.SpacingM),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                Image(
                    painter = painterResource(if (entry.isDir) R.drawable.ic_folder else R.drawable.ic_file),
                    contentDescription = null,
                    colorFilter = ColorFilter.tint(if (entry.isDir) HasanColors.Accent else HasanColors.TextSecondary),
                    modifier = Modifier.size(HasanDimens.IconSmall)
                )
                Column(modifier = Modifier.padding(start = HasanDimens.SpacingM)) {
                    Text(text = entry.name, color = HasanColors.TextPrimary, fontSize = HasanDimens.TextBodyMedium)
                    if (!entry.isDir && entry.size != null) {
                        Text(text = formatFileSize(entry.size), color = HasanColors.TextMutedA11y, fontSize = HasanDimens.TextCaption)
                    }
                }
            }
            if (!entry.isDir) {
                CutCornerIconButton(onClick = onDownload) {
                    Image(
                        painter = painterResource(R.drawable.ic_download),
                        contentDescription = "Télécharger",
                        colorFilter = ColorFilter.tint(HasanColors.Accent),
                        modifier = Modifier.size(HasanDimens.IconSmall)
                    )
                }
            }
        }
    }
}

private fun formatFileSize(bytes: Long): String = when {
    bytes < 1024 -> "$bytes o"
    bytes < 1024 * 1024 -> "${bytes / 1024} Ko"
    else -> "${bytes / (1024 * 1024)} Mo"
}

@Composable
private fun FilesErrorBanner(message: String, onDismiss: () -> Unit) {
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
            Image(
                painter = painterResource(R.drawable.ic_close),
                contentDescription = "Fermer",
                colorFilter = ColorFilter.tint(HasanColors.TextMutedA11y),
                modifier = Modifier
                    .size(HasanDimens.IconSmall)
                    .padding(start = HasanDimens.SpacingS)
                    .clickable(onClick = onDismiss)
            )
        }
    }
}
