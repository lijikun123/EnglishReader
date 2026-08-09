package com.example.englishreader.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.englishreader.data.model.AiSettings
import com.example.englishreader.ui.reader.ReaderSettingsSheet

@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel = viewModel(factory = SettingsViewModel.Factory),
) {
    val readingSettings by viewModel.readingSettings.collectAsStateWithLifecycle()
    val aiSettings by viewModel.aiSettings.collectAsStateWithLifecycle()
    val preferChinese by viewModel.preferChineseFirst.collectAsStateWithLifecycle()
    val dictionaryCount by viewModel.dictionaryCount.collectAsStateWithLifecycle()
    val dictionaryImporting by viewModel.dictionaryImporting.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()

    val snackbarHostState = remember { SnackbarHostState() }

    // SAF：选择 .csv / .json 词典；多数会被标为通用类型，最终按扩展名校验。
    val dictionaryLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
        onResult = { uri -> viewModel.onDictionaryFilePicked(uri) },
    )

    LaunchedEffect(message) {
        message?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.consumeMessage()
        }
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text("设置") }) },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // 阅读设置（复用 Reader 的设置面板）
            SettingsSection(title = "阅读设置") {
                ReaderSettingsSheet(
                    settings = readingSettings,
                    onChange = { transform -> viewModel.updateReadingSettings(transform) },
                    modifier = Modifier.padding(horizontal = 0.dp),
                )
            }

            // 词典设置
            SettingsSection(title = "词典设置") {
                SwitchRow(
                    title = "优先显示中文释义",
                    subtitle = "点击单词后先展示中文",
                    checked = preferChinese,
                    onCheckedChange = viewModel::setPreferChineseFirst,
                )
                Text(
                    text = "当前词典词条：$dictionaryCount 条（含内置示例 + 已导入）",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(top = 12.dp),
                )
                OutlinedButton(
                    onClick = { dictionaryLauncher.launch(arrayOf("text/*", "application/json", "application/octet-stream")) },
                    enabled = !dictionaryImporting,
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                ) {
                    Text(if (dictionaryImporting) "正在导入…" else "导入词典 CSV / JSON")
                }
                Text(
                    text = "支持 UTF-8 的 .csv（首行表头）或 .json（对象数组）。同名单词会被覆盖更新。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }

            // AI 设置
            SettingsSection(title = "AI 设置") {
                SwitchRow(
                    title = "启用 AI 助手",
                    subtitle = "点单词 / 长按句子时用 AI 解释 · 翻译 · 语法 · 精读（调用你自配的接口）",
                    checked = aiSettings.enabled,
                    onCheckedChange = { enabled -> viewModel.updateAiSettings { it.copy(enabled = enabled) } },
                )
                OutlinedTextField(
                    value = aiSettings.apiKey,
                    onValueChange = { v -> viewModel.updateAiSettings { it.copy(apiKey = v) } },
                    label = { Text("API Key（仅保存在本机）") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                )
                OutlinedTextField(
                    value = aiSettings.baseUrl,
                    onValueChange = { v -> viewModel.updateAiSettings { it.copy(baseUrl = v) } },
                    label = { Text("Base URL") },
                    placeholder = { Text(AiSettings.DEFAULT_BASE_URL) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                )
                OutlinedTextField(
                    value = aiSettings.model,
                    onValueChange = { v -> viewModel.updateAiSettings { it.copy(model = v) } },
                    label = { Text("Model") },
                    placeholder = { Text("${AiSettings.DEFAULT_MODEL}（或 deepseek-v4-pro）") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                )
                OutlinedTextField(
                    value = aiSettings.provider,
                    onValueChange = { v -> viewModel.updateAiSettings { it.copy(provider = v) } },
                    label = { Text("Provider（备注用，如 deepseek）") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                )
                Text(
                    text = "默认接 DeepSeek（OpenAI 兼容）。Base URL 留空即用默认，也可填完整接口地址。Key 仅存本机；分析时会带 Key 把所选文本发往该服务。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp),
                )

                // 提示词（可自定义）
                Text(
                    text = "提示词（可自定义）",
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.padding(top = 16.dp),
                )
                Text(
                    text = "占位符：{{text}} = 点选的词/句/段；{{context}} = 所在句子或段落。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 2.dp),
                )
                PromptField("系统提示词", aiSettings.systemPrompt) { v -> viewModel.updateAiSettings { it.copy(systemPrompt = v) } }
                PromptField("解释词义", aiSettings.promptExplainWord) { v -> viewModel.updateAiSettings { it.copy(promptExplainWord = v) } }
                PromptField("翻译句子", aiSettings.promptTranslate) { v -> viewModel.updateAiSettings { it.copy(promptTranslate = v) } }
                PromptField("语法分析", aiSettings.promptGrammar) { v -> viewModel.updateAiSettings { it.copy(promptGrammar = v) } }
                PromptField("长难句拆解", aiSettings.promptBreakdown) { v -> viewModel.updateAiSettings { it.copy(promptBreakdown = v) } }
                PromptField("精读段落", aiSettings.promptCloseReading) { v -> viewModel.updateAiSettings { it.copy(promptCloseReading = v) } }
                OutlinedButton(
                    onClick = {
                        viewModel.updateAiSettings {
                            it.copy(
                                systemPrompt = AiSettings.DEFAULT_SYSTEM_PROMPT,
                                promptExplainWord = AiSettings.DEFAULT_EXPLAIN_WORD,
                                promptTranslate = AiSettings.DEFAULT_TRANSLATE,
                                promptGrammar = AiSettings.DEFAULT_GRAMMAR,
                                promptBreakdown = AiSettings.DEFAULT_BREAKDOWN,
                                promptCloseReading = AiSettings.DEFAULT_CLOSE_READING,
                            )
                        }
                    },
                    modifier = Modifier.padding(top = 8.dp),
                ) { Text("恢复默认提示词") }
            }

            // 导出设置
            SettingsSection(title = "导出设置") {
                DisabledRow(
                    title = "导出生词到 Anki TSV",
                    subtitle = "即将推出",
                )
            }
        }
    }
}

@Composable
private fun SettingsSection(title: String, content: @Composable () -> Unit) {
    Column {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(bottom = 8.dp),
        )
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) { content() }
        }
    }
}

@Composable
private fun SwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(text = title, style = MaterialTheme.typography.bodyLarge)
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun PromptField(label: String, value: String, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        minLines = 2,
        maxLines = 8,
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
    )
}

@Composable
private fun DisabledRow(title: String, subtitle: String) {
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = subtitle,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
