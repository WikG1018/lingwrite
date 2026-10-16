// SPDX-FileCopyrightText: 2026 WikG and LingWrite contributors
// SPDX-License-Identifier: AGPL-3.0-only
package com.lingwrite.app.data.db

import androidx.room.*
import kotlinx.coroutines.flow.Flow

/**
 * A12 拆文分析结果：只存结构摘要、节奏曲线和抽象模板，**绝不保存原文**。
 * chapters 是 JSON [{"n":1,"event":"","hook":"","cool":"","cliff":"","intensity":3,"pattern":""}]，
 * curve 是 JSON [1-5]，template 是 JSON [{"chapter":1,"pattern":""}]，notes 是 JSON ["…"]。
 */
@Entity(tableName = "breakdowns")
data class BreakdownEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val bookId: Long? = null,
    val name: String,
    val chapters: String,
    val curve: String,
    val template: String,
    val notes: String = "[]",
    val createdAt: Long = System.currentTimeMillis(),
)

@Dao
interface BreakdownDao {
    @Query("SELECT * FROM breakdowns ORDER BY createdAt DESC")
    fun all(): Flow<List<BreakdownEntity>>

    @Query("SELECT * FROM breakdowns WHERE bookId = :bookId ORDER BY createdAt DESC")
    fun ofBook(bookId: Long): Flow<List<BreakdownEntity>>

    @Query("SELECT * FROM breakdowns WHERE id = :id") suspend fun get(id: Long): BreakdownEntity?

    @Insert suspend fun insert(b: BreakdownEntity): Long

    @Query("DELETE FROM breakdowns WHERE id = :id") suspend fun delete(id: Long)
}

/**
 * A16 读者反馈报告：persona 是画像 id，result 是 JSON（星级/一句话/弃书点/期待/建议）。
 * contentHash 用来对比同一章改稿前后的星级变化。
 */
@Entity(tableName = "reader_reports", indices = [Index("bookId")])
data class ReaderReportEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val bookId: Long,
    val fromChapter: Int,
    val toChapter: Int,
    val persona: String,
    val result: String,
    val contentHash: String,
    val createdAt: Long = System.currentTimeMillis(),
)

@Dao
interface ReaderReportDao {
    @Query("SELECT * FROM reader_reports WHERE bookId = :bookId ORDER BY createdAt DESC")
    fun ofBook(bookId: Long): Flow<List<ReaderReportEntity>>

    @Query("SELECT * FROM reader_reports WHERE bookId = :bookId")
    suspend fun once(bookId: Long): List<ReaderReportEntity>

    @Query("UPDATE reader_reports SET fromChapter = :fromChapter, toChapter = :toChapter WHERE id = :id")
    suspend fun updateRange(id: Long, fromChapter: Int, toChapter: Int)

    @Query("SELECT * FROM reader_reports WHERE bookId = :bookId AND fromChapter <= :to AND toChapter >= :from ORDER BY createdAt DESC")
    fun ofRange(bookId: Long, from: Int, to: Int): Flow<List<ReaderReportEntity>>

    @Insert suspend fun insert(r: ReaderReportEntity): Long

    @Query("DELETE FROM reader_reports WHERE id = :id") suspend fun delete(id: Long)
}
