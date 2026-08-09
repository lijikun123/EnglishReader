package com.example.englishreader.ui.reader

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.unit.dp
import com.example.englishreader.ai.AiAnalysisType
import com.example.englishreader.data.local.entity.DictionaryEntry

/**
 * 查词 + AI 面板内容。手机端放进 ModalBottomSheet，平板端放进右侧栏。
 * 当 [lookup] 为 null 时显示占位提示（平板右侧栏空状态）。
 */
@Composable
fun LookupContent(
    lookup: LookupUiState?,
    aiResult: AiResultUiState?,
    onSave: () -> Unit,
    onAiAction: (AiAnalysisType) -> Unit,
    onClose: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    if (lookup == null) {
        Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                text = "点击正文中的英文单词\n即可查看中文释义",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        return
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
    ) {
        Row(verticalAlignment = Alignment.Top) {
            Box(Modifier.weight(1f)) {
                WordHeader(word = lookup.word, entry = lookup.entries.firstOrNull())
            }
            if (onClose != null) {
                IconButton(onClick = onClose) {
                    Icon(Icons.Filled.Close, contentDescription = "关闭")
                }
            }
        }

        if (lookup.entries.isEmpty()) {
            Text(
                text = "本地词典暂未收录该词。可尝试下方 AI 功能，或后续导入更完整的词典。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 12.dp),
            )
        } else {
            lookup.entries.forEachIndexed { index, entry ->
                if (index > 0) HorizontalDivider(Modifier.padding(vertical = 12.dp))
                EntryBlock(entry)
            }
        }

        // 收藏按钮
        Button(
            onClick = onSave,
            enabled = !lookup.saved,
            modifier = Modifier.fillMaxWidth().padding(top = 20.dp),
        ) {
            Icon(
                imageVector = if (lookup.saved) Icons.Filled.Check else Icons.Filled.Star,
                contentDescription = null,
            )
            Text(
                text = if (lookup.saved) "已收藏到生词本" else "收藏到生词本",
                modifier = Modifier.padding(start = 8.dp),
            )
        }

        // AI 动作区
        Text(
            text = "AI 助手",
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.padding(top = 24.dp, bottom = 8.dp),
        )
        AiActionButtons(onAiAction = onAiAction)

        if (aiResult != null) {
            AiResultBlock(aiResult, modifier = Modifier.padding(top = 16.dp))
        }
    }
}

@Composable
private fun WordHeader(word: String, entry: DictionaryEntry?) {
    Column {
        Text(text = word, style = MaterialTheme.typography.headlineSmall)
        val meta = listOfNotNull(
            entry?.phonetic?.takeIf { it.isNotBlank() },
            entry?.partOfSpeech?.takeIf { it.isNotBlank() },
        ).joinToString("  ")
        if (meta.isNotBlank()) {
            Text(
                text = meta,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

@Composable
private fun EntryBlock(entry: DictionaryEntry) {
    Column(Modifier.padding(top = 12.dp)) {
        // 中文释义优先、最醒目
        if (entry.chineseMeaning.isNotBlank()) {
            Text(
                text = entry.chineseMeaning,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        if (entry.englishDefinition.isNotBlank()) {
            Text(
                text = entry.englishDefinition,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 6.dp),
            )
        }
        if (entry.exampleSentence.isNotBlank()) {
            Text(
                text = "例：${entry.exampleSentence}",
                style = MaterialTheme.typography.bodyMedium,
                fontStyle = FontStyle.Italic,
                modifier = Modifier.padding(top = 6.dp),
            )
        }
    }
}

@Composable
private fun AiActionButtons(onAiAction: (AiAnalysisType) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        AiAnalysisType.entries.forEach { type ->
            OutlinedButton(
                onClick = { onAiAction(type) },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(type.label)
            }
        }
    }
}

@Composable
private fun AiResultBlock(state: AiResultUiState, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = MaterialTheme.shapes.medium,
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(
                text = state.type.label,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (state.loading && state.text.isEmpty()) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                    horizontalArrangement = Arrangement.Center,
                ) {
                    CircularProgressIndicator()
                }
            } else {
                // 流式：边生成边显示；生成中仍带 loading=true，但已有文本就直接展示
                Text(
                    text = state.text,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        }
    }
}
