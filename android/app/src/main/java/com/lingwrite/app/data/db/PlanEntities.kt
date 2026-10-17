// SPDX-FileCopyrightText: 2026 WikG and LingWrite contributors
// SPDX-License-Identifier: AGPL-3.0-only
package com.lingwrite.app.data.db

import androidx.room.*
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.Serializable

/** 作品级大纲配置：总纲、自定义章纲字段、线索、黄金三章检查表。 */
@Entity(tableName = "book_plans", foreignKeys = [ForeignKey(entity = BookEntity::class, parentColumns = ["id"], childColumns = ["bookId"], onDelete = ForeignKey.CASCADE)])
data class BookPlanEntity(
    @PrimaryKey val bookId: Long,
    val synopsis: String = "",
    /** JSON ["爽点","情绪"] */
    val fields: String = "[]",
    /** JSON ["主线","感情线"] */
    val threads: String = "[\"主线\"]",
    val gapThreshold: Int = 8,
    /** JSON 检查项文字 */
    val golden: String = "",
    /** JSON [{"item":..,"pass":true,"evidence":..}] */
    val goldenResult: String = "[]",
    val goldenAt: Long = 0,
    /** 已套用的模板 id，逗号分隔 */
    val templates: String = "",
    /** 作品文风卡（向导第 2 步 / 自然文风模块） */
    val style: String = "",
    /** 向导草稿 JSON（WizardData） */
    val wizard: String = "",
    // ---- v0.2 总纲链路（docs/v0.2/outline-chain.md §2，迁移 3→4）----
    /** ① 一句话创意 */
    val idea: String = "",
    /** ① 采纳的梗概 */
    val logline: String = "",
    /** ② 卖点 JSON 数组 */
    val sellingPoints: String = "[]",
    /** 链路已完成到第几步（0–6） */
    val chainStep: Int = 0,
    /** ChainState JSON：各步未采纳候选缓存 + 上游哈希 */
    val chain: String = "{}",
)

@Entity(tableName = "volume_plans", foreignKeys = [ForeignKey(entity = VolumeEntity::class, parentColumns = ["id"], childColumns = ["volumeId"], onDelete = ForeignKey.CASCADE)], indices = [Index("bookId")])
data class VolumePlanEntity(
    @PrimaryKey val volumeId: Long, val bookId: Long, val outline: String = "",
    /** ④ 本卷目标（链路） */
    val goal: String = "",
    /** ④ 本卷高潮（链路） */
    val climax: String = "",
    /** ④ 预计章数（链路） */
    val chapterCount: Int = 0,
)

@Serializable
@Entity(tableName = "chapter_plans", foreignKeys = [ForeignKey(entity = ChapterEntity::class, parentColumns = ["id"], childColumns = ["chapterId"], onDelete = ForeignKey.CASCADE)], indices = [Index("bookId")])
data class ChapterPlanEntity(
    @PrimaryKey val chapterId: Long,
    val bookId: Long,
    val event: String = "",
    val hook: String = "",
    val cliff: String = "",
    val note: String = "",
    /** JSON {"爽点":"…"} */
    val custom: String = "{}",
    /** 逗号分隔线索名 */
    val threads: String = "",
    /** 来自 AI 拆解并经作者采纳 */
    val fromAi: Boolean = false,
    /** 落实检查 JSON {"event":true,"hook":false,"cliff":true,"note":"…"}；空表示未检查 */
    val landed: String = "",
    /** ⑥ 场景节拍 JSON [{"text":"…","done":false}]（链路） */
    val beats: String = "[]",
    /** A13 章节标题候选 JSON ["…"] */
    val titleIdeas: String = "[]",
    /** 作者标注的故事内时间，如「第三天清晨」；不是系统时间。 */
    @ColumnInfo(defaultValue = "") val storyTime: String = "",
)

/** 章纲回写前的快照；恢复时按 planJson 原样写回。 */
@Entity(
    tableName = "chapter_plan_versions",
    foreignKeys = [
        ForeignKey(entity = ChapterEntity::class, parentColumns = ["id"], childColumns = ["chapterId"], onDelete = ForeignKey.CASCADE),
        ForeignKey(entity = BookEntity::class, parentColumns = ["id"], childColumns = ["bookId"], onDelete = ForeignKey.CASCADE),
    ],
    indices = [Index("chapterId"), Index("bookId")],
)
data class ChapterPlanVersionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val chapterId: Long,
    val bookId: Long,
    val planJson: String,
    val note: String = "",
    val createdAt: Long = System.currentTimeMillis(),
)

@Dao
interface PlanDao {
    @Query("SELECT * FROM book_plans WHERE bookId = :bookId") fun book(bookId: Long): Flow<BookPlanEntity?>
    @Query("SELECT * FROM book_plans WHERE bookId = :bookId") suspend fun bookOnce(bookId: Long): BookPlanEntity?
    @Upsert suspend fun upsertBook(p: BookPlanEntity)
    @Query("SELECT * FROM volume_plans WHERE bookId = :bookId") fun volumes(bookId: Long): Flow<List<VolumePlanEntity>>
    @Query("SELECT * FROM volume_plans WHERE volumeId = :id") suspend fun volumeOnce(id: Long): VolumePlanEntity?
    @Upsert suspend fun upsertVolume(p: VolumePlanEntity)
    @Query("DELETE FROM volume_plans WHERE volumeId = :id") suspend fun deleteVolumePlan(id: Long)
    @Query("SELECT * FROM chapter_plans WHERE bookId = :bookId") fun chapters(bookId: Long): Flow<List<ChapterPlanEntity>>
    @Query("SELECT * FROM chapter_plans WHERE bookId = :bookId") suspend fun chaptersOnce(bookId: Long): List<ChapterPlanEntity>
    @Query("SELECT * FROM chapter_plans WHERE chapterId = :id") fun chapter(id: Long): Flow<ChapterPlanEntity?>
    @Query("SELECT * FROM chapter_plans WHERE chapterId = :id") suspend fun chapterOnce(id: Long): ChapterPlanEntity?
    @Upsert suspend fun upsertChapter(p: ChapterPlanEntity)
    @Query("UPDATE chapter_plans SET note = :before WHERE chapterId = :chapterId AND note = :expected")
    suspend fun restoreNoteIfUnchanged(chapterId: Long, expected: String, before: String): Int

    @Query("SELECT * FROM chapter_plan_versions WHERE chapterId = :chapterId ORDER BY createdAt DESC") suspend fun planVersions(chapterId: Long): List<ChapterPlanVersionEntity>
    @Query("SELECT * FROM chapter_plan_versions WHERE id = :id") suspend fun planVersion(id: Long): ChapterPlanVersionEntity?
    @Insert suspend fun insertPlanVersion(v: ChapterPlanVersionEntity): Long
    @Query("DELETE FROM chapter_plan_versions WHERE chapterId = :chapterId AND id NOT IN (SELECT id FROM chapter_plan_versions WHERE chapterId = :chapterId ORDER BY createdAt DESC LIMIT :keep)")
    suspend fun prunePlanVersions(chapterId: Long, keep: Int)
}
