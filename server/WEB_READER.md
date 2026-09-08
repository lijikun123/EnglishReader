# KReader 网页阅读器

网页版随现有同步服务提供，Android App 0.1.21、applicationId、Gradle 配置、数据库结构和原有 API 均保持不变。

## 使用

更新服务端后访问 `https://your-domain/kreader-sync/web/`，本机开发访问 `http://localhost:8080/web/`。使用 **App 中同一个同步账号**登录。先在 App 中完成一次同步；只在手机本地、尚未同步的书籍不会出现在网页书架。没有账号时仍使用 App 原有注册流程。

支持云端已有 TXT、Markdown、无 DRM EPUB 的纯文本正文、EPUB 目录和段落锚点，可翻页、滚动、切换章节，调整字号、行距、宽度和浅色/护眼/深色主题；配置服务端 AI 后还可显示双语段落和词组讲解。

不提供上传、导入、下载文件、删除书籍、注册、生词本或词典功能。打开书籍会通过已有接口加载正文，不会生成下载文件或导出入口。Markdown 和 EPUB 按 App 已解析的文本展示，不执行书籍中的 HTML。

## 双语与词组

- “双语”按段落显示自然中文译文；“词组”识别熟词僻义、固定搭配、学术语块和句型，点击正文中的加粗标记查看中文讲解。
- 浏览器只调用同源、需要登录的 `/v1/ai/*` 接口。AI Key 由 VPS 读取，不会发送给浏览器或写入 GitHub。
- 服务端复用 App 的精读提示词，默认调用 `https://api.deepseek.com/chat/completions` 和 `deepseek-v4-flash`；Base URL 与模型都可通过环境变量替换为其他兼容服务。
- 译文和词组按账号、模型、书籍正文版本、章节及段落缓存在当前浏览器的 IndexedDB。刷新和重开后复用缓存；App 现有本地缓存不会上传，因此网页不会直接复用手机上已经生成的结果。
- 同一时刻最多发出 2 个上游请求，服务端默认每个账号每分钟最多 60 个 AI 请求。失败结果不缓存。
- 发送给 AI 服务的内容仅是当前需要处理的英文段落。开启功能表示允许将这些段落发送给 VPS 配置的 AI 提供商。
- 中文译文使用独立 DOM 展示，阅读位置始终按英文原文的 UTF-16 偏移计算；切换双语、词组或排版不会产生进度写入。

在 VPS 的 `deploy/.env` 中配置：

```dotenv
KREADER_AI_API_KEY=你的_API_Key
KREADER_AI_BASE_URL=https://api.deepseek.com
KREADER_AI_MODEL=deepseek-v4-flash
KREADER_MAX_AI_INPUT_CHARS=6000
KREADER_MAX_AI_REQUESTS_PER_MINUTE=60
```

`.env` 应保持权限 `600` 且绝不能提交到 Git。没有配置 Key 时，AI 状态接口会报告禁用，阅读、书架和进度同步不受影响。

## 进度同步

- 只发送 `progress.upsert`，不发送书籍创建、内容上传或删除操作。
- 沿用 `chapterIndex`、`charOffset`、`chapterProgress`、`bookProgress`。与 Android `ReaderText.kt` 相同：按空行拆段、去除段首尾空白、用两个换行连接，再按 UTF-16 字符偏移定位；整书进度使用章节等权规则。
- 阅读操作先写入 IndexedDB，约 700 ms 后同步；可见页面每 15 秒检查一次，联网和重新切回网页也会同步。
- 同步先拉取书架/删除记录，再推送进度并拉回服务端结果。冲突仍由现有服务端按操作时间和设备 ID 决定，不能把“接受请求”误当作“本机位置胜出”。
- 网络失败保留原 mutation UUID 重试；成功拉回服务端结果才清除本地队列。同步期间继续阅读不会被旧请求的确认覆盖。
- 阅读时收到其他设备的新位置，会提示“接着读”或“留在此处”。
- App 端同步时机保持原样，可能需要切回 App 或在设置里点“立即同步”。网页版不能改变旧 App 的后台调度。
- 当前页面已加载的书籍可在断网后继续阅读；不提供整站离线缓存，断网重载网页或打开未加载书籍仍需要网络。
- **旧 App 双语模式限制**：App 用包含本机译文的显示文本保存字符偏移，译文不参与同步。网页版可以接续章节，但不能保证双语显示位置精确对齐英文字符；纯英文模式按相同规则对齐。本次为保留 App 原样，没有改写其进度模型。

## 登录与存储

正文仅保存在页面内存。书架、同步游标、待同步进度、AI 译文和词组缓存按“服务地址 + 用户 ID”隔离保存在 IndexedDB；退出后待同步进度仍保留，重新登录同一账号会继续同步，其他账号不会读取它。

为支持重新打开网页和多标签页，现有 API 的 access/refresh token 保存在当前站点 localStorage；不保存密码。刷新 token 由 Web Locks 串行处理。显式退出会撤销当前 refresh token 并清除本地登录凭据；登录过期则要求重新登录。公用电脑使用后请退出登录。

网页与 API 同源，地址从网页路径推导，不允许通过查询参数改成第三方地址。使用 HTTPS 和支持 Web Crypto / IndexedDB / Web Locks 的现代浏览器（localhost 可使用 HTTP）。

只公开列出的网页静态资源，附带 CSP、禁止嵌入、禁止 MIME 嗅探和不发送 referrer 的响应头。正文、书名均使用 textContent 渲染；不依赖第三方 CDN。

## 部署到已有 VPS

新增的 `src/main/resources/web/` 随原有 Gradle `installDist` 和 Docker 镜像自动打包，不需要 Node.js、前端容器或 CORS 配置。原来的 `/kreader-sync/` 反代会同时覆盖网页路径，App 的同步地址不用改。

在 VPS 更新代码后，在 **原来的部署目录**执行：

```sh
cd /你的实际项目路径/EnglishReader/server/deploy
sh deploy-web-reader.sh
```

脚本先为当前 API 镜像保留回滚标签，构建成功后替换 API 容器，检查 `/healthz` 和 `/web/`。不启动/替换/移除 PostgreSQL，不改 `.env`，不重启 Nginx/Caddy。API 容器切换会有短暂重连，App 原有队列会重试。

也可手动执行：

```sh
docker compose build api
docker compose up -d --no-deps api
curl --fail http://127.0.0.1:18080/healthz
curl --fail -o /dev/null http://127.0.0.1:18080/web/
```

本次不需要数据库迁移，请勿执行 `docker compose down -v`。如 VPS 只保留了 `server/`，更新对应的服务端文件，继续沿用原来的 `deploy/.env` 和数据卷。

## 开发验证

服务端和资源路由（JDK 17+，不需要 Android SDK）：

```sh
./gradlew -p server test installDist
```

客户端同步/兼容性（Node 22+，无 npm 依赖）：

```sh
node --test server/web-tests/*.test.mjs
```

浏览器流程使用本地假同步服务和测试书籍，不会登录或写入线上账号。如环境已安装 Playwright 和 Chrome：

```sh
node server/web-tests/browser.smoke.mjs
```

`PLAYWRIGHT_MODULE` 可指定已安装的 Playwright 模块文件 URL；`PLAYWRIGHT_CHANNEL` 默认为 `chrome`。此测试工具不参与生产依赖或启动。
