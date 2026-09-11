#!/bin/sh
set -eu

archive=${1:?"usage: import-dictionary.sh normalized.csv.gz source_sha256 [expected_count]"}
source_sha256=${2:?"source SHA-256 is required"}
expected_count=${3:-56992}

case "$source_sha256" in
  *[!0-9a-f]*|'') echo "source SHA-256 must contain lowercase hexadecimal only" >&2; exit 2 ;;
esac
[ "${#source_sha256}" -eq 64 ] || { echo "source SHA-256 must contain 64 characters" >&2; exit 2; }
case "$expected_count" in
  *[!0-9]*|'') echo "expected count must be a positive integer" >&2; exit 2 ;;
esac
[ -f "$archive" ] || { echo "dictionary archive not found: $archive" >&2; exit 2; }
gzip -t "$archive"

script_dir=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
cd "$script_dir"
container_file=/tmp/kreader_dictionary_import.csv

cleanup() {
  docker compose exec -T postgres rm -f "$container_file" >/dev/null 2>&1 || true
}
trap cleanup EXIT INT TERM

gzip -dc "$archive" | docker compose exec -T postgres sh -c 'cat > "$1"' sh "$container_file"

docker compose exec -T postgres sh -c \
  'psql -v ON_ERROR_STOP=1 -v expected_count="$1" -v source_sha256="$2" -U "$POSTGRES_USER" -d "$POSTGRES_DB"' \
  sh "$expected_count" "$source_sha256" <<'SQL'
BEGIN;
TRUNCATE TABLE dictionary_entries;
\copy dictionary_entries(word, lemma, phonetic, part_of_speech, chinese_meaning, english_definition, example_sentence) FROM '/tmp/kreader_dictionary_import.csv' WITH (FORMAT csv, HEADER true, NULL '\N')
SELECT 1 / CASE WHEN COUNT(*) = :expected_count THEN 1 ELSE 0 END AS verified_count
FROM dictionary_entries;
INSERT INTO dictionary_metadata(key, value)
VALUES ('source_sha256', :'source_sha256'), ('entry_count', :'expected_count')
ON CONFLICT (key) DO UPDATE SET value = EXCLUDED.value;
COMMIT;
ANALYZE dictionary_entries;
SQL

docker compose exec -T postgres sh -c \
  'psql -At -U "$POSTGRES_USER" -d "$POSTGRES_DB" -c "SELECT COUNT(*) FROM dictionary_entries"'
