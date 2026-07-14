package com.hasan.v1.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.hasan.v1.R

/**
 * Chakra Petch (600) / IBM Plex Sans (400/500) / IBM Plex Mono (400/500) —
 * licence SIL Open Font License v1.1 pour les trois familles (voir
 * licenses/fonts/OFL-Chakra-Petch.txt et OFL-IBM-Plex.txt).
 */
val ChakraPetch = FontFamily(
    Font(R.font.chakra_petch_medium, FontWeight.Medium),
    Font(R.font.chakra_petch_semibold, FontWeight.SemiBold)
)

val IBMPlexSans = FontFamily(
    Font(R.font.ibm_plex_sans_regular, FontWeight.Normal),
    Font(R.font.ibm_plex_sans_medium, FontWeight.Medium)
)

val IBMPlexMono = FontFamily(
    Font(R.font.ibm_plex_mono_regular, FontWeight.Normal),
    Font(R.font.ibm_plex_mono_medium, FontWeight.Medium)
)

/** Style dédié aux données système (timestamps, latence, empreintes) — hors Typography Material par défaut. */
val HasanMonoLabelSmall = TextStyle(
    fontFamily = IBMPlexMono,
    fontWeight = FontWeight.Normal,
    fontSize = 10.sp,
    letterSpacing = 0.5.sp
)

val HasanTypography = Typography(
    displaySmall = TextStyle(
        fontFamily = ChakraPetch,
        fontWeight = FontWeight.SemiBold,
        fontSize = 15.sp,
        letterSpacing = 2.sp
    ),
    titleMedium = TextStyle(
        fontFamily = ChakraPetch,
        fontWeight = FontWeight.SemiBold,
        fontSize = 20.sp
    ),
    bodyLarge = TextStyle(
        fontFamily = IBMPlexSans,
        fontWeight = FontWeight.Normal,
        fontSize = 12.5.sp,
        lineHeight = 19.4.sp
    ),
    bodyMedium = TextStyle(
        fontFamily = IBMPlexSans,
        fontWeight = FontWeight.Normal,
        fontSize = 12.sp
    ),
    labelSmall = TextStyle(
        fontFamily = IBMPlexMono,
        fontWeight = FontWeight.Normal,
        fontSize = 9.sp,
        letterSpacing = 1.sp
    ),
    labelMedium = TextStyle(
        fontFamily = IBMPlexMono,
        fontWeight = FontWeight.Medium,
        fontSize = 10.5.sp
    )
)
