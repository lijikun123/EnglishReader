package com.example.englishreader.data.local

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import com.example.englishreader.data.local.entity.DictionaryEntry
import java.io.File
import java.io.FileOutputStream
import java.util.zip.GZIPInputStream

/** Read-only dictionary shipped as a private, compressed APK asset. */
class BuiltInDictionaryStore(private val context: Context) {
    @Volatile
    private var opened: SQLiteDatabase? = null

    @Volatile
    private var unavailable = false

    fun count(): Int = database()?.rawQuery(
        "SELECT value FROM dictionary_metadata WHERE key = 'entry_count'",
        null,
    )?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0).toIntOrNull() ?: 0 else 0 } ?: 0

    fun lookup(word: String): List<DictionaryEntry> {
        val db = database() ?: return emptyList()
        return db.rawQuery(
            """
            SELECT word, lemma, phonetic, part_of_speech, chinese_meaning,
                   english_definition, example_sentence
            FROM dictionary_entries
            WHERE word = ? COLLATE NOCASE OR lemma = ? COLLATE NOCASE
            ORDER BY CASE WHEN word = ? COLLATE NOCASE THEN 0 ELSE 1 END, word
            LIMIT 12
            """.trimIndent(),
            arrayOf(word, word, word),
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    add(
                        DictionaryEntry(
                            word = cursor.getString(0),
                            lemma = cursor.getString(1),
                            phonetic = cursor.getString(2),
                            partOfSpeech = cursor.getString(3),
                            chineseMeaning = cursor.getString(4),
                            englishDefinition = cursor.getString(5),
                            exampleSentence = cursor.getString(6),
                        ),
                    )
                }
            }
        }
    }

    @Synchronized
    private fun database(): SQLiteDatabase? {
        opened?.let { return it }
        if (unavailable) return null
        return try {
            val target = File(context.noBackupFilesDir, DATABASE_FILE)
            if (!isCurrent(target)) install(target)
            SQLiteDatabase.openDatabase(
                target.absolutePath,
                null,
                SQLiteDatabase.OPEN_READONLY or SQLiteDatabase.NO_LOCALIZED_COLLATORS,
            ).also { opened = it }
        } catch (_: Exception) {
            unavailable = true
            null
        }
    }

    private fun isCurrent(file: File): Boolean {
        if (!file.isFile) return false
        return try {
            SQLiteDatabase.openDatabase(
                file.absolutePath,
                null,
                SQLiteDatabase.OPEN_READONLY or SQLiteDatabase.NO_LOCALIZED_COLLATORS,
            ).use { db ->
                db.rawQuery(
                    "SELECT value FROM dictionary_metadata WHERE key = 'source_sha256'",
                    null,
                ).use { cursor -> cursor.moveToFirst() && cursor.getString(0) == SOURCE_SHA256 }
            }
        } catch (_: Exception) {
            false
        }
    }

    private fun install(target: File) {
        target.parentFile?.mkdirs()
        val temporary = File(target.parentFile, "$DATABASE_FILE.tmp")
        temporary.delete()
        context.assets.open(ASSET_FILE).use { compressed ->
            GZIPInputStream(compressed).use { input ->
                FileOutputStream(temporary).use { output ->
                    input.copyTo(output, DEFAULT_BUFFER_SIZE)
                    output.fd.sync()
                }
            }
        }
        if (target.exists() && !target.delete()) error("Unable to replace built-in dictionary")
        if (!temporary.renameTo(target)) {
            temporary.delete()
            error("Unable to install built-in dictionary")
        }
        if (!isCurrent(target)) {
            target.delete()
            error("Built-in dictionary verification failed")
        }
    }

    private companion object {
        // A neutral extension prevents Android's asset packager from expanding
        // .gz files and silently changing their packaged name.
        const val ASSET_FILE = "kreader_dictionary.bin"
        const val DATABASE_FILE = "kreader_dictionary.sqlite"
        const val SOURCE_SHA256 = "f89f6b56ae8e13f3fbe98f0e434229215882254cfdda953479848ecd5a68a914"
    }
}
