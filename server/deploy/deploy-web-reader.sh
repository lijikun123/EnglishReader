#!/usr/bin/env sh
# Replace only the existing API container; keep its current image for rollback.
set -eu
deploy_dir=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
cd "$deploy_dir"
test -f .env || { echo "Missing deploy/.env. Use your existing deployment directory." >&2; exit 1; }
command -v docker >/dev/null
command -v curl >/dev/null
docker compose version >/dev/null
previous_image=$(docker compose images -q api)
test -n "$previous_image" || { echo "No existing API image. Follow README.md for first deployment." >&2; exit 1; }
backup_image="kreader-sync-api:before-web-$(date -u +%Y%m%dT%H%M%SZ)"
docker image tag "$previous_image" "$backup_image"
echo "Rollback image: $backup_image"
# Build failure leaves the running service untouched.
docker compose build api
docker compose up -d --no-deps api
attempt=0
while [ "$attempt" -lt 30 ]; do
    if curl --fail --silent --max-time 3 http://127.0.0.1:18080/healthz >/dev/null &&
       curl --fail --silent --max-time 3 http://127.0.0.1:18080/web/ >/dev/null; then
        echo "API and reader ready. Open your sync URL followed by /web/."
        echo "Previous API image retained: $backup_image"
        exit 0
    fi
    attempt=$((attempt + 1))
    sleep 2
done
echo "Readiness failed. Inspect: docker compose logs --tail=100 api" >&2
echo "Rollback for the standard Compose project name kreader-sync:" >&2
echo "  docker image tag $backup_image kreader-sync-api:latest" >&2
echo "  docker compose up -d --no-deps --force-recreate api" >&2
exit 1
