package com.hasan.v1.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hasan.v1.ui.theme.HasanColors
import com.hasan.v1.ui.theme.IBMPlexMono

enum class HasanNavTab { CHAT, ACTIVITY, SETTINGS }

data class HasanNavItem(val tab: HasanNavTab, val iconRes: Int, val label: String)

/** Bottom nav — indicateur actif = bordure haute fine accent (voir .navtab.active dans hasan-mockup-v2.html), pas de pilule Material. */
@Composable
fun HasanBottomNav(
    items: List<HasanNavItem>,
    selected: HasanNavTab,
    onSelect: (HasanNavTab) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(HasanColors.BgBase)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceEvenly
    ) {
        items.forEach { item ->
            val isActive = item.tab == selected
            val contentColor = if (isActive) HasanColors.Accent else HasanColors.TextMutedA11y
            Column(
                modifier = Modifier
                    .weight(1f)
                    .clickable { onSelect(item.tab) },
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(2.dp)
                        .background(if (isActive) HasanColors.Accent else HasanColors.BgBase)
                )
                Column(
                    modifier = Modifier.padding(top = 8.dp, bottom = 8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Image(
                        painter = painterResource(item.iconRes),
                        contentDescription = item.label,
                        colorFilter = ColorFilter.tint(contentColor),
                        modifier = Modifier.size(20.dp)
                    )
                    Text(
                        text = item.label,
                        color = contentColor,
                        fontFamily = IBMPlexMono,
                        fontWeight = if (isActive) FontWeight.Medium else FontWeight.Normal,
                        fontSize = 9.sp,
                        letterSpacing = 0.5.sp,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
            }
        }
    }
}
