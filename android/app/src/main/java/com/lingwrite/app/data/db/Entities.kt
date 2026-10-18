// SPDX-FileCopyrightText: 2026 WikG and LingWrite contributors
// SPDX-License-Identifier: AGPL-3.0-only
package com.lingwrite.app.data.db

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.ColumnInfo

@Entity(
    tableName = "books",
    foreignKeys = [ForeignKey(entity = SeriesEntity::class, parentColumns = ["id"], childColumns = ["seriesId"], onDelete = ForeignKey.SET_NULL)],
    indices = [Index("seriesId")],
)
data class BookEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val genre: String = "",
    /** draft / outline / serial / done / wizard */
    val status: String = "draft",
    val coverIndex: Int = 0,
    val dailyTarget: Int = 0,
    val blurb: String = "",
    val wizardStep: Int = 0,
    /** 本书写作模式：assist / generate；空 = 跟随全局（MD2） */
    val writeMode: String? = null,
    /** A11 存稿目标（章） */
    val bufferTarget: Int = 5,
    /** A11 标记已发布到第 N 章 */
    val publishedUpTo: Int = 0,
    /** 所属系列；null = 未加入 */
    val seriesId: Long? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
)

@Entity(
    tableName = "volumes",
    foreignKeys = [ForeignKey(entity = BookEntity::class, parentColumns = ["id"], childColumns = ["bookId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("bookId")],
)
data class VolumeEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val bookId: Long,
    val title: String,
    val sort: Int,
)

@Entity(
    tableName = "chapters",
    foreignKeys = [
        ForeignKey(entity = BookEntity::class, parentColumns = ["id"], childColumns = ["bookId"], onDelete = ForeignKey.CASCADE),
        ForeignKey(entity = VolumeEntity::class, parentColumns = ["id"], childColumns = ["volumeId"], onDelete = ForeignKey.CASCADE),
    ],
    indices = [Index("bookId"), Index("volumeId")],
)
data class ChapterEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val bookId: Long,
    val volumeId: Long,
    /** 全书连续章号 */
    val number: Int,
    val title: String,
    val content: String = "",
    val wordCount: Int = 0,
    /** draft / done */
    val state: String = "draft",
    /** AI 段落标记：JSON 数组 [{"s":起,"e":止,"k":"gen|edited"}]，按字符偏移 */
    val aiMarks: String = "[]",
    val updatedAt: Long = System.currentTimeMillis(),
    /** 非零时在章节回收站中，正文与历史保留。 */
    @ColumnInfo(defaultValue = "0") val deletedAt: Long = 0,
)

@Entity(
    tableName = "chapter_versions",
    foreignKeys = [ForeignKey(entity = ChapterEntity::class, parentColumns = ["id"], childColumns = ["chapterId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("chapterId")],
)
data class ChapterVersionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val chapterId: Long,
    val content: String,
    val wordCount: Int,
    /** auto / manual / rollback / ai */
    val kind: String,
    val note: String = "",
    val createdAt: Long = System.currentTimeMillis(),
)

data class BookSummary(
    val id: Long, val title: String, val genre: String, val status: String, val coverIndex: Int,
    val wizardStep: Int, val updatedAt: Long, val words: Int, val chapters: Int, val lastChapter: Int?,
)

@Entity(tableName = "providers")
data class ProviderEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** 预设 id，自定义为 "custom" */
    val presetId: String,
    val name: String,
    /** openai / anthropic */
    val protocol: String,
    val baseUrl: String,
    /** bearer / x-api-key / api-key */
    val auth: String,
    /** payg / sub */
    val access: String = "payg",
    /** 逗号分隔 */
    val models: String,
    val enabled: Boolean = true,
    /** Keystore 加密后的 Key（Base64） */
    val keyCipher: String = "",
    val keyHint: String = "",
    /** 每百万 token 单价（元），JSON {"model":[in,out]} */
    val prices: String = "{}",
    /** ok / fail / 空 */
    val lastTest: String = "",
    val createdAt: Long = System.currentTimeMillis(),
)

/** 调用日志：同时用于 AI 使用记录与调用量统计。 */
@Entity(tableName = "call_logs", indices = [Index("time"), Index("bookId")])
data class CallLogEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val time: Long = System.currentTimeMillis(),
    val bookId: Long? = null,
    val chapterId: Long? = null,
    val provider: String,
    val model: String,
    /** continue / rewrite / expand / polish / deai / dialog / annotate / summary / check / extract / foreshadow / wizard / ask / test / style */
    val task: String,
    val inputTokens: Int = 0,
    val outputTokens: Int = 0,
    /** pending / accepted / edited / rejected / auto / failed */
    val outcome: String = "pending",
    val scope: String = "",
    val error: String = "",
    val estimated: Boolean = false,
    /** 一次生成给出的候选数（A3） */
    val candidates: Int = 1,
    /** 采纳的下标；-1 = 未采纳 */
    val chosen: Int = -1,
)
