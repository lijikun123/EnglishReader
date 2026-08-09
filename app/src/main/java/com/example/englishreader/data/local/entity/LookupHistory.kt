package com.example.englishreader.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/** 查词历史。每次点击查词时记录一条。 */
@Entity(tableName = "lookup_history")
data class LookupHistory(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val word: String,
    val sourceSentence: String = "",
    val readingItemId: Long? = null,
    val createdAt: Long = System.currentTimeMillis(),
)
