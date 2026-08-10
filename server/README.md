# KReader sync server

The server is the private, account-scoped synchronization backend for the Android EnglishReader app.

The first release synchronizes accounts, book metadata, parsed-book bundles and reading positions. It intentionally does **not** upload AI API keys, dictionary/translation caches or local display preferences.

## Local development

The module is intentionally separate from the Android Gradle project:

```bash
cd server
../gradlew build
```

Set the `KREADER_*` and database environment variables from `deploy/.env.example` before running it. PostgreSQL is required for all routes except the process starting point.

## Deployment model

`deploy/compose.yaml` starts PostgreSQL and the API. The API binds only to `127.0.0.1:18080` on the VPS, so it is not public before a reverse proxy and TLS are ready.

If the VPS already uses Nginx for other projects, prefer including
`deploy/nginx-kreader-sync.conf` in the existing HTTPS server block. It exposes
only `/kreader-sync/`, leaving other paths and ports alone; the Android base URL
then looks like `https://your-domain/kreader-sync`. PostgreSQL and the API
container port remain private.

For a fresh VPS with no web server on ports 80/443, the optional `edge` profile
can instead run Caddy after DNS points a dedicated domain at the VPS.

## Security model

- Passwords use Argon2id.
- Access tokens are short-lived JWTs; refresh tokens are random, stored only as SHA-256 hashes, and can be revoked per device.
- Every book, bundle and change query is scoped to the authenticated user.
- Sync mutations are idempotent per user and mutation UUID.
- Bundles are gzip-compressed and stored as `bytea` in PostgreSQL for simple encrypted/offsite backup later.
