// SPDX-FileCopyrightText: 2026 WikG and LingWrite contributors
// SPDX-License-Identifier: AGPL-3.0-only
package com.lingwrite.app.data.db

import androidx.room.*
import kotlinx.coroutines.flow.Flow

/** 每日写作字数（保存正文时累加净增字数）。 */
@Entity(tableName = "word_days", primaryKeys = ["day", "bookId"])
data class WordDayEntity(val day: String, val bookId: Long, val words: Int)

/** 灵感速记 / 随手问。kind：idea / ask */
@Entity(tableName = "ideas")
data class IdeaEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val text: String,
    val kind: String = "idea",
    val answer: String = "",
    val bookId: Long? = null,
    /** 已转成：card / foreshadow */
    val usedAs: String = "",
    val pinned: Boolean = false,
    val createdAt: Long = System.currentTimeMillis(),
    /** 编辑器随手问：所属章节（A1） */
    val chapterId: Long? = null,
    /** A14 灵感扩展：3 个方向的 JSON 缓存 [{"title","idea"}] */
    val expansions: String = "[]",
)

@Dao
interface StatsDao {
    @Query("SELECT * FROM call_logs WHERE time >= :from AND time < :to AND task != 'test' ORDER BY time") fun logsBetween(from: Long, to: Long): Flow<List<CallLogEntity>>
    @Query("SELECT * FROM call_logs WHERE bookId = :bookId AND task != 'test' ORDER BY time DESC") fun bookLogs(bookId: Long): Flow<List<CallLogEntity>>
    @Query("SELECT * FROM word_days WHERE day >= :from ORDER BY day") fun wordDays(from: String): Flow<List<WordDayEntity>>
    @Query("SELECT * FROM word_days WHERE bookId = :bookId ORDER BY day") fun bookWordDays(bookId: Long): Flow<List<WordDayEntity>>
    @Query("SELECT * FROM word_days WHERE day = :day AND bookId = :bookId") suspend fun wordDay(day: String, bookId: Long): WordDayEntity?
    @Upsert suspend fun upsertWordDay(w: WordDayEntity)

    @Query("SELECT * FROM ideas ORDER BY pinned DESC, createdAt DESC") fun ideas(): Flow<List<IdeaEntity>>
    /** 编辑器随手问的问答历史（A1）。 */
    @Query("SELECT * FROM ideas WHERE chapterId = :chapterId AND kind = 'ask' ORDER BY createdAt") fun askHistory(chapterId: Long): Flow<List<IdeaEntity>>
    @Insert suspend fun insertIdea(i: IdeaEntity): Long
    @Update suspend fun updateIdea(i: IdeaEntity)
    @Query("DELETE FROM ideas WHERE id = :id") suspend fun deleteIdea(id: Long)
}
