package com.example.englishreader.ui.settings

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.example.englishreader.data.importer.DictionaryImporter
import com.example.englishreader.data.importer.DictionaryImportResult
import com.example.englishreader.data.model.AiSettings
import com.example.englishreader.data.model.ReadingSettings
import com.example.englishreader.data.repository.DictionaryRepository
import com.example.englishreader.data.repository.SettingsRepository
import com.example.englishreader.di.container
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class SettingsViewModel(
    private val settingsRepository: SettingsRepository,
    private val dictionaryRepository: DictionaryRepository,
    private val dictionaryImporter: DictionaryImporter,
) : ViewModel() {

    val readingSettings: StateFlow<ReadingSettings> = settingsRepository.readingSettings
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ReadingSettings())

    val aiSettings: StateFlow<AiSettings> = settingsRepository.aiSettings
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AiSettings())

    val preferChineseFirst: StateFlow<Boolean> = settingsRepository.preferChineseFirst
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)

    /** 当前词典词条数（含内置示例 + 已导入）。 */
    val dictionaryCount: StateFlow<Int> = dictionaryRepository.observeCount()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    private val _dictionaryImporting = MutableStateFlow(false)
    val dictionaryImporting: StateFlow<Boolean> = _dictionaryImporting.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    fun updateReadingSettings(transform: (ReadingSettings) -> ReadingSettings) {
        viewModelScope.launch { settingsRepository.updateReadingSettings(transform) }
    }

    fun setPreferChineseFirst(value: Boolean) {
        viewModelScope.launch { settingsRepository.setPreferChineseFirst(value) }
    }

    fun updateAiSettings(transform: (AiSettings) -> AiSettings) {
        viewModelScope.launch { settingsRepository.updateAiSettings(transform) }
    }

    /** SAF 选择词典文件回调（uri 为 null 表示取消）。 */
    fun onDictionaryFilePicked(uri: Uri?) {
        if (uri == null) return
        viewModelScope.launch {
            _dictionaryImporting.value = true
            when (val result = dictionaryImporter.read(uri)) {
                is DictionaryImportResult.Success -> {
                    val count = dictionaryRepository.importEntries(result.entries)
                    _message.value = "已导入 $count 条词典记录"
                }
                is DictionaryImportResult.Error -> _message.value = result.message
            }
            _dictionaryImporting.value = false
        }
    }

    fun consumeMessage() {
        _message.value = null
    }

    companion object {
        val Factory = viewModelFactory {
            initializer {
                SettingsViewModel(
                    settingsRepository = container.settingsRepository,
                    dictionaryRepository = container.dictionaryRepository,
                    dictionaryImporter = container.dictionaryImporter,
                )
            }
        }
    }
}
