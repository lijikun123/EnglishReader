#!/usr/bin/env sh
set -eu

# Local rolling backup. An off-VPS copy must be configured separately before
# storing irreplaceable data, because a VPS loss would remove both the live
# database and these files.
deploy_dir=${KREADER_DEPLOY_DIR:-/opt/kreader-sync/deploy}
backup_dir=${KREADER_BACKUP_DIR:-/var/backups/kreader-sync}
timestamp=$(date -u +%Y%m%dT%H%M%SZ)

mkdir -p "$backup_dir"
chmod 700 "$backup_dir"
cd "$deploy_dir"

set -a
. ./.env
set +a

umask 077
docker compose exec -T postgres pg_dump -U "$POSTGRES_USER" -d "$POSTGRES_DB" -Fc \
  > "$backup_dir/kreader-$timestamp.dump"

find "$backup_dir" -type f -name 'kreader-*.dump' -mtime +13 -delete
