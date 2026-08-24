# KReader（Kyan's Reader · 英文阅读 App）— v0.1.21

面向中文母语英语学习者的 Android 英文阅读器：以阅读体验为先，支持本地导入、查词、AI 辅助、生词本，以及手机与平板之间的自建同步。

> 默认是本地优先：所有阅读操作先写入本机 Room 数据库；已登录同步账号时，书籍与进度会在网络可用后同步到私有服务。

- 技术栈：Kotlin · Coroutines/Flow · Jetpack Compose · Material 3 · Navigation Compose · Room · DataStore · WorkManager · MVVM + Repository
- `minSdk 24`，`compileSdk / targetSdk 36`，Java 17
- 当前版本：`0.1.21`（versionCode 21）
- applicationId：`com.example.englishreader`（为保留已安装用户的数据而未更改）

## 已实现功能

### 阅读与导入

- 导入本地 **TXT / Markdown / EPUB**（仅无 DRM）；书架显示标题、作者、格式、阅读进度与最近阅读时间。
- EPUB 解析 spine 章节、书名/作者和目录；支持 EPUB3 `nav.xhtml`、EPUB2 `toc.ncx`、章节标题回退与 fragment 段落锚点。
- 页内点击/滑动翻页；EPUB 翻到章节边缘会连续进入上一章或下一章，并保留正确阅读位置。
- 阅读设置包括字号、行距、阅读宽度和浅色 / 深色 / 护眼主题；设置与进度均会持久化，重启后恢复。
- 手机使用全屏阅读和底部面板；平板/宽屏使用正文与词典并排布局。

### 查词、AI 与词组

- 点击正文英文单词查询本地词典，展示中文释义、音标、词性、英文释义和例句；支持 CSV / JSON 自定义词典导入与基础词形回退。
- 支持 OpenAI-compatible Chat Completions，默认配置面向 DeepSeek；可进行语境释义、翻译、语法、长难句拆解和精读。
- AI Key、Base URL、模型和提示词由用户在“设置 → AI”自行配置。Key 使用 Android Keystore 本地保护，**不会上传或参与同步**。
- 支持双语段落阅读与 AI 词组识别；译文和词组分析在本机缓存。

### 生词本与 Anki

- 可收藏单词和 AI 识别的词组，保存释义、例句、原句、来源书籍/章节和备注。
- 同一词或词组在同一设备只保留一条规范化记录，避免重复收藏。
- 生词本使用列表布局，支持选择、全选、批量删除和批量分享。
- 通过系统分享面板导出 UTF-8 Anki TSV；关闭分享面板会直接回到生词本，而不会进入文件管理器层级。

### 跨设备同步（测试版）

- Android 手机与平板登录同一账号后，可同步书架、解析后的 TXT / Markdown / 无 DRM EPUB 内容、阅读位置及书籍删除操作。
- 本地操作先进入可重试、幂等的 outbox；联网时自动同步，WorkManager 另有约 15 分钟一次的兜底同步。设置页可手动“立即同步”。
- 同一内容在两台设备重复导入时，按内容 SHA-256 合并为同一云端书籍。
- 进度与书籍变更按最近一次操作时间合并；时间相同时由设备 ID 稳定打破平局。
- 同步读取遇到短暂网络中断会自动重试；手动同步失败也会进入后台重试队列。
- 删除已同步书籍后，其他设备下次同步会从书架移除它；服务端保留删除标记，以便离线设备也能收到这条删除通知。
- **不参与同步**：生词本、词典、AI/翻译/词组缓存、AI Key、主题和排版设置。删除书籍不会删除已收藏的生词，只会清除其本地书籍关联。

## 同步使用说明

1. 在第一台设备的“设置 → 跨设备同步”填写自己的 HTTPS 同步服务地址，并注册账号。
2. 在另一台设备填写相同服务地址，使用同一账号**登录**（不要重复注册）。
3. 首次同步可能需要上传/下载书籍内容；之后阅读、导入和删除都会自动排队同步，也可在设置页点“立即同步”。

服务端默认关闭公开注册（`KREADER_ALLOW_REGISTRATION=false`）。私有部署时，只在创建第一个账号的短时间内开启注册，随后立即关闭。项目内置的默认地址属于项目所有者的私有服务，不应视为公共服务；自行部署时请使用自己的 HTTPS 域名地址。

## 支持的格式

| 用途 | 格式 | 入口 |
|---|---|---|
| 阅读内容 | `.txt`、`.md`、`.markdown`、`.epub`（无 DRM） | 书架右下角“导入文件” |
| 词典 | `.csv`、`.json`（UTF-8） | 设置 → 词典设置 → 导入词典 |
| 生词导出 | `.tsv`（Anki） | 生词本 → 选择 → 分享 |

## 词典 CSV / JSON 格式

字段：`word`（必填）、`lemma`、`phonetic`、`partOfSpeech`、`chineseMeaning`、`englishDefinition`、`exampleSentence`。UTF-8 编码；`word` / `lemma` 会按小写检索；同名词条以后导入者覆盖先前词条。

CSV 首行为表头，列名忽略大小写，支持含逗号/换行的双引号字段：

```csv
word,lemma,phonetic,partOfSpeech,chineseMeaning,englishDefinition,exampleSentence
considering,consider,/kənˈsɪdərɪŋ/,prep./conj.,考虑到；鉴于,"used to show a fact is taken into account",Considering the circumstances, he did well.
```

JSON 为对象数组：

```json
[
  {
    "word": "considering",
    "lemma": "consider",
    "phonetic": "/kənˈsɪdərɪŋ/",
    "partOfSpeech": "prep./conj.",
    "chineseMeaning": "考虑到；鉴于",
    "englishDefinition": "used to show that a particular fact is being taken into account",
    "exampleSentence": "Considering the circumstances, he did well."
  }
]
```

## Anki TSV 导出格式

- 每张卡片一行，`Front<TAB>Back`，无表头、UTF-8。
- 正面为单词或词组；背面使用 HTML `<br>` 连接词性、中文释义、英文释义、例句、原句、来源与备注。
- 字段内的 tab / 换行会被清理，避免 Anki 错列；中文释义为空时会回退词典结果，仍为空则显示“暂无释义”。

Anki 导入时选择 Tab 分隔符，映射 Front / Back，并启用“允许 HTML”。

## 本地开发与 APK

使用 Android Studio（Ladybug 或更新）打开本目录；首次 Gradle 同步时如提示缺少 SDK Platform 36，按提示安装即可。

```bash
# 构建 Debug APK
./gradlew :app:assembleDebug

# 运行单元测试
./gradlew :app:testDebugUnitTest
```

Debug APK 路径：`app/build/outputs/apk/debug/app-debug.apk`。

USB 安装：在手机启用“开发者选项 → USB 调试”后运行：

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

`local.properties`（本机 SDK 路径）、APK、Keystore、部署 `.env` 等本地敏感或构建文件均被 Git 忽略。

## 自建同步服务

服务端在 [`server/`](server/) 下，是与 Android 工程分离的 Kotlin/Ktor + PostgreSQL 项目。部署说明见 [`server/README.md`](server/README.md)。

部署原则：API 仅绑定 VPS 的 loopback 地址，PostgreSQL 不暴露公网；由既有 Nginx 或可选 Caddy 提供 HTTPS 与 `/kreader-sync/` 路径代理。不要将数据库端口或 `.env` 中的密钥提交到 Git。

## 数据库（Room v8）

| 表 | 用途 |
|---|---|
| `reading_items` | 书架、进度、当前章节与上次位置 |
| `reading_chapters` | EPUB 章节正文 |
| `reading_toc_items` | EPUB 目录和 fragment 段落锚点 |
| `vocabulary_items` | 收藏单词/词组与来源信息 |
| `dictionary_entries` | 内置与导入词典 |
| `lookup_history` | 查词历史 |
| `ai_analysis_cache` | AI 分析缓存 |
| `chapter_translations` | AI 段落译文缓存 |
| `chapter_phrases` | AI 词组识别缓存 |
| `sync_books` | 本地书籍 Long ID 与云端 UUID 的同步映射 |
| `sync_outbox` | 可重试、幂等的同步任务队列 |

已有 v4→v8 正式迁移；未覆盖的版本跳变会显式失败，而不会静默清空本地书籍。

## 已知限制

- EPUB 仅支持无 DRM 内容；不渲染原书 CSS、图片、脚注或公式。
- PDF、AZW3、MOBI、KFX、MDX、StarDict 与 SRS 背词算法尚未实现。
- AI 需要用户自己的兼容服务、API Key 和网络；失败结果不会写入缓存。
- 生词本、词典和 AI 缓存目前仅保留在当前设备，尚未跨设备同步。
- 同步服务是私有自建服务；在代理/VPN 环境下，应确保自己的同步域名不会被异常拦截。
