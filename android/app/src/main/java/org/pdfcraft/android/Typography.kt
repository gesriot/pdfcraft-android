package org.pdfcraft.android

import androidx.compose.material3.Typography
import androidx.compose.ui.text.font.FontFamily

// Apply the bundled, attributed font to Material controls as well as body text.
fun interTypography(font: FontFamily): Typography = Typography().let {
    it.copy(
        displayLarge = it.displayLarge.copy(fontFamily = font),
        displayMedium = it.displayMedium.copy(fontFamily = font),
        displaySmall = it.displaySmall.copy(fontFamily = font),
        headlineLarge = it.headlineLarge.copy(fontFamily = font),
        headlineMedium = it.headlineMedium.copy(fontFamily = font),
        headlineSmall = it.headlineSmall.copy(fontFamily = font),
        titleLarge = it.titleLarge.copy(fontFamily = font),
        titleMedium = it.titleMedium.copy(fontFamily = font),
        titleSmall = it.titleSmall.copy(fontFamily = font),
        bodyLarge = it.bodyLarge.copy(fontFamily = font),
        bodyMedium = it.bodyMedium.copy(fontFamily = font),
        bodySmall = it.bodySmall.copy(fontFamily = font),
        labelLarge = it.labelLarge.copy(fontFamily = font),
        labelMedium = it.labelMedium.copy(fontFamily = font),
        labelSmall = it.labelSmall.copy(fontFamily = font),
    )
}
