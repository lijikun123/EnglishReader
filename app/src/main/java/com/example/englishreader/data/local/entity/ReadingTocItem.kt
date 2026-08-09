package com.example.englishreader.data.local.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 电子书自带目录项（来自 EPUB 的 nav.xhtml / toc.ncx）。
 *
 * 与 [ReadingChapter] 的关系：目录是「导航层」，[chapterIndex] 指向要跳转到的 spine 章节；
 * 多个目录项可能指向同一章（章内分节，fragment 精确定位留待后续）。
 * 采用「扁平列表 + [level] 缩进」表示层级（足够渲染缩进目录，无需 parentId 树）。
 */
@Entity(
    tableName = "reading_toc_items",
    indices = [Index("readingItemId")],
    foreignKeys = [
        ForeignKey(
            entity = ReadingItem::class,
            parentColumns = ["id"],
            childColumns = ["readingItemId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class ReadingTocItem(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val readingItemId: Long,
    /** 跳转目标 spine 章节索引。 */
    val chapterIndex: Int,
    val label: String,
    val href: String,
    /** 目录层级，0 为顶层，用于缩进显示。 */
    val level: Int,
    /** 目录展示顺序。 */
    val orderIndex: Int,
    /** href 的 fragment 在目标章节正文内的段落索引；-1 表示跳到章节开头。 */
    val anchorParagraph: Int = -1,
    val createdAt: Long = System.currentTimeMillis(),
)
