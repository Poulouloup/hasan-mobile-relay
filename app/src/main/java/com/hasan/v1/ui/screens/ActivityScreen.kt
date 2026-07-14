package com.hasan.v1.ui.screens

import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hasan.v1.network.ActivityEvent
import com.hasan.v1.ui.components.CutCornerPanel
import com.hasan.v1.ui.components.TagPill
import com.hasan.v1.ui.theme.ChakraPetch
import com.hasan.v1.ui.theme.HasanColors
import com.hasan.v1.ui.theme.HasanShapes
import com.hasan.v1.ui.theme.IBMPlexMono
import java.text.SimpleDateFormat
import java.util.Locale

/** Onglet Activité — journal en mémoire des événements relay/connexion (voir MainViewModel.activityLog). */
@Composable
fun ActivityScreen(events: List<ActivityEvent>) {
    Column(modifier = Modifier.fillMaxSize()) {
        ActivityHeader(events)
        if (events.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(24.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "Aucun événement pour l'instant",
                    color = HasanColors.TextMutedA11y,
                    textAlign = TextAlign.Center
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(events.asReversed(), key = { it.id }) { event ->
                    ActivityRow(event)
                }
            }
        }
    }
}

@Composable
private fun ActivityHeader(events: List<ActivityEvent>) {
    val lastEventLabel = events.lastOrNull()?.let { relativeTimeLabel(it.timestampMillis) }
        ?: "aucun événement"

    Column(modifier = Modifier.padding(16.dp)) {
        Text(
            text = events.size.toString(),
            color = HasanColors.TextPrimary,
            fontFamily = ChakraPetch,
            fontWeight = FontWeight.SemiBold,
            fontSize = 32.sp
        )
        Text(
            text = "dernier événement · $lastEventLabel",
            color = HasanColors.TextMutedA11y,
            fontFamily = IBMPlexMono,
            fontSize = 11.sp
        )
    }
}

@Composable
private fun ActivityRow(event: ActivityEvent) {
    CutCornerPanel(
        modifier = Modifier.fillMaxWidth(),
        shape = HasanShapes.panel()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(HasanColors.Accent)
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = event.title,
                    color = HasanColors.TextPrimary,
                    fontSize = 13.sp
                )
                Text(
                    text = formatTimestamp(event.timestampMillis),
                    color = HasanColors.TextMutedA11y,
                    fontFamily = IBMPlexMono,
                    fontSize = 10.sp
                )
            }
            TagPill(text = event.tag)
        }
    }
}

private val timeFormatter = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

private fun formatTimestamp(millis: Long): String = timeFormatter.format(millis)

private fun relativeTimeLabel(millis: Long): String {
    val deltaMs = System.currentTimeMillis() - millis
    val minutes = deltaMs / 60_000L
    return when {
        minutes < 1 -> "à l'instant"
        minutes < 60 -> "il y a ${minutes}min"
        else -> "il y a ${minutes / 60}h"
    }
}
