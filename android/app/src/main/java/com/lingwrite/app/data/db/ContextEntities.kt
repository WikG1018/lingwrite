// SPDX-FileCopyrightText: 2026 WikG and LingWrite contributors
// SPDX-License-Identifier: AGPL-3.0-only
package com.lingwrite.app.data.db

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "chapter_summaries", foreignKeys = [ForeignKey(entity = ChapterEntity::class, parentColumns = ["id"], childColumns = ["chapterId"], onDelete = ForeignKey.CASCADE)], indices = [Index("bookId")])
data class SummaryEntity(
    @PrimaryKey val chapterId: Long,
    val bookId: Long,
    val text: String,
    /** 作者确认过的摘要不会被自动覆盖，并优先使用 */
    val confirmed: Boolean = false,
    val updatedAt: Long = System.currentTimeMillis(),
)

@Entity(tableName = "volume_summaries", foreignKeys = [ForeignKey(entity = VolumeEntity::class, parentColumns = ["id"], childColumns = ["volumeId"], onDelete = ForeignKey.CASCADE)], indices = [Index("bookId")])
data class VolumeSummaryEntity(@PrimaryKey val volumeId: Long, val bookId: Long, val text: String, val confirmed: Boolean = false, val chapters: Int = 0, val updatedAt: Long = System.currentTimeMillis())

/** 正文切片（约 300 字一段）。vector 为可选的语义向量（逗号分隔）。 */
@Entity(tableName = "passages", foreignKeys = [ForeignKey(entity = ChapterEntity::class, parentColumns = ["id"], childColumns = ["chapterId"], onDelete = ForeignKey.CASCADE)], indices = [Index("chapterId"), Index("bookId")])
data class PassageEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val bookId: Long,
    val chapterId: Long,
    val number: Int,
    val idx: Int,
    val start: Int,
    val text: String,
    val vector: String = "",
)

/** FTS4 全文索引：grams = 二元切分（支持短语），unis = 单字。rowid 与 passages.id 一致。 */
@Fts4
@Entity(tableName = "passages_fts")
data class PassageFts(@PrimaryKey @ColumnInfo(name = "rowid") val rowid: Long, val grams: String, val unis: String)

@Entity(tableName = "chapter_index", foreignKeys = [ForeignKey(entity = ChapterEntity::class, parentColumns = ["id"], childColumns = ["chapterId"], onDelete = ForeignKey.CASCADE)])
data class ChapterIndexEntity(@PrimaryKey val chapterId: Long, val at: Long)

data class Hit(val id: Long, val bookId: Long, val chapterId: Long, val number: Int, val start: Int, val text: String)

@Dao
interface ContextDao {
    @Query("SELECT * FROM chapter_summaries WHERE bookId = :bookId") suspend fun summaries(bookId: Long): List<SummaryEntity>
    @Query("SELECT * FROM chapter_summaries WHERE chapterId = :id") fun summary(id: Long): Flow<SummaryEntity?>
    @Query("SELECT * FROM chapter_summaries WHERE chapterId = :id") suspend fun summaryOnce(id: Long): SummaryEntity?
    @Upsert suspend fun upsertSummary(s: SummaryEntity)
    @Query("SELECT * FROM volume_summaries WHERE bookId = :bookId") suspend fun volumeSummaries(bookId: Long): List<VolumeSummaryEntity>
    @Query("SELECT * FROM volume_summaries WHERE volumeId = :id") suspend fun volumeSummary(id: Long): VolumeSummaryEntity?
    @Upsert suspend fun upsertVolumeSummary(s: VolumeSummaryEntity)

    @Query("SELECT * FROM chapter_index WHERE chapterId IN (SELECT id FROM chapters WHERE bookId = :bookId)") suspend fun indexState(bookId: Long): List<ChapterIndexEntity>
    @Upsert suspend fun markIndexed(e: ChapterIndexEntity)
    @Query("SELECT id FROM passages WHERE chapterId = :chapterId") suspend fun passageIds(chapterId: Long): List<Long>
    @Query("DELETE FROM passages_fts WHERE rowid IN (:ids)") suspend fun deleteFts(ids: List<Long>)
    @Query("DELETE FROM passages WHERE chapterId = :chapterId") suspend fun deletePassages(chapterId: Long)
    @Insert suspend fun insertPassage(p: PassageEntity): Long
    @Insert suspend fun insertFts(f: PassageFts)
    @Query("SELECT * FROM passages WHERE bookId = :bookId AND vector = '' AND chapterId IN (SELECT id FROM chapters WHERE deletedAt = 0)") suspend fun unvectored(bookId: Long): List<PassageEntity>
    @Query("SELECT * FROM passages WHERE bookId = :bookId AND vector != '' AND chapterId IN (SELECT id FROM chapters WHERE deletedAt = 0)") suspend fun vectored(bookId: Long): List<PassageEntity>
    @Query("UPDATE passages SET vector = :v WHERE id = :id") suspend fun setVector(id: Long, v: String)
    @Query("UPDATE passages SET vector = '' WHERE bookId = :bookId") suspend fun clearVectors(bookId: Long)

    @Query("""SELECT p.id, p.bookId, p.chapterId, p.number, p.start, p.text FROM passages_fts f JOIN passages p ON p.id = f.rowid
        JOIN chapters c ON c.id = p.chapterId WHERE c.deletedAt = 0 AND f.grams MATCH :q AND (:bookId = 0 OR p.bookId = :bookId) LIMIT :limit""")
    suspend fun search(q: String, bookId: Long, limit: Int = 60): List<Hit>
    @Query("""SELECT p.id, p.bookId, p.chapterId, p.number, p.start, p.text FROM passages_fts f JOIN passages p ON p.id = f.rowid
        JOIN chapters c ON c.id = p.chapterId WHERE c.deletedAt = 0 AND f.unis MATCH :q AND (:bookId = 0 OR p.bookId = :bookId) LIMIT :limit""")
    suspend fun searchUnis(q: String, bookId: Long, limit: Int = 60): List<Hit>
}
