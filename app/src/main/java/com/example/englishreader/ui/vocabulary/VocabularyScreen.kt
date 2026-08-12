package com.example.englishreader.ui.vocabulary

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.englishreader.data.local.entity.VocabularyItem
import com.example.englishreader.ui.formatRelativeTime

private const val EXPORT_FILE_NAME = "english_reader_vocabulary.tsv"

@Composable
fun VocabularyScreen(
    viewModel: VocabularyViewModel = viewModel(factory = VocabularyViewModel.Factory),
) {
    val items by viewModel.items.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    var pendingDeletion by remember { mutableStateOf<VocabularyItem?>(null) }

    // SAF 创建文档：返回 null 表示用户取消保存。
    val exportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("text/tab-separated-values"),
        onResult = { uri -> if (uri == null) viewModel.notifyCancelled() else viewModel.exportTo(uri) },
    )

    LaunchedEffect(message) {
        message?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.consumeMessage()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("生词本") },
                actions = {
                    IconButton(onClick = {
                        // 空生词本不生成文件，仅提示。
                        if (items.isEmpty()) viewModel.notifyEmpty()
                        else exportLauncher.launch(EXPORT_FILE_NAME)
                    }) {
                        Icon(Icons.Filled.Share, contentDescription = "导出 Anki TSV")
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        if (items.isEmpty()) {
            Box(
                Modifier.fillMaxSize().padding(padding),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "还没有收藏内容\n阅读时点击单词即可收藏；开启「词组」后也可收藏 AI 识别的词组",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 360.dp),
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(items, key = { it.id }) { item ->
                    VocabularyCard(item = item, onDeleteRequest = { pendingDeletion = item })
                }
            }
        }
    }

    pendingDeletion?.let { item ->
        DeleteVocabularyDialog(
            item = item,
            onConfirm = {
                viewModel.delete(item)
                pendingDeletion = null
            },
            onDismiss = { pendingDeletion = null },
        )
    }
}

@Composable
private fun VocabularyCard(item: VocabularyItem, onDeleteRequest: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(text = item.word, style = MaterialTheme.typography.titleMedium)
                    val meta = listOf(item.phonetic, item.partOfSpeech)
                        .filter { it.isNotBlank() }
                        .joinToString("  ")
                    if (meta.isNotBlank()) {
                        Text(
                            text = meta,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                IconButton(onClick = onDeleteRequest) {
                    Icon(Icons.Filled.Delete, contentDescription = "删除")
                }
            }

            if (item.chineseMeaning.isNotBlank()) {
                Text(
                    text = item.chineseMeaning,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
            if (item.englishDefinition.isNotBlank()) {
                Text(
                    text = item.englishDefinition,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            if (item.sourceSentence.isNotBlank()) {
                Text(
                    text = "原句：${item.sourceSentence}",
                    style = MaterialTheme.typography.bodySmall,
                    fontStyle = FontStyle.Italic,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }

            val source = listOf(item.sourceBookTitle, item.sourceChapterTitle)
                .filter { it.isNotBlank() }
                .joinToString(" · ")
            if (source.isNotBlank()) {
                Text(
                    text = "来源：$source",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }

            Text(
                text = "收藏于 ${formatRelativeTime(item.createdAt)}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
    }
}

@Composable
private fun DeleteVocabularyDialog(
    item: VocabularyItem,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("删除收藏？") },
        text = {
            Text(
                "「${item.word}」会从当前设备的生词本删除，不会影响原书。" +
                    "生词本跨设备同步尚未启用。",
            )
        },
        confirmButton = { TextButton(onClick = onConfirm) { Text("删除") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}
