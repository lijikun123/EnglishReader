CREATE TABLE users (
    id UUID PRIMARY KEY,
    email TEXT NOT NULL UNIQUE,
    password_hash TEXT NOT NULL,
    created_at BIGINT NOT NULL,
    disabled_at BIGINT
);

CREATE TABLE devices (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    name TEXT NOT NULL,
    created_at BIGINT NOT NULL,
    last_seen_at BIGINT NOT NULL
);

CREATE INDEX devices_user_id_idx ON devices(user_id);

CREATE TABLE refresh_tokens (
    token_hash CHAR(64) PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    device_id UUID NOT NULL REFERENCES devices(id) ON DELETE CASCADE,
    expires_at BIGINT NOT NULL,
    created_at BIGINT NOT NULL,
    revoked_at BIGINT
);

CREATE INDEX refresh_tokens_user_device_idx ON refresh_tokens(user_id, device_id);

CREATE TABLE books (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    title TEXT NOT NULL,
    author TEXT NOT NULL DEFAULT '',
    content_type TEXT NOT NULL,
    format TEXT NOT NULL,
    -- SHA-256 / size of the deterministic, uncompressed BookBundleV1 JSON.
    content_sha256 CHAR(64) NOT NULL,
    content_bytes BIGINT NOT NULL,
    content_revision BIGINT NOT NULL,
    revision BIGINT NOT NULL DEFAULT 1,
    occurred_at BIGINT NOT NULL,
    source_device_id UUID NOT NULL,
    bundle_ready BOOLEAN NOT NULL DEFAULT FALSE,
    created_at BIGINT NOT NULL,
    updated_at BIGINT NOT NULL,
    deleted_at BIGINT
);

CREATE INDEX books_user_updated_idx ON books(user_id, updated_at);
-- Prevent two devices independently importing an identical active book from
-- creating duplicate cloud entries. Deleted tombstones do not block re-import.
CREATE UNIQUE INDEX books_user_content_hash_active_uidx
    ON books(user_id, content_sha256) WHERE deleted_at IS NULL;

CREATE TABLE book_bundles (
    book_id UUID PRIMARY KEY REFERENCES books(id) ON DELETE CASCADE,
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    sha256 CHAR(64) NOT NULL,
    raw_bytes BIGINT NOT NULL,
    gzip_bytes BIGINT NOT NULL,
    content_gzip BYTEA NOT NULL,
    updated_at BIGINT NOT NULL
);

CREATE INDEX book_bundles_user_id_idx ON book_bundles(user_id);

CREATE TABLE reading_positions (
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    book_id UUID NOT NULL REFERENCES books(id) ON DELETE CASCADE,
    chapter_index INTEGER NOT NULL,
    char_offset INTEGER NOT NULL,
    chapter_progress DOUBLE PRECISION NOT NULL,
    book_progress DOUBLE PRECISION NOT NULL,
    revision BIGINT NOT NULL DEFAULT 1,
    occurred_at BIGINT NOT NULL,
    source_device_id UUID NOT NULL,
    updated_at BIGINT NOT NULL,
    PRIMARY KEY(user_id, book_id)
);

CREATE INDEX reading_positions_user_updated_idx ON reading_positions(user_id, updated_at);

CREATE TABLE sync_mutations (
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    mutation_id UUID NOT NULL,
    processed_at BIGINT NOT NULL,
    canonical_book_id UUID,
    PRIMARY KEY(user_id, mutation_id)
);

CREATE TABLE sync_changes (
    cursor BIGSERIAL PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    kind TEXT NOT NULL,
    entity_id UUID NOT NULL,
    revision BIGINT NOT NULL,
    payload JSONB NOT NULL,
    occurred_at BIGINT NOT NULL,
    server_updated_at BIGINT NOT NULL
);

CREATE INDEX sync_changes_user_cursor_idx ON sync_changes(user_id, cursor);
