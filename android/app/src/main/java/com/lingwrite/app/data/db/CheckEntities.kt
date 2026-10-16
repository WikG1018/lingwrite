// SPDX-FileCopyrightText: 2026 WikG and LingWrite contributors
// SPDX-License-Identifier: AGPL-3.0-only
package com.lingwrite.app.data.db

import androidx.room.*
import kotlinx.coroutines.flow.Flow

/** 一致性检查结果（候选）。status：open / intended（故意为之，不再提醒）/ ignored（本次忽略）/ fixed */
@Entity(tableName = "issues", foreignKeys = [ForeignKey(entity = ChapterEntity::class, parentColumns = ["id"], childColumns = ["chapterId"], onDelete = ForeignKey.CASCADE)], indices = [Index("bookId"), Index("chapterId")])
data class IssueEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val bookId: Long,
    val chapterId: Long,
    val number: Int,
    /** persona / power / time / relation / other */
    val kind: String,
    val quote: String,
    val start: Int = -1,
    /** 设定依据或前文片段 */
    val basis: String,
    val explain: String,
    val status: String = "open",
    val note: String = "",
    val createdAt: Long = System.currentTimeMillis(),
    /** A6 给改法：缓存的改法 JSON（[{"type","before","after","why"}]） */
    val fix: String = "",
)

/** 伏笔台账。status：open / paid / dropped；pending = AI 建议待确认。 */
@Entity(tableName = "foreshadows", foreignKeys = [ForeignKey(entity = BookEntity::class, parentColumns = ["id"], childColumns = ["bookId"], onDelete = ForeignKey.CASCADE)], indices = [Index("bookId")])
data class ForeshadowEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val bookId: Long,
    val content: String,
    val chapterId: Long? = null,
    val plantNumber: Int = 0,
    val quote: String = "",
    val quoteStart: Int = -1,
    val payoff: Int? = null,
    val status: String = "open",
    val cardId: Long? = null,
    val pending: Boolean = false,
    val paidAt: Int? = null,
    val createdAt: Long = System.currentTimeMillis(),
    /** A8 回收建议缓存 JSON（[{"chapter","how","setup"}]） */
    val payoffIdeas: String = "",
)

@Dao
interface CheckDao {
    @Query("SELECT * FROM issues WHERE bookId = :bookId ORDER BY number DESC, id") fun issues(bookId: Long): Flow<List<IssueEntity>>
    @Query("SELECT * FROM issues WHERE chapterId = :chapterId ORDER BY start") fun chapterIssues(chapterId: Long): Flow<List<IssueEntity>>
    @Query("SELECT * FROM issues WHERE id = :id") suspend fun issue(id: Long): IssueEntity?
    @Query("SELECT * FROM issues WHERE bookId = :bookId AND status = 'intended'") suspend fun intended(bookId: Long): List<IssueEntity>
    @Query("DELETE FROM issues WHERE chapterId = :chapterId AND status IN ('open','ignored')") suspend fun clearOpen(chapterId: Long)
    @Insert suspend fun insertIssue(i: IssueEntity): Long
    @Update suspend fun updateIssue(i: IssueEntity)

    @Query("SELECT * FROM foreshadows WHERE bookId = :bookId ORDER BY pending DESC, status, COALESCE(payoff, 99999), plantNumber") fun foreshadows(bookId: Long): Flow<List<ForeshadowEntity>>
    @Query("SELECT * FROM foreshadows WHERE bookId = :bookId AND pending = 0") suspend fun foreshadowsOnce(bookId: Long): List<ForeshadowEntity>
    @Query("SELECT * FROM foreshadows WHERE bookId = :bookId") suspend fun foreshadowsInBook(bookId: Long): List<ForeshadowEntity>
    @Query("SELECT * FROM foreshadows WHERE pending = 0 AND status = 'open'") suspend fun allOpen(): List<ForeshadowEntity>
    @Insert suspend fun insertForeshadow(f: ForeshadowEntity): Long
    @Update suspend fun updateForeshadow(f: ForeshadowEntity)
    @Query("DELETE FROM foreshadows WHERE id = :id") suspend fun deleteForeshadow(id: Long)
    @Query("SELECT * FROM foreshadows WHERE id = :id") suspend fun foreshadow(id: Long): ForeshadowEntity?
}
