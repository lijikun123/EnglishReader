package com.example.englishreader.ui.library

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.example.englishreader.data.importer.DocumentImporter
import com.example.englishreader.data.importer.ImportResult
import com.example.englishreader.data.local.entity.ContentType
import com.example.englishreader.data.local.entity.ReadingItem
import com.example.englishreader.data.repository.ReadingRepository
import com.example.englishreader.di.container
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class LibraryViewModel(
    private val readingRepository: ReadingRepository,
    private val documentImporter: DocumentImporter,
) : ViewModel() {

    val items: StateFlow<List<ReadingItem>> = readingRepository.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** 解析成功、等待用户确认的导入结果（Text 或 Epub；Error 不会进此状态）。 */
    private val _pendingImport = MutableStateFlow<ImportResult?>(null)
    val pendingImport: StateFlow<ImportResult?> = _pendingImport.asStateFlow()

    /** 是否正在解析（EPUB 可能稍慢，给个 loading 提示）。 */
    private val _importing = MutableStateFlow(false)
    val importing: StateFlow<Boolean> = _importing.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    /** SAF 选择文件回调（uri 为 null 表示用户取消）。 */
    fun onFilePicked(uri: Uri?) {
        if (uri == null) return
        viewModelScope.launch {
            _importing.value = true
            when (val result = documentImporter.read(uri)) {
                is ImportResult.Error -> _message.value = result.message
                else -> _pendingImport.value = result
            }
            _importing.value = false
        }
    }

    /** 用户在弹窗中确认后真正写入数据库。 */
    fun confirmImport(title: String, author: String, type: ContentType) {
        val pending = _pendingImport.value ?: return
        viewModelScope.launch {
            when (pending) {
                is ImportResult.Text -> {
                    val finalTitle = title.trim().ifBlank { pending.title }
                    readingRepository.addTextBook(finalTitle, pending.content, type, pending.format)
                    _message.value = "已导入：$finalTitle"
                }
                is ImportResult.Epub -> {
                    val finalTitle = title.trim().ifBlank { pending.title }
                    readingRepository.addEpubBook(finalTitle, author.trim(), type, pending.chapters, pending.toc)
                    _message.value = "已导入：$finalTitle（${pending.chapters.size} 章）"
                }
                is ImportResult.Error -> Unit
            }
            _pendingImport.value = null
        }
    }

    fun cancelImport() {
        _pendingImport.value = null
    }

    /** 本机先删除；若已登录同步账号，仓库会在后台安全传播删除标记。 */
    fun delete(item: ReadingItem) {
        viewModelScope.launch {
            readingRepository.delete(item)
            _message.value = "已删除《${item.title}》"
        }
    }

    fun consumeMessage() {
        _message.value = null
    }

    companion object {
        val Factory = viewModelFactory {
            initializer {
                LibraryViewModel(
                    readingRepository = container.readingRepository,
                    documentImporter = container.documentImporter,
                )
            }
        }
    }
}
