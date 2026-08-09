# KReader（Kyan's Reader · 英文阅读 App）— v0.1.2

面向**中文母语英语学习者**的本地英文阅读 App：舒服地读英文小说 / 外刊，遇到生词随手查中文释义、收藏到生词本，再导出到 Anki 复习。支持分页阅读、触摸/滑动翻页。

> 定位：这不是词典 App，而是**阅读体验优先**的阅读 App。查词、生词本、导出、AI 都是辅助。完全本地运行，不依赖账号或服务器。

- 技术栈：Kotlin · Coroutines/Flow · Jetpack Compose · Material 3 · Navigation Compose · Room · DataStore · MVVM + Repository · 手机/平板自适应（WindowSizeClass）
- `minSdk 24`，`compileSdk / targetSdk 36`，AGP 8.13 / Kotlin 2.2 / Compose BOM 2026.05
- 版本：`0.1.2`（versionCode 2）；applicationId 仍为 `com.example.englishreader`（未改，保留已安装数据）

---

## v0.1 已实现功能（真实可用）

**阅读**
- 导入本地 **TXT / Markdown / EPUB**（无 DRM）文件并入库，书架显示书名、作者、格式、整本进度、上次时间
- EPUB：解析书名/作者、按 spine 分章、XHTML→纯文本（保留段落、去脚本/样式/图片占位）
- EPUB 目录：优先用电子书自带 **EPUB3 `nav.xhtml`** 或 **EPUB2 `toc.ncx`**（按层级缩进、当前章高亮、点击跳转）；无目录时回退到 spine + `h1~h6`
- EPUB **fragment 精确锚点**：目录项 `chapter.xhtml#id` 可跳到章节内对应段落附近，找不到则回退章节开头
- 上一章 / 下一章；阅读进度（含 EPUB 当前章节 + 章节内位置）自动保存，重启恢复
- 阅读设置（字号 / 行距 / 段落间距 / 阅读宽度 / 主题 light·dark·sepia）经 DataStore 实时生效并持久化
- 手机：全屏阅读 + 底部弹窗；平板/宽屏：左正文 + 右词典面板

**查词 / 词典**
- 点击正文英文单词 → 查词弹窗，**优先中文释义** + 音标/词性/英文释义/例句；查不到提示「本地词典暂未收录」
- 内置示例中英文词典；可在「设置 → 词典设置」**导入 CSV / JSON 中英文词典**（导入词条覆盖同名词、优先于内置）
- 查询忽略大小写 + 朴素词形还原回退（ies/es/s/ing/ed）

**生词本 / 导出**
- 收藏生词（带音标/词性/释义/例句/原句/来源书名/章节/备注）
- 生词本一键**导出 Anki TSV**（见下方格式）

---

## 暂未实现（按规划刻意不做）

- ⛔ 真实 AI 接口：阅读器四个按钮（解释词义/翻译/语法/精读）目前是 **Stub 占位**（不联网，弹窗明确标注「占位实现」）；API Key 输入框已就绪、仅本地保存、不上传、不硬编码
- ⛔ PDF / AZW3 / MOBI / KFX 等格式
- ⛔ MDX / StarDict 词典格式
- ⛔ SRS 背单词算法（`familiarity` 字段已预留）
- ⛔ 复杂词典管理（清空/编辑）、EPUB 富渲染（CSS/图片/脚注/公式）、TOC 小节级高亮

---

## 支持的文件格式

| 用途 | 格式 | 入口 |
|---|---|---|
| 阅读内容 | `.txt` `.md` / `.markdown` `.epub`（无 DRM） | 书架右下角「导入文件」 |
| 词典 | `.csv` `.json`（UTF-8） | 设置 → 词典设置 → 导入词典 |
| 导出 | `.tsv`（Anki） | 生词本右上角分享图标 |

---

## 词典 CSV / JSON 格式

字段：`word`（必填）`lemma` `phonetic` `partOfSpeech` `chineseMeaning` `englishDefinition` `exampleSentence`。UTF-8；`word`/`lemma` 存储为小写以忽略大小写；同名 `word` 后导入覆盖先前。

**CSV**（首行为表头，列名忽略大小写匹配；支持双引号包裹含逗号/换行的字段）：
```csv
word,lemma,phonetic,partOfSpeech,chineseMeaning,englishDefinition,exampleSentence
considering,consider,/kənˈsɪdərɪŋ/,prep./conj.,考虑到；鉴于,"used to show a fact is taken into account",Considering the circumstances, he did well.
```

**JSON**（对象数组）：
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

---

## Anki TSV 导出格式

- 每张卡片一行，`Front<TAB>Back`，**无表头**，UTF-8
- 正面：单词；背面：用 HTML `<br>` 连接的若干段
- 字段中的 tab/换行会被清洗成空格，避免 Anki 错列；空字段自动跳过；中文释义为空时回退导入词典，仍为空显示「暂无释义」

背面顺序：`【词性】中文释义` → `English definition: …` → `Example: …` → `原句：…` → `来源：书名 - 章节` → `备注：…`

示例（一行，制表符用 ⇥ 表示）：
```
fortune⇥【n.】财富；运气<br>English definition: chance or luck, especially in the way it affects life<br>Example: She had the good fortune to meet him.<br>来源：Alice's Adventures in Wonderland - Chapter I<br>备注：重点
```
Anki 导入：Import → 分隔符选 Tab、字段映射 Front/Back、勾选「允许 HTML」。

---

## 如何在 Android Studio 运行

1. 用 **Android Studio（Ladybug 或更新）** 打开本目录
2. 首次会自动 Gradle 同步；如提示缺 **SDK Platform 36**，点一下安装
3. 选模拟器或连真机，点 ▶️ Run

命令行（已含 Gradle wrapper）：
```bash
./gradlew :app:assembleDebug
```
> `local.properties`（指向本机 SDK）是机器相关文件，已被 git 忽略，由 Android Studio 自动生成。

---

## 如何生成 / 安装 APK

**Debug APK（本阶段交付物）**
```bash
./gradlew :app:assembleDebug
# 产物：app/build/outputs/apk/debug/app-debug.apk
```
安装到手机：
- USB：手机开「开发者选项 → USB 调试」，`adb install -r app/build/outputs/apk/debug/app-debug.apk`
- 或把 `app-debug.apk` 传到手机，文件管理器点击安装（需允许「安装未知来源应用」）

**Release / 签名 APK（后续上架时再做，本阶段不强制）**
1. `keytool -genkey -v -keystore release.jks -keyalg RSA -keysize 2048 -validity 10000 -alias er`
2. 在 `app/build.gradle.kts` 加 `signingConfigs { release { ... } }` 并在 `buildTypes.release` 引用；建议开启 `isMinifyEnabled = true`
3. `./gradlew :app:assembleRelease` → `app-release.apk`
> debug APK 已可在任意手机安装自测，无需签名配置。

---

## 数据库（Room，version 4）

| 表 | 用途 |
|---|---|
| `reading_items` | 书架（标题/作者/格式/进度/当前章节/上次位置） |
| `reading_chapters` | EPUB 章节正文（外键级联） |
| `reading_toc_items` | EPUB 目录项（含层级 + fragment 段落锚点） |
| `vocabulary_items` | 收藏生词（含来源书名/章节/原句/备注） |
| `dictionary_entries` | 中英文词典（内置示例 + 导入） |
| `lookup_history` | 查词历史 |
| `ai_analysis_cache` | AI 分析缓存（配合 Stub） |

> 注意：开发期 schema 仍用破坏式迁移；**升级安装若数据库版本变化会清空本地库一次**（示例数据会重新写入，需重新导入书/词典）。v0.1 之后若要保数据需改为正式迁移。

---

## 已知限制

- EPUB 仅支持**无 DRM**、UTF-8；不渲染 CSS/图片/脚注；图片页会被跳过
- 部分 EPUB（如 Gutenberg）一个文件含多章 + 自动生成的目录标签可能较"糙"；fragment 为段落级近似，非像素级
- 词典/书籍整文件读入内存解析，适合常见体量（几千~几万条 / 单本小说）
- 大小写忽略仅针对 ASCII 英文词；词形还原是朴素规则
- AI 四按钮为占位；查词依赖已导入/内置词典，未收录的词只显示提示
- 升级若改 DB 版本会重置本地库一次（见上）

---

## 真实 / 占位 一览

| 功能 | 状态 |
|---|---|
| TXT / Markdown / EPUB 导入与阅读 | ✅ 真实 |
| EPUB 目录（nav/ncx）+ fragment 锚点 + 章节导航 | ✅ 真实 |
| 阅读设置 + 持久化 + 进度恢复 | ✅ 真实 |
| 点词查词（内置 + CSV/JSON 导入词典） | ✅ 真实 |
| 收藏生词 + Anki TSV 导出 | ✅ 真实 |
| AI 四按钮（解释/翻译/语法/精读） | 🟡 Stub 占位（不联网，弹窗已标注） |
| PDF / MOBI / AZW3 / MDX / StarDict / SRS | ⛔ 未做 |
