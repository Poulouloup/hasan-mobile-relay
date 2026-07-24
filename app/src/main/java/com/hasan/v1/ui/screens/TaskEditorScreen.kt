package com.hasan.v1.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hasan.v1.R
import com.hasan.v1.webui.models.CronJob
import com.hasan.v1.webui.models.DeliveryOption
import com.hasan.v1.ui.components.CutCornerPanel
import com.hasan.v1.ui.components.HasanIconButton
import com.hasan.v1.ui.theme.ChakraPetch
import com.hasan.v1.ui.theme.HasanColors
import com.hasan.v1.ui.theme.HasanDimens
import com.hasan.v1.ui.theme.HasanShapes
import com.hasan.v1.ui.theme.IBMPlexMono

/**
 * Formulaire plein écran de création/édition d'une tâche cron. [initialJob]
 * non-null = mode édition (champs pré-remplis), null = mode création.
 *
 * Format du champ schedule volontairement en texte libre (pas de picker) —
 * le serveur accepte 4 grammaires distinctes (voir texte d'aide) vérifiées
 * dans cron/jobs.py parse_schedule ; l'erreur de validation serveur est
 * affichée telle quelle si le format est rejeté (voir [errorMessage]).
 */
@Composable
fun TaskEditorScreen(
    initialJob: CronJob?,
    deliveryOptions: List<DeliveryOption>,
    errorMessage: String?,
    onSave: (prompt: String, schedule: String, name: String?, deliver: String?) -> Unit,
    onCancel: () -> Unit,
    onPickDelivery: (List<DeliveryOption>, (DeliveryOption) -> Unit) -> Unit
) {
    var name by remember { mutableStateOf(initialJob?.name.orEmpty()) }
    var prompt by remember { mutableStateOf(initialJob?.prompt.orEmpty()) }
    // scheduleDisplay est la forme lisible ("every 30m"), pas la valeur brute
    // d'origine (non renvoyée par le serveur) — réutilisée comme point de
    // départ éditable : le serveur re-parse le texte soumis quoi qu'il en
    // soit (POST /api/crons/update accepte une nouvelle chaîne schedule).
    var schedule by remember { mutableStateOf(initialJob?.scheduleDisplay.orEmpty()) }
    var deliver by remember { mutableStateOf(initialJob?.deliver ?: "local") }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(HasanColors.BgBase)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = HasanDimens.SpacingS, vertical = HasanDimens.SpacingXs),
            verticalAlignment = Alignment.CenterVertically
        ) {
            HasanIconButton(
                iconRes = R.drawable.ic_close,
                contentDescription = "Retour",
                onClick = onCancel
            )
            Text(
                text = if (initialJob == null) "Nouvelle tâche" else "Modifier la tâche",
                color = HasanColors.TextPrimary,
                fontFamily = ChakraPetch,
                fontWeight = FontWeight.SemiBold,
                fontSize = HasanDimens.TextTitleMedium,
                modifier = Modifier.padding(start = HasanDimens.SpacingS)
            )
        }

        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(HasanDimens.SpacingL)
        ) {

        errorMessage?.let {
            Text(
                text = it,
                color = HasanColors.Accent,
                fontSize = HasanDimens.TextBodyMedium,
                modifier = Modifier.padding(bottom = HasanDimens.SpacingM)
            )
        }

        EditorField(label = "Nom (optionnel)", value = name, onValueChange = { name = it }, singleLine = true)

        EditorField(
            label = "Prompt",
            value = prompt,
            onValueChange = { prompt = it },
            singleLine = false,
            minLines = 3
        )

        EditorField(
            label = "Planification",
            value = schedule,
            onValueChange = { schedule = it },
            singleLine = true,
            helpText = "Formats acceptés : \"every 30m\" / \"every 2h\" · expression cron " +
                "5 champs (\"0 9 * * *\") · horodatage ISO (\"2026-08-01T09:00\") · " +
                "durée simple (\"30m\", \"2h\")"
        )

        Text(
            text = "Livraison",
            color = HasanColors.TextSecondary,
            fontFamily = IBMPlexMono,
            fontSize = HasanDimens.TextCaption,
            modifier = Modifier.padding(top = HasanDimens.SpacingS, bottom = HasanDimens.SpacingXs)
        )
        CutCornerPanel(
            modifier = Modifier
                .fillMaxWidth()
                .clickable {
                    onPickDelivery(deliveryOptions) { picked -> deliver = picked.value }
                },
            shape = HasanShapes.panel()
        ) {
            Text(
                text = deliveryOptions.firstOrNull { it.value == deliver }?.label ?: deliver,
                color = HasanColors.TextPrimary,
                fontSize = HasanDimens.TextSubtitle,
                modifier = Modifier.padding(HasanDimens.SpacingM)
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth().padding(top = HasanDimens.SpacingXxl),
            horizontalArrangement = Arrangement.spacedBy(HasanDimens.SpacingM)
        ) {
            CutCornerPanel(
                modifier = Modifier
                    .weight(1f)
                    .clickable { onCancel() },
                shape = HasanShapes.panel()
            ) {
                Text(
                    text = "Annuler",
                    color = HasanColors.TextSecondary,
                    fontSize = HasanDimens.TextBody,
                    modifier = Modifier.padding(HasanDimens.SpacingM),
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                )
            }
            CutCornerPanel(
                modifier = Modifier
                    .weight(1f)
                    .clickable(enabled = prompt.isNotBlank() && schedule.isNotBlank()) {
                        onSave(prompt.trim(), schedule.trim(), name.trim().ifBlank { null }, deliver)
                    },
                shape = HasanShapes.panel(),
                backgroundColor = HasanColors.AccentGlowBg,
                borderColor = HasanColors.AccentDim
            ) {
                Text(
                    text = "Enregistrer",
                    color = HasanColors.Accent,
                    fontSize = HasanDimens.TextBody,
                    modifier = Modifier.padding(HasanDimens.SpacingM),
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                )
            }
        }
        }
    }
}

@Composable
private fun EditorField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    singleLine: Boolean,
    minLines: Int = 1,
    helpText: String? = null
) {
    Column(modifier = Modifier.fillMaxWidth().padding(top = HasanDimens.SpacingM)) {
        Text(
            text = label,
            color = HasanColors.TextSecondary,
            fontFamily = IBMPlexMono,
            fontSize = HasanDimens.TextCaption,
            modifier = Modifier.padding(bottom = HasanDimens.SpacingXs)
        )
        TextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = singleLine,
            minLines = minLines,
            modifier = Modifier.fillMaxWidth(),
            keyboardOptions = KeyboardOptions(imeAction = if (singleLine) ImeAction.Next else ImeAction.Default),
            colors = TextFieldDefaults.colors(
                focusedContainerColor = HasanColors.BgSurface,
                unfocusedContainerColor = HasanColors.BgSurface,
                focusedIndicatorColor = HasanColors.Accent,
                unfocusedIndicatorColor = HasanColors.Border,
                focusedTextColor = HasanColors.TextPrimary,
                unfocusedTextColor = HasanColors.TextPrimary,
                cursorColor = HasanColors.Accent
            )
        )
        helpText?.let {
            Text(
                text = it,
                color = HasanColors.TextMutedA11y,
                fontSize = HasanDimens.TextLabelMedium,
                modifier = Modifier.padding(top = HasanDimens.SpacingXs)
            )
        }
    }
}
