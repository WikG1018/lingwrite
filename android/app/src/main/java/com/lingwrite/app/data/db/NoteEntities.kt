// SPDX-FileCopyrightText: 2026 WikG and LingWrite contributors
// SPDX-License-Identifier: AGPL-3.0-only
package com.lingwrite.app.data.db

import androidx.room.*
import kotlinx.coroutines.flow.Flow

/**
 * 本章便签（MD3）：辅助模式下 AI 产出与随手问答的落脚点，绝不进正文。
 * kind：direction（给方向）/ beat（节拍）/ stuck（卡文）/ ask（随手问）/ fix（改法）
 */
@Entity(
    tableName = "chapter_notes",
    foreignKeys = [ForeignKey(entity = ChapterEntity::class, parentColumns = ["id"], childColumns = ["chapterId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("chapterId")],
)
data class ChapterNoteEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val chapterId: Long,
    val kind: String,
    val text: String,
    val done: Boolean = false,
    val createdAt: Long = System.currentTimeMillis(),
)

@Dao
interface NoteDao {
    @Query("SELECT * FROM chapter_notes WHERE chapterId = :chapterId ORDER BY done, createdAt DESC")
    fun notes(chapterId: Long): Flow<List<ChapterNoteEntity>>

    @Query("SELECT COUNT(*) FROM chapter_notes WHERE chapterId = :chapterId AND done = 0")
    fun unreadCount(chapterId: Long): Flow<Int>

    @Query("SELECT * FROM chapter_notes WHERE chapterId = :chapterId AND kind = :kind ORDER BY createdAt DESC")
    fun notesOfKind(chapterId: Long, kind: String): Flow<List<ChapterNoteEntity>>

    @Insert suspend fun insert(n: ChapterNoteEntity): Long

    @Update suspend fun update(n: ChapterNoteEntity)

    @Query("UPDATE chapter_notes SET done = 1 WHERE id = :id") suspend fun markDone(id: Long)

    @Query("DELETE FROM chapter_notes WHERE id = :id") suspend fun delete(id: Long)
}
