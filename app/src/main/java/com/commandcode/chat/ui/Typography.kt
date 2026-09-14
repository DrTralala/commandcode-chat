package com.commandcode.chat.ui

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import com.commandcode.chat.R

internal val NunitoFontFamily: FontFamily = FontFamily(
    Font(R.font.nunito_variable, weight = FontWeight.Normal),
)

private fun TextStyle.withNunito(): TextStyle = copy(fontFamily = NunitoFontFamily)

private val DefaultTypography = Typography()

internal val AppTypography: Typography = DefaultTypography.copy(
    displayLarge = DefaultTypography.displayLarge.withNunito(),
    displayMedium = DefaultTypography.displayMedium.withNunito(),
    displaySmall = DefaultTypography.displaySmall.withNunito(),
    headlineLarge = DefaultTypography.headlineLarge.withNunito(),
    headlineMedium = DefaultTypography.headlineMedium.withNunito(),
    headlineSmall = DefaultTypography.headlineSmall.withNunito(),
    titleLarge = DefaultTypography.titleLarge.withNunito(),
    titleMedium = DefaultTypography.titleMedium.withNunito(),
    titleSmall = DefaultTypography.titleSmall.withNunito(),
    bodyLarge = DefaultTypography.bodyLarge.withNunito(),
    bodyMedium = DefaultTypography.bodyMedium.withNunito(),
    bodySmall = DefaultTypography.bodySmall.withNunito(),
    labelLarge = DefaultTypography.labelLarge.withNunito(),
    labelMedium = DefaultTypography.labelMedium.withNunito(),
    labelSmall = DefaultTypography.labelSmall.withNunito(),
)
