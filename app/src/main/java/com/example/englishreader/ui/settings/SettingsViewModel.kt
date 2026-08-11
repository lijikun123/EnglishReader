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
import com.example.englishreader.data.sync.SyncActionResult
import com.example.englishreader.data.sync.SyncRepository
import com.example.englishreader.data.sync.SyncRunResult
import com.example.englishreader.data.sync.SyncRuntimeState
import com.example.englishreader.data.sync.SyncSettings
import com.example.englishreader.di.container
import kotlinx.coroutines.CancellationException
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
    private val syncRepository: SyncRepository,
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

    val syncSettings: StateFlow<SyncSettings> = syncRepository.settings
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SyncSettings())

    val syncRuntimeState: StateFlow<SyncRuntimeState> = syncRepository.runtimeState

    private val _syncActionInProgress = MutableStateFlow(false)
    val syncActionInProgress: StateFlow<Boolean> = _syncActionInProgress.asStateFlow()

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

    fun saveSyncServer(serverUrl: String) {
        viewModelScope.launch {
            when (val result = syncRepository.setServerUrl(serverUrl)) {
                SyncActionResult.Success -> _message.value = "同步地址已保存"
                is SyncActionResult.Failure -> _message.value = result.message
            }
        }
    }

    fun loginSync(serverUrl: String, email: String, password: String) {
        authenticateSync("登录", serverUrl, email, password) { url, account, secret ->
            syncRepository.login(url, account, secret)
        }
    }

    fun registerSync(serverUrl: String, email: String, password: String) {
        authenticateSync("创建账号", serverUrl, email, password) { url, account, secret ->
            syncRepository.register(url, account, secret)
        }
    }

    fun syncNow() {
        viewModelScope.launch {
            _syncActionInProgress.value = true
            try {
                _message.value = syncResultMessage(syncRepository.syncFromSettings())
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                _message.value = "同步未完成：${error.message ?: "发生未知错误"}"
            } finally {
                _syncActionInProgress.value = false
            }
        }
    }

    fun logoutSync() {
        viewModelScope.launch {
            _syncActionInProgress.value = true
            try {
                syncRepository.logout()
                _message.value = "已退出同步账号；本机书籍不会被删除"
            } finally {
                _syncActionInProgress.value = false
            }
        }
    }

    private fun authenticateSync(
        action: String,
        serverUrl: String,
        email: String,
        password: String,
        request: suspend (String, String, String) -> SyncActionResult,
    ) {
        if (email.isBlank() || password.isBlank()) {
            _message.value = "请填写邮箱和密码"
            return
        }
        viewModelScope.launch {
            _syncActionInProgress.value = true
            try {
                when (val result = request(serverUrl, email, password)) {
                    SyncActionResult.Success -> {
                        _message.value = "${action}成功，${syncResultMessage(syncRepository.syncFromSettings())}"
                    }
                    is SyncActionResult.Failure -> _message.value = result.message
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                _message.value = "${action}未完成：${error.message ?: "发生未知错误"}"
            } finally {
                _syncActionInProgress.value = false
            }
        }
    }

    private fun syncResultMessage(result: SyncRunResult): String = when (result) {
        SyncRunResult.Success -> "已同步"
        SyncRunResult.InProgress -> "后台同步正在收尾，请稍后再试"
        SyncRunResult.NotConfigured -> "请先填写 HTTPS 同步地址"
        SyncRunResult.NotAuthenticated -> "请先登录同步账号"
        is SyncRunResult.PermanentFailure -> "同步未完成：${result.message}"
        is SyncRunResult.RetryableFailure -> "网络暂时不可用，将自动重试：${result.message}"
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
                    syncRepository = container.syncRepository,
                )
            }
        }
    }
}
