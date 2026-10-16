// SPDX-FileCopyrightText: 2026 WikG and LingWrite contributors
// SPDX-License-Identifier: AGPL-3.0-only
package com.lingwrite.app.data.db

import androidx.room.*
import kotlinx.coroutines.flow.Flow

/**
 * AI 设定整理建议（A2，ai-features §2）：
 * kind：new_card / state / alias / relation；status：pending / adopted / ignored。
 * payload 是 JSON：新卡（type/name/fields）、状态变化（cardId/field/value）、别名（cardId/alias）、关系（cardId/target/label）。
 */
@Entity(
    tableName = "lore_suggestions",
    foreignKeys = [ForeignKey(entity = BookEntity::class, parentColumns = ["id"], childColumns = ["bookId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("bookId", "status")],
)
data class LoreSuggestionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val bookId: Long,
    val chapterId: Long,
    val number: Int,
    /** new_card / state / alias / relation */
    val kind: String,
    val cardId: Long? = null,
    val payload: String,
    val quote: String = "",
    val status: String = "pending",
    val createdAt: Long = System.currentTimeMillis(),
)

@Dao
interface LoreSuggestDao {
    @Query("SELECT * FROM lore_suggestions WHERE bookId = :bookId AND status = 'pending' ORDER BY createdAt")
    fun pending(bookId: Long): Flow<List<LoreSuggestionEntity>>

    @Query("SELECT * FROM lore_suggestions WHERE bookId = :bookId ORDER BY createdAt DESC")
    fun all(bookId: Long): Flow<List<LoreSuggestionEntity>>

    @Query("SELECT * FROM lore_suggestions WHERE id = :id")
    suspend fun get(id: Long): LoreSuggestionEntity?

    @Insert
    suspend fun insert(items: List<LoreSuggestionEntity>)

    @Query("UPDATE lore_suggestions SET status = :status WHERE id = :id")
    suspend fun setStatus(id: Long, status: String)

    @Query("DELETE FROM lore_suggestions WHERE bookId = :bookId AND status = 'ignored'")
    suspend fun clearIgnored(bookId: Long)
}
