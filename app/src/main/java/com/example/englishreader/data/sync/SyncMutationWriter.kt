package com.example.englishreader.data.sync

/** Called by the local reading repository after a durable local change. */
interface SyncMutationWriter {
    suspend fun onBookImported(localReadingItemId: Long)
    suspend fun onProgressChanged(localReadingItemId: Long)
    suspend fun onBookDeleted(localReadingItemId: Long)
}
