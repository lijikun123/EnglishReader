package com.example.englishreader.ui.vocabulary

import android.app.Activity
import android.content.ClipData
import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.englishreader.data.local.entity.VocabularyItem
import com.example.englishreader.ui.formatRelativeTime

@Composable
fun VocabularyScreen(
    viewModel: VocabularyViewModel = viewModel(factory = VocabularyViewModel.Factory),
) {
    val items by viewModel.items.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val context = LocalContext.current

    var selectionMode by remember { mutableStateOf(false) }
    var selectedIds by remember { mutableStateOf<Set<Long>>(emptySet()) }
    var pendingDeletion by remember { mutableStateOf<List<VocabularyItem>?>(null) }

    val allIds = remember(items) { items.mapTo(linkedSetOf()) { it.id } }
    val selectedItems = remember(items, selectedIds) { items.filter { it.id in selectedIds } }

    BackHandler(enabled = selectionMode) {
        selectionMode = false
        selectedIds = emptySet()
    }

    // A background update/deletion must not leave a no-longer-existing row selected.
    LaunchedEffect(allIds) {
        selectedIds = selectedIds.intersect(allIds)
    }

    LaunchedEffect(message) {
        message?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.consumeMessage()
        }
    }

    // This is a real Android share sheet, not the system file-save screen. Closing it
    // returns here directly instead of walking back through a file-manager stack.
    LaunchedEffect(viewModel) {
        viewModel.shareRequests.collect { request ->
            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_STREAM, request.uri)
                putExtra(Intent.EXTRA_TITLE, "english_reader_vocabulary.tsv")
                clipData = ClipData.newRawUri("English Reader vocabulary", request.uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            val chooser = Intent.createChooser(shareIntent, "分享 ${request.itemCount} 项生词")
            if (context !is Activity) chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(chooser)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        if (selectionMode) "已选择 ${selectedItems.size} 项" else "生词本",
                    )
                },
                actions = {
                    if (selectionMode) {
                        TextButton(
                            onClick = {
                                selectedIds = if (allIds.isNotEmpty() && selectedIds.containsAll(allIds)) {
                                    emptySet()
                                } else {
                                    allIds
                                }
                            },
                        ) {
                            Text(if (allIds.isNotEmpty() && selectedIds.containsAll(allIds)) "取消全选" else "全选")
                        }
                        IconButton(
                            enabled = selectedItems.isNotEmpty(),
                            onClick = {
                                viewModel.share(selectedItems)
                                selectionMode = false
                                selectedIds = emptySet()
                            },
                        ) {
                            Icon(Icons.Filled.Share, contentDescription = "分享选中的生词")
                        }
                        IconButton(
                            enabled = selectedItems.isNotEmpty(),
                            onClick = { pendingDeletion = selectedItems },
                        ) {
                            Icon(Icons.Filled.Delete, contentDescription = "删除选中的生词")
                        }
                        IconButton(
                            onClick = {
                                selectionMode = false
                                selectedIds = emptySet()
                            },
                        ) {
                            Icon(Icons.Filled.Close, contentDescription = "退出选择")
                        }
                    } else {
                        TextButton(onClick = { selectionMode = true }) {
                            Text("选择")
                        }
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
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(vertical = 4.dp),
            ) {
                items(items, key = { it.id }) { item ->
                    VocabularyListItem(
                        item = item,
                        selectionMode = selectionMode,
                        selected = item.id in selectedIds,
                        onToggleSelection = {
                            selectedIds = if (item.id in selectedIds) {
                                selectedIds - item.id
                            } else {
                                selectedIds + item.id
                            }
                        },
                    )
                    HorizontalDivider()
                }
            }
        }
    }

    pendingDeletion?.let { deleteItems ->
        DeleteVocabularyDialog(
            count = deleteItems.size,
            onConfirm = {
                viewModel.delete(deleteItems)
                pendingDeletion = null
                selectionMode = false
                selectedIds = emptySet()
            },
            onDismiss = { pendingDeletion = null },
        )
    }
}

@Composable
private fun VocabularyListItem(
    item: VocabularyItem,
    selectionMode: Boolean,
    selected: Boolean,
    onToggleSelection: () -> Unit,
) {
    val rowModifier = if (selectionMode) {
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onToggleSelection)
            .padding(horizontal = 16.dp, vertical = 12.dp)
    } else {
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp)
    }

    Row(
        modifier = rowModifier,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.Top,
    ) {
        if (selectionMode) {
            Checkbox(checked = selected, onCheckedChange = { onToggleSelection() })
        }
        Column(modifier = Modifier.weight(1f)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = item.word,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = formatRelativeTime(item.createdAt),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 12.dp),
                )
            }

            val meta = listOf(item.phonetic, item.partOfSpeech)
                .filter { it.isNotBlank() }
                .joinToString("  ")
            if (meta.isNotBlank()) {
                Text(
                    text = meta,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
            if (item.chineseMeaning.isNotBlank()) {
                Text(
                    text = item.chineseMeaning,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            if (item.englishDefinition.isNotBlank()) {
                Text(
                    text = item.englishDefinition,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
            if (item.sourceSentence.isNotBlank()) {
                Text(
                    text = "原句：${item.sourceSentence}",
                    style = MaterialTheme.typography.bodySmall,
                    fontStyle = FontStyle.Italic,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 6.dp),
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
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
        }
    }
}

@Composable
private fun DeleteVocabularyDialog(
    count: Int,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("删除 $count 个收藏？") },
        text = {
            Text(
                "所选的词或词组会从当前设备的生词本删除，不会影响原书。" +
                    "生词本跨设备同步尚未启用。",
            )
        },
        confirmButton = { TextButton(onClick = onConfirm) { Text("删除") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}
