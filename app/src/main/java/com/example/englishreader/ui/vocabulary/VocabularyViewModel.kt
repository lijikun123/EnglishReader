package com.example.englishreader.ui.vocabulary

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.example.englishreader.data.exporter.AnkiExporter
import com.example.englishreader.data.exporter.AnkiTsv
import com.example.englishreader.data.local.entity.VocabularyItem
import com.example.englishreader.data.repository.DictionaryRepository
import com.example.englishreader.data.repository.VocabularyRepository
import com.example.englishreader.di.container
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class VocabularyViewModel(
    private val vocabularyRepository: VocabularyRepository,
    private val dictionaryRepository: DictionaryRepository,
    private val ankiExporter: AnkiExporter,
) : ViewModel() {

    val items: StateFlow<List<VocabularyItem>> = vocabularyRepository.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    fun delete(item: VocabularyItem) {
        viewModelScope.launch { vocabularyRepository.delete(item) }
    }

    fun notifyEmpty() {
        _message.value = "生词本为空，无需导出"
    }

    fun notifyCancelled() {
        _message.value = "已取消导出"
    }

    fun consumeMessage() {
        _message.value = null
    }

    /** 把全部生词写成 Anki TSV 到用户选定的 Uri。 */
    fun exportTo(uri: Uri) {
        viewModelScope.launch {
            val list = items.value
            if (list.isEmpty()) {
                _message.value = "生词本为空，无需导出"
                return@launch
            }
            try {
                val content = buildString {
                    for (item in list) {
                        // 中文释义：生词条目优先；为空则回退导入词典；仍为空交由格式化层兜底「暂无释义」
                        val chinese = item.chineseMeaning.ifBlank {
                            dictionaryRepository.lookup(item.word).firstOrNull()?.chineseMeaning.orEmpty()
                        }
                        append(AnkiTsv.line(item, chinese)).append('\n')
                    }
                }
                ankiExporter.write(uri, content)
                _message.value = "已导出 ${list.size} 个生词"
            } catch (e: Exception) {
                _message.value = "导出失败：${e.message ?: "未知错误"}"
            }
        }
    }

    companion object {
        val Factory = viewModelFactory {
            initializer {
                VocabularyViewModel(
                    vocabularyRepository = container.vocabularyRepository,
                    dictionaryRepository = container.dictionaryRepository,
                    ankiExporter = container.ankiExporter,
                )
            }
        }
    }
}
