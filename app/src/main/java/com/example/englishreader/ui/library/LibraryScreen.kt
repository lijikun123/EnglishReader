package com.example.englishreader.ui.library

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SuggestionChip
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.englishreader.data.importer.ImportResult
import com.example.englishreader.data.local.entity.BookFormat
import com.example.englishreader.data.local.entity.ContentType
import com.example.englishreader.data.local.entity.ReadingItem
import com.example.englishreader.ui.formatPercent
import com.example.englishreader.ui.formatRelativeTime

@Composable
fun LibraryScreen(
    onOpenReader: (Long) -> Unit,
    viewModel: LibraryViewModel = viewModel(factory = LibraryViewModel.Factory),
) {
    val items by viewModel.items.collectAsStateWithLifecycle()
    val pendingImport by viewModel.pendingImport.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    var pendingDeletion by remember { mutableStateOf<ReadingItem?>(null) }

    val snackbarHostState = remember { SnackbarHostState() }

    // SAF 文件选择器：EPUB 多被标为 application/epub+zip，文本类用 text/*，
    // 少数 .md 被标为通用类型，最终都按扩展名在 DocumentImporter 中校验。
    val importLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
        onResult = { uri -> viewModel.onFilePicked(uri) },
    )

    LaunchedEffect(message) {
        message?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.consumeMessage()
        }
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text("KReader") }) },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = {
                    importLauncher.launch(
                        arrayOf("application/epub+zip", "text/*", "application/octet-stream"),
                    )
                },
                icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                text = { Text("导入文件") },
            )
        },
    ) { padding ->
        if (items.isEmpty()) {
            EmptyLibrary(Modifier.fillMaxSize().padding(padding))
        } else {
            LazyVerticalGrid(
                // 自适应列数：手机一列，平板多列。
                columns = GridCells.Adaptive(minSize = 320.dp),
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(items, key = { it.id }) { item ->
                    LibraryCard(
                        item = item,
                        onClick = { onOpenReader(item.id) },
                        onDeleteRequest = { pendingDeletion = item },
                    )
                }
            }
        }
    }

    pendingImport?.let { pending ->
        ImportDialog(
            pending = pending,
            onConfirm = { title, author, type -> viewModel.confirmImport(title, author, type) },
            onDismiss = { viewModel.cancelImport() },
        )
    }

    pendingDeletion?.let { item ->
        DeleteBookDialog(
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
private fun ImportDialog(
    pending: ImportResult,
    onConfirm: (title: String, author: String, type: ContentType) -> Unit,
    onDismiss: () -> Unit,
) {
    val isEpub = pending is ImportResult.Epub
    val defaultTitle = when (pending) {
        is ImportResult.Text -> pending.title
        is ImportResult.Epub -> pending.title
        is ImportResult.Error -> ""
    }
    val defaultAuthor = (pending as? ImportResult.Epub)?.author.orEmpty()

    var title by remember(pending) { mutableStateOf(defaultTitle) }
    var author by remember(pending) { mutableStateOf(defaultAuthor) }
    var type by remember(pending) { mutableStateOf(ContentType.NOVEL) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (isEpub) "导入 EPUB" else "导入文件") },
        text = {
            Column {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text("书名 / 标题") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (isEpub) {
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = author,
                        onValueChange = { author = it },
                        label = { Text("作者") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                Spacer(Modifier.height(16.dp))
                Text("类型", style = MaterialTheme.typography.labelLarge)
                Row(
                    modifier = Modifier.padding(top = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    FilterChip(
                        selected = type == ContentType.NOVEL,
                        onClick = { type = ContentType.NOVEL },
                        label = { Text("Novel") },
                    )
                    FilterChip(
                        selected = type == ContentType.ARTICLE,
                        onClick = { type = ContentType.ARTICLE },
                        label = { Text("Article") },
                    )
                }
                Spacer(Modifier.height(12.dp))
                Text(
                    text = when (pending) {
                        is ImportResult.Epub -> "EPUB · ${pending.chapters.size} 章"
                        is ImportResult.Text -> "${pending.format.label()} · ${pending.content.length} 字符"
                        is ImportResult.Error -> ""
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(title, author, type) }) { Text("导入") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}

@Composable
private fun LibraryCard(
    item: ReadingItem,
    onClick: () -> Unit,
    onDeleteRequest: () -> Unit,
) {
    var menuExpanded by remember { mutableStateOf(false) }
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top,
            ) {
                Text(
                    text = item.title,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f).padding(end = 8.dp),
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    ContentTypeChip(item.contentType)
                    Box {
                        IconButton(onClick = { menuExpanded = true }) {
                            Icon(Icons.Filled.MoreVert, contentDescription = "更多操作")
                        }
                        DropdownMenu(
                            expanded = menuExpanded,
                            onDismissRequest = { menuExpanded = false },
                        ) {
                            DropdownMenuItem(
                                text = { Text("删除") },
                                leadingIcon = { Icon(Icons.Filled.Delete, contentDescription = null) },
                                onClick = {
                                    menuExpanded = false
                                    onDeleteRequest()
                                },
                            )
                        }
                    }
                }
            }

            if (item.author.isNotBlank()) {
                Text(
                    text = "作者：${item.author}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }

            if (item.content.isNotBlank()) {
                Text(
                    text = item.content.take(80).replace("\n", " ") + "…",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }

            LinearProgressIndicator(
                progress = { item.progress.coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth().padding(top = 14.dp),
            )

            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = "${item.format.label()} · 进度 ${formatPercent(item.progress)}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = formatRelativeTime(item.updatedAt),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun DeleteBookDialog(
    item: ReadingItem,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("删除书籍？") },
        text = {
            Text(
                "《${item.title}》的正文、阅读进度和本地 AI 缓存会被删除。" +
                    "已收藏的生词会保留。若已开启同步，下一次同步会同时从其它设备移除这本书。",
            )
        },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text("删除") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}

@Composable
private fun ContentTypeChip(type: ContentType) {
    val label = if (type == ContentType.NOVEL) "Novel" else "Article"
    SuggestionChip(
        onClick = {},
        label = { Text(label) },
    )
}

@Composable
private fun EmptyLibrary(modifier: Modifier) {
    Box(modifier, contentAlignment = Alignment.Center) {
        Text(
            text = "书架还是空的\n点击右下角「导入文件」导入 TXT / Markdown / EPUB",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private fun BookFormat.label(): String = when (this) {
    BookFormat.TXT -> "TXT"
    BookFormat.MARKDOWN -> "MD"
    BookFormat.EPUB -> "EPUB"
}
