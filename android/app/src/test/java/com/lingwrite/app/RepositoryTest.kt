// SPDX-FileCopyrightText: 2026 WikG and LingWrite contributors
// SPDX-License-Identifier: AGPL-3.0-only
package com.lingwrite.app

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lingwrite.app.data.BookRepository
import com.lingwrite.app.data.RestoreHints
import com.lingwrite.app.data.Words
import com.lingwrite.app.data.db.LingDatabase
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = android.app.Application::class)
class RepositoryTest {
    private val db = LingDatabase.build(ApplicationProvider.getApplicationContext(), inMemory = true)
    private val repo = BookRepository(db)

    /** 回收站恢复锚点是进程级状态；每个用例前清空，避免测试类之间章节 id 从 1 重开时串味。 */
    @Before fun clearRestoreHints() { RestoreHints.clear() }

    @Test fun words() {
        assertEquals(6, Words.count("林砚说：好。"))
        assertEquals(4, Words.count("Hello world 你好"))
    }

    @Test fun bookChapterVersionsRollback() = runBlocking {
        val id = repo.createBook("测试书", "仙侠")
        val s = repo.summaries().first().single()
        assertEquals(1, s.chapters)
        val ch = db.chapters().chaptersOnce(id).single()
        repo.saveContent(ch.id, "第一版内容。")
        repo.saveContent(ch.id, "第二版内容，多了些字。", snapshotEveryMs = 0)
        val versions = repo.versions(ch.id).first()
        assertEquals(2, versions.size)
        repo.rollback(ch.id, versions.last().id)
        assertEquals("第一版内容。", db.chapters().get(ch.id)!!.content)
        assertTrue(repo.versions(ch.id).first().any { it.kind == "rollback" })
        val n = repo.nextChapter(id)
        assertEquals(2, db.chapters().get(n)!!.number)
    }

    /** 「写下一章」跟随指定章所在卷；不指定时进最后一卷。 */
    @Test fun nextChapterFollowsCurrentVolume() = runBlocking {
        val id = repo.createBook("卷测试", "仙侠")
        val vols = db.chapters().volumesOnce(id)
        val v2 = repo.addVolume(id, "第二卷")
        val c1 = repo.nextChapter(id, afterChapterId = db.chapters().chaptersOnce(id).first().id)
        assertEquals("新章应进当前章所在的第一卷", vols[0].id, db.chapters().get(c1)!!.volumeId)
        val c2 = repo.nextChapter(id)
        assertEquals("不指定时进最后一卷", v2, db.chapters().get(c2)!!.volumeId)
    }

    /** 删除分卷：章节移到相邻卷、卷序与章号重排、只剩一卷时拒绝。 */
    @Test fun deleteVolumeMovesChaptersAndResorts() = runBlocking {
        val id = repo.createBook("删卷测试", "仙侠")
        val v1 = db.chapters().volumesOnce(id)[0]
        val v2 = repo.addVolume(id, "第二卷")
        val v3 = repo.addVolume(id, "第三卷")
        // v1: 1 章；v2: 2 章；v3: 1 章
        val c1 = db.chapters().chaptersOnce(id).first()
        val c2 = repo.nextChapter(id, afterChapterId = c1.id)
        val c3 = repo.nextChapter(id, afterChapterId = c2)
        val c4 = repo.nextChapter(id)
        db.chapters().get(c2)!!.let { db.chapters().update(it.copy(volumeId = v2)) }
        db.chapters().get(c3)!!.let { db.chapters().update(it.copy(volumeId = v2)) }
        db.chapters().get(c4)!!.let { db.chapters().update(it.copy(volumeId = v3)) }
        db.plan().upsertVolume(com.lingwrite.app.data.db.VolumePlanEntity(v2, id, outline = "被删卷的卷纲"))

        // 删中间卷：2 章应移到第一卷，第三卷升为第二卷，sort 0..1，章号连续
        val moved = repo.deleteVolume(id, v2)
        assertEquals(2, moved)
        val volsAfter = db.chapters().volumesOnce(id)
        assertEquals(2, volsAfter.size)
        assertEquals(v1.id, volsAfter[0].id); assertEquals(0, volsAfter[0].sort)
        assertEquals(v3, volsAfter[1].id); assertEquals(1, volsAfter[1].sort)
        val chs = db.chapters().chaptersOnce(id)
        assertEquals("全部 4 章都在，移到了相邻卷", 4, chs.size)
        assertTrue("被删卷的章进了第一卷", chs.all { it.volumeId != v2 })
        assertEquals(listOf(1, 2, 3, 4), chs.map { it.number })
        assertTrue("被删卷的卷纲一并清理", db.plan().volumes(id).first().none { it.volumeId == v2 })

        // 只剩两卷再删一卷（c4 移回第一卷）：允许；最后一卷：拒绝（-1）
        assertEquals(1, repo.deleteVolume(id, v3))
        assertEquals(-1, repo.deleteVolume(id, v1.id))
        assertEquals(1, db.chapters().volumesOnce(id).size)
    }

    @Test fun renumberKeepsEditTimeAndFollowsStoredChapterNumbers() = runBlocking {
        val id = repo.createBook("章号", "仙侠")
        val first = db.chapters().chaptersOnce(id).single()
        repo.saveContent(first.id, "第一章")
        val firstAt = db.chapters().get(first.id)!!.updatedAt
        repo.addVolume(id, "第二卷")
        val second = repo.nextChapter(id)
        db.chapters().update(db.chapters().get(second)!!.copy(content = "第二章正文", wordCount = 500, updatedAt = 1L))
        db.books().update(db.books().get(id)!!.copy(publishedUpTo = 2))
        val movedFs = db.checks().insertForeshadow(com.lingwrite.app.data.db.ForeshadowEntity(
            bookId = id, content = "灯", chapterId = second, plantNumber = 2, payoff = 2, paidAt = 2,
        ))
        val futureFs = db.checks().insertForeshadow(com.lingwrite.app.data.db.ForeshadowEntity(
            bookId = id, content = "后", plantNumber = 1, payoff = 40,
        ))
        db.readerReports().insert(com.lingwrite.app.data.db.ReaderReportEntity(
            bookId = id, fromChapter = 2, toChapter = 2, persona = "veteran", result = "{}", contentHash = "x",
        ))
        val inserted = repo.nextChapter(id, afterChapterId = first.id)
        assertEquals(first.volumeId, db.chapters().get(inserted)!!.volumeId)
        assertEquals(2, db.chapters().get(inserted)!!.number)
        assertEquals(3, db.chapters().get(second)!!.number)
        assertEquals(firstAt, db.chapters().get(first.id)!!.updatedAt)
        assertEquals(1L, db.chapters().get(second)!!.updatedAt)
        assertTrue(repo.todayWords().first() < 500)
        assertEquals(3, db.books().get(id)!!.publishedUpTo)
        val moved = db.checks().foreshadow(movedFs)!!
        assertEquals(3, moved.plantNumber)
        assertEquals(3, moved.payoff)
        assertEquals(3, moved.paidAt)
        assertEquals(40, db.checks().foreshadow(futureFs)!!.payoff)
        val report = db.readerReports().once(id).single()
        assertEquals(3, report.fromChapter)
        assertEquals(3, report.toChapter)
    }

    /** 连续删两章后按任意顺序恢复，章节顺序必须回到原样（曾出现 1,3,2,4 的乱序）。 */
    @Test fun restoreDeletedChaptersInReverseOrderKeepsOrder() = runBlocking {
        val id = repo.createBook("乱序恢复", "仙侠")
        val a = db.chapters().chaptersOnce(id).single().id
        val b = repo.nextChapter(id)
        val c = repo.nextChapter(id)
        val d = repo.nextChapter(id)
        repo.deleteChapter(b)
        repo.deleteChapter(a)
        repo.restoreChapter(b)
        repo.restoreChapter(a)
        assertEquals(listOf(a, b, c, d), db.chapters().chaptersOnce(id).map { it.id })
    }

    /** 删除后再新增章节，恢复的章节仍应回到原位置，而不是插到末尾。 */
    @Test fun restoreDeletedChapterAfterNewChapterKeepsOrder() = runBlocking {
        val id = repo.createBook("恢复位置", "仙侠")
        val a = db.chapters().chaptersOnce(id).single().id
        val b = repo.nextChapter(id)
        val c = repo.nextChapter(id)
        repo.deleteChapter(b)
        val n = repo.nextChapter(id)
        repo.restoreChapter(b)
        assertEquals(listOf(a, b, c, n), db.chapters().chaptersOnce(id).map { it.id })
    }

    /** 相邻两章都被删时，先恢复后一章、再恢复前一章，顺序仍正确。 */
    @Test fun restoreAdjacentDeletedChaptersKeepsOrder() = runBlocking {
        val id = repo.createBook("相邻恢复", "仙侠")
        val a = db.chapters().chaptersOnce(id).single().id
        val b = repo.nextChapter(id)
        val c = repo.nextChapter(id)
        val d = repo.nextChapter(id)
        repo.deleteChapter(b)
        repo.deleteChapter(c)
        repo.restoreChapter(c)
        repo.restoreChapter(b)
        assertEquals(listOf(a, b, c, d), db.chapters().chaptersOnce(id).map { it.id })
    }

    @Test fun restoreDeletedMiddleChapterKeepsOrder() = runBlocking {
        val id = repo.createBook("顺序", "仙侠")
        val a = db.chapters().chaptersOnce(id).single().id
        val b = repo.nextChapter(id)
        val c = repo.nextChapter(id)
        repo.deleteChapter(b)
        repo.restoreChapter(b)
        assertEquals(listOf(a, b, c), db.chapters().chaptersOnce(id).map { it.id })
    }
}
