package com.example.englishreader.ui.reader

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.englishreader.data.model.ReadingSettings
import com.example.englishreader.data.model.ThemeMode

/**
 * 阅读排版设置面板（手机端为底部弹窗内容）。
 * 与「设置页 → 阅读设置」共用同一份 DataStore，改动实时生效。
 */
@Composable
fun ReaderSettingsSheet(
    settings: ReadingSettings,
    onChange: ((ReadingSettings) -> ReadingSettings) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp)) {
        Text(
            text = "阅读设置",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(bottom = 12.dp),
        )

        ThemeModeRow(current = settings.themeMode, onSelect = { mode -> onChange { it.copy(themeMode = mode) } })

        SettingSlider(
            label = "字号",
            value = settings.fontSize,
            valueText = "${settings.fontSize.toInt()} sp",
            range = ReadingSettings.MIN_FONT_SIZE..ReadingSettings.MAX_FONT_SIZE,
            onValueChange = { v -> onChange { it.copy(fontSize = v) } },
        )
        SettingSlider(
            label = "行距",
            value = settings.lineHeight,
            valueText = String.format("%.1f×", settings.lineHeight),
            range = ReadingSettings.MIN_LINE_HEIGHT..ReadingSettings.MAX_LINE_HEIGHT,
            onValueChange = { v -> onChange { it.copy(lineHeight = v) } },
        )
        SettingSlider(
            label = "段落间距",
            value = settings.paragraphSpacing,
            valueText = "${settings.paragraphSpacing.toInt()} dp",
            range = ReadingSettings.MIN_PARAGRAPH_SPACING..ReadingSettings.MAX_PARAGRAPH_SPACING,
            onValueChange = { v -> onChange { it.copy(paragraphSpacing = v) } },
        )
        SettingSlider(
            label = "阅读宽度",
            value = settings.readingWidth,
            valueText = "${settings.readingWidth.toInt()} dp",
            range = ReadingSettings.MIN_READING_WIDTH..ReadingSettings.MAX_READING_WIDTH,
            onValueChange = { v -> onChange { it.copy(readingWidth = v) } },
        )
    }
}

@Composable
private fun ThemeModeRow(current: ThemeMode, onSelect: (ThemeMode) -> Unit) {
    Column(Modifier.padding(bottom = 8.dp)) {
        Text(text = "主题", style = MaterialTheme.typography.labelLarge)
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            ThemeMode.entries.forEach { mode ->
                ThemeChip(
                    label = mode.displayName(),
                    selected = mode == current,
                    onClick = { onSelect(mode) },
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun ThemeChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val bg = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant
    val fg = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
    Surface(
        color = bg,
        shape = MaterialTheme.shapes.small,
        modifier = modifier.selectable(selected = selected, onClick = onClick),
    ) {
        Text(
            text = label,
            color = fg,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
            modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
    }
}

@Composable
private fun SettingSlider(
    label: String,
    value: Float,
    valueText: String,
    range: ClosedFloatingPointRange<Float>,
    onValueChange: (Float) -> Unit,
) {
    Column(Modifier.padding(top = 8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(text = label, style = MaterialTheme.typography.labelLarge)
            Text(
                text = valueText,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = range,
        )
    }
}

fun ThemeMode.displayName(): String = when (this) {
    ThemeMode.LIGHT -> "浅色"
    ThemeMode.DARK -> "深色"
    ThemeMode.SEPIA -> "护眼"
}
