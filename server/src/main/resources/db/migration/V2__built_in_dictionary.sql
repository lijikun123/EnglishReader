CREATE TABLE dictionary_entries (
    word TEXT PRIMARY KEY,
    lemma TEXT NOT NULL,
    phonetic TEXT NOT NULL DEFAULT '',
    part_of_speech TEXT NOT NULL DEFAULT '',
    chinese_meaning TEXT NOT NULL DEFAULT '',
    english_definition TEXT NOT NULL DEFAULT '',
    example_sentence TEXT NOT NULL DEFAULT ''
);

CREATE INDEX dictionary_entries_lemma_idx ON dictionary_entries(lemma);

CREATE TABLE dictionary_metadata (
    key TEXT PRIMARY KEY,
    value TEXT NOT NULL
);
