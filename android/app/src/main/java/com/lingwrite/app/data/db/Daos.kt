// SPDX-FileCopyrightText: 2026 WikG and LingWrite contributors
// SPDX-License-Identifier: AGPL-3.0-only
package com.lingwrite.app.data.db

import androidx.room.*
import kotlinx.coroutines.flow.Flow

data class BookTotals(val words: Int, val chapters: Int)

@Dao
interface BookDao {
    @Query("""SELECT b.id, b.title, b.genre, b.status, b.coverIndex, b.wizardStep, b.updatedAt,
        COALESCE(SUM(c.wordCount),0) AS words, COUNT(c.id) AS chapters, MAX(c.number) AS lastChapter
        FROM books b LEFT JOIN chapters c ON c.bookId = b.id AND c.deletedAt = 0 GROUP BY b.id ORDER BY b.updatedAt DESC""")
    fun summaries(): Flow<List<BookSummary>>

    @Query("SELECT * FROM books WHERE id = :id") fun book(id: Long): Flow<BookEntity?>
    @Query("SELECT * FROM books WHERE id = :id") suspend fun get(id: Long): BookEntity?
    @Query("SELECT COALESCE(SUM(wordCount),0) AS words, COUNT(*) AS chapters FROM chapters WHERE bookId = :bookId AND deletedAt = 0")
    suspend fun totals(bookId: Long): BookTotals
    @Query("SELECT * FROM books") suspend fun all(): List<BookEntity>
    @Insert suspend fun insert(b: BookEntity): Long
    @Update suspend fun update(b: BookEntity)
    @Query("DELETE FROM books WHERE id = :id") suspend fun delete(id: Long)
    @Query("UPDATE books SET updatedAt = :t WHERE id = :id") suspend fun touch(id: Long, t: Long = System.currentTimeMillis())
}

@Dao
interface ChapterDao {
    @Query("SELECT * FROM volumes WHERE bookId = :bookId ORDER BY sort") fun volumes(bookId: Long): Flow<List<VolumeEntity>>
    @Query("SELECT * FROM volumes WHERE bookId = :bookId ORDER BY sort") suspend fun volumesOnce(bookId: Long): List<VolumeEntity>
    @Insert suspend fun insertVolume(v: VolumeEntity): Long
    @Update suspend fun updateVolume(v: VolumeEntity)
    @Query("DELETE FROM volumes WHERE id = :id") suspend fun deleteVolume(id: Long)

    @Query("SELECT id, bookId, volumeId, number, title, '' AS content, wordCount, state, aiMarks, updatedAt, deletedAt FROM chapters WHERE bookId = :bookId AND deletedAt = 0 ORDER BY number DESC")
    fun chapterHeaders(bookId: Long): Flow<List<ChapterEntity>>
    /** 只要章号/标题/字数等元数据，不加载正文。 */
    @Query("SELECT id, bookId, volumeId, number, title, '' AS content, wordCount, state, aiMarks, updatedAt, deletedAt FROM chapters WHERE bookId = :bookId AND deletedAt = 0 ORDER BY number")
    suspend fun headersOnce(bookId: Long): List<ChapterEntity>
    /** 最近编辑的一章正文，用于书架继续写作卡片。 */
    @Query("SELECT content FROM chapters WHERE bookId = :bookId AND deletedAt = 0 ORDER BY updatedAt DESC LIMIT 1")
    suspend fun lastContent(bookId: Long): String?
    /** 最近 N 章正文（按章号倒序），用于输出语言判断。 */
    @Query("SELECT content FROM chapters WHERE bookId = :bookId AND deletedAt = 0 ORDER BY number DESC LIMIT :n")
    suspend fun lastNContent(bookId: Long, n: Int): List<String>
    /** 当前进度章：最后一个有字数的章节。 */
    @Query("SELECT MAX(number) FROM chapters WHERE bookId = :bookId AND deletedAt = 0 AND wordCount > 0")
    suspend fun maxNumberWithWords(bookId: Long): Int?
    /** 正文索引过期（未索引或 updatedAt 晚于索引时间）的章节 id。 */
    @Query("SELECT c.id FROM chapters c LEFT JOIN chapter_index i ON i.chapterId = c.id WHERE c.bookId = :bookId AND c.deletedAt = 0 AND c.id != :except AND (i.at IS NULL OR i.at < c.updatedAt)")
    suspend fun staleIds(bookId: Long, except: Long = -1): List<Long>
    @Query("SELECT * FROM chapters WHERE bookId = :bookId AND deletedAt = 0 ORDER BY number") suspend fun chaptersOnce(bookId: Long): List<ChapterEntity>
    @Query("SELECT * FROM chapters WHERE bookId = :bookId AND deletedAt != 0 ORDER BY deletedAt DESC") fun deletedChapters(bookId: Long): Flow<List<ChapterEntity>>
    @Query("SELECT * FROM chapters WHERE id = :id") fun chapter(id: Long): Flow<ChapterEntity?>
    @Query("SELECT * FROM chapters WHERE id = :id") suspend fun get(id: Long): ChapterEntity?
    @Query("SELECT * FROM chapters WHERE bookId = :bookId AND deletedAt = 0 AND number < :number ORDER BY number DESC LIMIT :n") suspend fun previous(bookId: Long, number: Int, n: Int): List<ChapterEntity>
    /** 今日字数：今天有修改的章节，相对今天之前最后一个版本的增量（无版本则计全部）。 */
    @Query("""SELECT COALESCE(SUM(MAX(0, c.wordCount - COALESCE((SELECT v.wordCount FROM chapter_versions v WHERE v.chapterId = c.id AND v.createdAt < :since ORDER BY v.createdAt DESC LIMIT 1), 0))),0)
        FROM chapters c WHERE c.deletedAt = 0 AND c.updatedAt >= :since""")
    fun todayWords(since: Long): Flow<Int>
    @Query("SELECT MAX(number) FROM chapters WHERE bookId = :bookId AND deletedAt = 0") suspend fun maxNumber(bookId: Long): Int?
    @Insert suspend fun insert(c: ChapterEntity): Long
    @Insert suspend fun insertAll(c: List<ChapterEntity>): List<Long>
    @Update suspend fun update(c: ChapterEntity)
    @Query("UPDATE chapters SET content = :content, wordCount = :words, aiMarks = :marks, updatedAt = :at WHERE id = :id AND deletedAt = 0")
    suspend fun updateContent(id: Long, content: String, words: Int, marks: String, at: Long)
    @Query("UPDATE chapters SET title = :title WHERE id = :id") suspend fun rename(id: Long, title: String)
    @Query("UPDATE chapters SET state = :state WHERE id = :id") suspend fun setState(id: Long, state: String)
    @Query("UPDATE chapters SET number = :number, volumeId = :volume WHERE id = :id")
    suspend fun position(id: Long, number: Int, volume: Long)
    @Query("UPDATE chapters SET deletedAt = :at WHERE id = :id") suspend fun setDeleted(id: Long, at: Long)
    @Query("DELETE FROM chapters WHERE id = :id") suspend fun delete(id: Long)

    @Query("SELECT * FROM chapter_versions WHERE chapterId = :chapterId ORDER BY createdAt DESC") fun versions(chapterId: Long): Flow<List<ChapterVersionEntity>>
    @Query("SELECT * FROM chapter_versions WHERE chapterId = :chapterId ORDER BY createdAt DESC LIMIT 1") suspend fun latestVersion(chapterId: Long): ChapterVersionEntity?
    @Query("SELECT * FROM chapter_versions WHERE id = :id") suspend fun version(id: Long): ChapterVersionEntity?
    @Insert suspend fun insertVersion(v: ChapterVersionEntity): Long
    /** 自动版本只保留最近 [keep] 个；手动快照不清理 */
    @Query("DELETE FROM chapter_versions WHERE chapterId = :chapterId AND kind = 'auto' AND id NOT IN (SELECT id FROM chapter_versions WHERE chapterId = :chapterId AND kind = 'auto' ORDER BY createdAt DESC LIMIT :keep)")
    suspend fun pruneAuto(chapterId: Long, keep: Int)
}

data class UsageRow(val model: String, val task: String, val calls: Int, val inTok: Long, val outTok: Long, val provider: String)
data class DayUsage(val day: String, val calls: Int, val inTok: Long, val outTok: Long)

@Dao
interface AiDao {
    @Query("SELECT * FROM providers ORDER BY createdAt") fun providers(): Flow<List<ProviderEntity>>
    @Query("SELECT * FROM providers ORDER BY createdAt") suspend fun providersOnce(): List<ProviderEntity>
    @Query("SELECT * FROM providers WHERE id = :id") suspend fun provider(id: Long): ProviderEntity?
    @Query("SELECT * FROM providers WHERE id = :id") fun providerFlow(id: Long): Flow<ProviderEntity?>
    @Insert suspend fun insertProvider(p: ProviderEntity): Long
    @Update suspend fun updateProvider(p: ProviderEntity)
    @Query("DELETE FROM providers WHERE id = :id") suspend fun deleteProvider(id: Long)

    @Insert suspend fun insertLog(l: CallLogEntity): Long
    @Update suspend fun updateLog(l: CallLogEntity)
    @Query("SELECT * FROM call_logs WHERE id = :id") suspend fun log(id: Long): CallLogEntity?
    @Query("UPDATE call_logs SET outcome = :outcome WHERE id = :id") suspend fun setOutcome(id: Long, outcome: String)
    @Query("UPDATE call_logs SET candidates = :candidates, chosen = :chosen WHERE id = :id") suspend fun setChoice(id: Long, candidates: Int, chosen: Int)
    @Query("SELECT * FROM call_logs WHERE (:bookId IS NULL OR bookId = :bookId) ORDER BY time DESC LIMIT :limit") fun logs(bookId: Long?, limit: Int = 500): Flow<List<CallLogEntity>>
    @Query("SELECT * FROM call_logs WHERE chapterId = :chapterId ORDER BY time DESC") fun chapterLogs(chapterId: Long): Flow<List<CallLogEntity>>
    @Query("SELECT model, task, provider, COUNT(*) AS calls, SUM(inputTokens) AS inTok, SUM(outputTokens) AS outTok FROM call_logs WHERE time >= :since AND task != 'test' GROUP BY provider, model, task")
    fun usageSince(since: Long): Flow<List<UsageRow>>
    @Query("SELECT strftime('%Y-%m-%d', time / 1000, 'unixepoch', 'localtime') AS day, COUNT(*) AS calls, SUM(inputTokens) AS inTok, SUM(outputTokens) AS outTok FROM call_logs WHERE time >= :since AND task != 'test' GROUP BY day ORDER BY day")
    fun daily(since: Long): Flow<List<DayUsage>>
}
