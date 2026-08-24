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
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class VocabularyShareRequest(
    val uri: Uri,
    val itemCount: Int,
)

class VocabularyViewModel(
    private val vocabularyRepository: VocabularyRepository,
    private val dictionaryRepository: DictionaryRepository,
    private val ankiExporter: AnkiExporter,
) : ViewModel() {

    val items: StateFlow<List<VocabularyItem>> = vocabularyRepository.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    private val _shareRequests = Channel<VocabularyShareRequest>(Channel.BUFFERED)
    val shareRequests = _shareRequests.receiveAsFlow()

    fun delete(deleteItems: List<VocabularyItem>) {
        val snapshot = deleteItems.distinctBy { it.id }
        if (snapshot.isEmpty()) return
        viewModelScope.launch {
            vocabularyRepository.deleteByIds(snapshot.map { it.id })
            _message.value = if (snapshot.size == 1) {
                "已删除「${snapshot.single().word}」"
            } else {
                "已删除 ${snapshot.size} 个收藏"
            }
        }
    }

    /** Builds a temporary TSV and asks the UI to open Android's system share sheet. */
    fun share(shareItems: List<VocabularyItem>) {
        val snapshot = shareItems.toList()
        if (snapshot.isEmpty()) {
            _message.value = "请先选择要分享的生词"
            return
        }
        viewModelScope.launch {
            try {
                _shareRequests.send(
                    VocabularyShareRequest(
                        uri = ankiExporter.createShareUri(buildTsv(snapshot)),
                        itemCount = snapshot.size,
                    ),
                )
            } catch (e: Exception) {
                _message.value = "准备分享失败：${e.message ?: "未知错误"}"
            }
        }
    }

    fun consumeMessage() {
        _message.value = null
    }

    private suspend fun buildTsv(list: List<VocabularyItem>): String = buildString {
        for (item in list) {
            // 中文释义：收藏条目优先；为空则回退导入词典；仍为空交由格式化层兜底「暂无释义」。
            val chinese = item.chineseMeaning.ifBlank {
                dictionaryRepository.lookup(item.word).firstOrNull()?.chineseMeaning.orEmpty()
            }
            append(AnkiTsv.line(item, chinese)).append('\n')
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
