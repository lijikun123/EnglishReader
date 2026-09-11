#!/usr/bin/env python3
"""Build KReader's private Android and PostgreSQL dictionary artifacts.

The input CSV is deliberately not copied into the repository. This script uses
only Python's standard library so a private dictionary can be staged before an
APK build or a server import without adding a runtime dependency.
"""

from __future__ import annotations

import argparse
import csv
import gzip
import hashlib
import io
import json
import shutil
import sqlite3
import tempfile
from pathlib import Path


FIELDS = (
    "word",
    "lemma",
    "phonetic",
    "partOfSpeech",
    "chineseMeaning",
    "englishDefinition",
    "exampleSentence",
)


def clean_row(row: dict[str, str | None]) -> tuple[str, ...] | None:
    word = (row.get("word") or "").strip().lower()
    if not word:
        return None
    lemma = (row.get("lemma") or "").strip().lower() or word
    return (
        word,
        lemma,
        (row.get("phonetic") or "").strip(),
        (row.get("partOfSpeech") or "").strip(),
        (row.get("chineseMeaning") or "").strip(),
        (row.get("englishDefinition") or "").strip(),
        (row.get("exampleSentence") or "").strip(),
    )


def source_sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as source:
        for chunk in iter(lambda: source.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def rows_from(path: Path):
    with path.open("r", encoding="utf-8-sig", newline="") as source:
        reader = csv.DictReader(source)
        if reader.fieldnames != list(FIELDS):
            raise ValueError(f"Unexpected CSV header: {reader.fieldnames!r}")
        seen: set[str] = set()
        for source_row in reader:
            row = clean_row(source_row)
            if row is None:
                continue
            if row[0] in seen:
                raise ValueError(f"Duplicate normalized word: {row[0]}")
            seen.add(row[0])
            yield row


def build_sqlite(input_path: Path, output_path: Path, digest: str) -> int:
    output_path.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix="kreader-dictionary-") as temporary:
        database_path = Path(temporary) / "kreader_dictionary.sqlite"
        connection = sqlite3.connect(database_path)
        try:
            connection.executescript(
                """
                PRAGMA page_size = 4096;
                PRAGMA journal_mode = DELETE;
                CREATE TABLE dictionary_entries (
                    word TEXT PRIMARY KEY COLLATE NOCASE,
                    lemma TEXT NOT NULL COLLATE NOCASE,
                    phonetic TEXT NOT NULL,
                    part_of_speech TEXT NOT NULL,
                    chinese_meaning TEXT NOT NULL,
                    english_definition TEXT NOT NULL,
                    example_sentence TEXT NOT NULL
                ) WITHOUT ROWID;
                CREATE INDEX dictionary_entries_lemma_idx
                    ON dictionary_entries(lemma COLLATE NOCASE);
                CREATE TABLE dictionary_metadata (
                    key TEXT PRIMARY KEY,
                    value TEXT NOT NULL
                ) WITHOUT ROWID;
                """
            )
            count = 0
            batch: list[tuple[str, ...]] = []
            for row in rows_from(input_path):
                batch.append(row)
                if len(batch) == 1000:
                    connection.executemany(
                        "INSERT INTO dictionary_entries VALUES (?, ?, ?, ?, ?, ?, ?)", batch
                    )
                    count += len(batch)
                    batch.clear()
            if batch:
                connection.executemany(
                    "INSERT INTO dictionary_entries VALUES (?, ?, ?, ?, ?, ?, ?)", batch
                )
                count += len(batch)
            connection.executemany(
                "INSERT INTO dictionary_metadata(key, value) VALUES (?, ?)",
                (("source_sha256", digest), ("entry_count", str(count))),
            )
            connection.commit()
            connection.execute("VACUUM")
        finally:
            connection.close()

        temporary_output = output_path.with_suffix(output_path.suffix + ".tmp")
        with database_path.open("rb") as source, temporary_output.open("wb") as raw_target:
            with gzip.GzipFile(fileobj=raw_target, mode="wb", compresslevel=9, mtime=0) as target:
                shutil.copyfileobj(source, target, length=1024 * 1024)
        temporary_output.replace(output_path)
    return count


def build_postgres_csv(input_path: Path, output_path: Path) -> int:
    output_path.parent.mkdir(parents=True, exist_ok=True)
    temporary_output = output_path.with_suffix(output_path.suffix + ".tmp")
    count = 0
    with temporary_output.open("wb") as raw_target:
        with gzip.GzipFile(fileobj=raw_target, mode="wb", compresslevel=9, mtime=0) as compressed:
            with io.TextIOWrapper(compressed, encoding="utf-8", newline="") as target:
                writer = csv.writer(target, lineterminator="\n")
                writer.writerow(
                    (
                        "word",
                        "lemma",
                        "phonetic",
                        "part_of_speech",
                        "chinese_meaning",
                        "english_definition",
                        "example_sentence",
                    )
                )
                for row in rows_from(input_path):
                    writer.writerow(row)
                    count += 1
    temporary_output.replace(output_path)
    return count


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("input", type=Path)
    parser.add_argument("--android-output", type=Path)
    parser.add_argument("--postgres-output", type=Path)
    args = parser.parse_args()
    if not args.android_output and not args.postgres_output:
        parser.error("at least one output is required")
    digest = source_sha256(args.input)
    counts = []
    if args.android_output:
        counts.append(build_sqlite(args.input, args.android_output, digest))
    if args.postgres_output:
        counts.append(build_postgres_csv(args.input, args.postgres_output))
    if len(set(counts)) > 1:
        raise RuntimeError(f"Output entry counts differ: {counts}")
    print(json.dumps({"sourceSha256": digest, "entries": counts[0]}, indent=2))


if __name__ == "__main__":
    main()
