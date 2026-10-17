// SPDX-FileCopyrightText: 2026 WikG and LingWrite contributors
// SPDX-License-Identifier: AGPL-3.0-only
package com.lingwrite.app.data.db

import androidx.room.*
import kotlinx.coroutines.flow.Flow

/**
 * 作者手动/粘贴录入的平台追读数。同一章可保留多次快照，界面取最新。
 * reads / completion / favorites / comments 的 -1 表示「平台没给这个数」，不是 0。
 */
@Entity(
    tableName = "read_metrics",
    foreignKeys = [ForeignKey(entity = BookEntity::class, parentColumns = ["id"], childColumns = ["bookId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("bookId", "chapterNumber", "recordedAt")],
)
data class ReadMetricEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val bookId: Long,
    val chapterNumber: Int,
    val platform: String = "",
    val recordedAt: Long = System.currentTimeMillis(),
    val reads: Int = -1,
    /** 完读率百分数 0–100；-1 = 未知。 */
    val completion: Int = -1,
    val favorites: Int = -1,
    val comments: Int = -1,
    val note: String = "",
)

@Dao
interface ReadStatsDao {
    @Query("SELECT * FROM read_metrics WHERE bookId = :bookId ORDER BY chapterNumber, recordedAt") fun rows(bookId: Long): Flow<List<ReadMetricEntity>>
    @Query("SELECT * FROM read_metrics WHERE bookId = :bookId ORDER BY chapterNumber, recordedAt") suspend fun once(bookId: Long): List<ReadMetricEntity>
    @Insert suspend fun insert(m: ReadMetricEntity): Long
    @Update suspend fun update(m: ReadMetricEntity)
    @Query("DELETE FROM read_metrics WHERE id = :id") suspend fun delete(id: Long)
    @Query("DELETE FROM read_metrics WHERE bookId = :bookId AND chapterNumber = :chapterNumber") suspend fun deleteChapter(bookId: Long, chapterNumber: Int)
    @Query("UPDATE read_metrics SET chapterNumber = :to WHERE bookId = :bookId AND chapterNumber = :from")
    suspend fun remapChapter(bookId: Long, from: Int, to: Int)
}
