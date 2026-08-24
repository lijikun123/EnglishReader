# KReader sync server

KReader 的私有、按账号隔离的 Android 同步后端。它同步账号、书籍元数据、解析后的书籍内容包、阅读位置和书籍删除标记；不会上传 AI API Key、词典/AI 缓存或本地阅读排版设置。

## 同步语义

- 每个用户的数据严格隔离；同一用户重复导入相同内容（SHA-256）会归并为同一云端书籍。
- 客户端 mutation 带 UUID，因此可安全重试而不重复写入。
- 书籍和进度按 `occurredAt` 最新者优先；时间相同时使用设备 ID 作稳定的平局规则。
- 删除使用 tombstone：内容包会移除，但删除事件保留，离线设备上线后仍能从书架删除对应书籍。
- 历史的内容就绪事件若已被后续删除或新内容版本取代，会作为无副作用事件返回，避免新设备下载不存在或过期的内容包后卡住同步游标。

## 本地开发

服务端刻意与 Android Gradle 工程分离：

```bash
cd server
../gradlew build
```

运行前按 `deploy/.env.example` 设置 `KREADER_*` 和数据库变量。除启动入口外，所有 API 路由均需要 PostgreSQL。

## VPS 部署

1. 复制并保护环境文件：

   ```bash
   cd server/deploy
   cp .env.example .env
   chmod 600 .env
   ```

2. 在 `.env` 中替换所有 `CHANGE_ME` 值，使用足够长的随机 PostgreSQL 密码与 JWT secret；设置正确的 issuer、audience 与域名。
3. 启动 API 与 PostgreSQL：

   ```bash
   docker compose up -d --build
   ```

4. 若 VPS 已有 Nginx，使用 `nginx-kreader-sync.conf` 将 HTTPS 域名下的 `/kreader-sync/` 反代到 `127.0.0.1:18080`。API 与 PostgreSQL 都不应直接暴露公网。
5. 仅在 VPS 尚未占用 80 / 443 且 DNS 已指向该 VPS 时，才可使用可选 Caddy edge profile：

   ```bash
   docker compose --profile edge up -d
   ```

`KREADER_ALLOW_REGISTRATION` 默认 `false`。创建第一个所有者账号时短暂开启，完成后立即关闭；不要把公开注册长期暴露在互联网。

## 备份

`deploy/backup-postgres.sh` 会生成 PostgreSQL 自定义格式备份并保留 14 天；`kreader-backup.service` 与 `kreader-backup.timer` 提供每日定时任务模板。启用前请检查 VPS 上的真实部署路径，并另行配置加密的异地备份——同一台 VPS 上的备份无法防御整机丢失。

## 安全模型

- 密码使用 Argon2id。
- Access token 为短期 JWT；refresh token 为随机值，仅保存 SHA-256 哈希，可按设备撤销。
- 每本书、内容包和变更查询均受已认证用户范围限制。
- 书籍内容包 gzip 压缩后以 PostgreSQL `bytea` 存储，便于统一备份。
- Compose 服务日志有大小与数量限制；PostgreSQL、API 与边缘代理按职责分离。
