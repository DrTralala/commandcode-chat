package com.commandcode.chat.ui.chat

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign

@Composable
fun ContextUsageIndicator(usage: ContextUsagePresentation) {
    Text(
        text = usage.label,
        style = MaterialTheme.typography.labelMedium,
        color = contextUsageColour(usage.level, MaterialTheme.colorScheme),
        maxLines = 1,
        textAlign = TextAlign.End,
        modifier = Modifier.testTag("context_usage").semantics {
            contentDescription = if (usage.level == ContextUsageLevel.UNKNOWN) "Context usage unavailable"
                else "Estimated context usage: ${usage.label}"
        },
    )
}

internal fun contextUsageColour(level: ContextUsageLevel, colours: ColorScheme): Color = when (level) {
    ContextUsageLevel.UNKNOWN -> colours.onSurfaceVariant
    ContextUsageLevel.LOW -> Color(0xFF60A5FA)
    ContextUsageLevel.MEDIUM -> Color(0xFFFB923C)
    ContextUsageLevel.HIGH -> Color(0xFFF87171)
}
