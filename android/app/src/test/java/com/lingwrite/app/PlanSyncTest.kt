// SPDX-FileCopyrightText: 2026 WikG and LingWrite contributors
// SPDX-License-Identifier: AGPL-3.0-only
package com.lingwrite.app

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lingwrite.app.data.*
import com.lingwrite.app.data.db.ChapterPlanEntity
import com.lingwrite.app.data.db.VolumePlanEntity
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = Application::class)
class PlanSyncTest {
    private val ctx get() = ApplicationProvider.getApplicationContext<Application>()

    @Before fun seed() { AppGraph.init(ctx, inMemory = true) }

    @Test fun diffLandedGapsAndSnapshotRestore() = runBlocking {
        val book = AppGraph.books.createBook("章纲同步")
        val chapter = AppGraph.db.chapters().chaptersOnce(book).single()
        val dao = AppGraph.db.plan()
        val old = ChapterPlanEntity(chapterId = chapter.id, bookId = book, event = "旧事件", hook = "旧钩子", cliff = "旧悬念", landed = """{"event":false,"hook":true,"cliff":false,"note":"偏离"}""")
        dao.upsertChapter(old)
        assertEquals(listOf("event", "cliff"), PlanSync.landedGaps(old))
        val after = old.copy(event = "新事件", cliff = "新悬念", note = "回写", storyTime = "第三天")
        assertEquals(setOf("event", "cliff", "note", "storyTime"), PlanSync.diff(old, after).map { it.field }.toSet())

        val version = PlanSync.apply(PlanProposal(chapter.id, old, after, PlanSync.diff(old, after)))
        assertTrue(version > 0)
        assertEquals("新事件", dao.chapterOnce(chapter.id)!!.event)
        assertEquals(1, dao.planVersions(chapter.id).size)
        val restored = PlanSync.restore(chapter.id, version)
        assertEquals("旧事件", restored!!.event)
        assertEquals("旧事件", dao.chapterOnce(chapter.id)!!.event)
    }

    @Test fun volumeProgressComparesPlanAndActual() = runBlocking {
        val book = AppGraph.books.createBook("卷进度")
        val vol = AppGraph.db.chapters().volumesOnce(book).single()
        AppGraph.db.plan().upsertVolume(VolumePlanEntity(volumeId = vol.id, bookId = book, chapterCount = 3))
        val p = PlanSync.volumeProgress(book)
        assertEquals(1, p.size)
        assertEquals(3, p[0].planned)
        assertEquals(1, p[0].actual)
    }

    @Test fun promptMentionsJsonOnlyAndStoryTime() {
        val plan = ChapterPlanEntity(chapterId = 1, bookId = 1, event = "事件")
        val prompt = OutlinePrompts.resync(plan, "正文内容", listOf("event"))
        assertTrue(prompt.contains("只输出 JSON"))
        assertTrue(prompt.contains("storyTime"))
        assertTrue(prompt.contains("未落实项"))
    }

    // 审查 #14：采纳只合并 diff 字段，不覆盖作者在提议之后的手改。
    @Test fun applyMergesOnlyDiffFields() = runBlocking {
        val book = AppGraph.books.createBook("合并")
        val chapter = AppGraph.db.chapters().chaptersOnce(book).single()
        val dao = AppGraph.db.plan()
        val old = ChapterPlanEntity(chapterId = chapter.id, bookId = book, event = "旧事件", hook = "旧钩子", note = "作者备注")
        dao.upsertChapter(old)
        val after = old.copy(event = "AI 事件")
        val proposal = PlanProposal(chapter.id, old, after, PlanSync.diff(old, after))
        // 提议出来之后，作者手动改了钩子
        dao.upsertChapter(old.copy(hook = "作者新钩子"))
        PlanSync.apply(proposal)
        val now = dao.chapterOnce(chapter.id)!!
        assertEquals("AI 事件", now.event)
        assertEquals("作者手改保留", "作者新钩子", now.hook)
        assertEquals("作者备注", now.note)
    }

    @Test fun restoreSnapshotsCurrentSoUndoIsUndoable() = runBlocking {
        val book = AppGraph.books.createBook("撤销")
        val chapter = AppGraph.db.chapters().chaptersOnce(book).single()
        val dao = AppGraph.db.plan()
        val old = ChapterPlanEntity(chapterId = chapter.id, bookId = book, event = "A")
        dao.upsertChapter(old)
        val after = old.copy(event = "B")
        val v = PlanSync.apply(PlanProposal(chapter.id, old, after, PlanSync.diff(old, after)))
        assertEquals("B", dao.chapterOnce(chapter.id)!!.event)
        PlanSync.restore(chapter.id, v)
        assertEquals("A", dao.chapterOnce(chapter.id)!!.event)
        val versions = dao.planVersions(chapter.id)
        assertEquals(2, versions.size)
        // 最新快照是撤销前的 B，可以再恢复回去
        val redo = versions.maxBy { it.id }
        PlanSync.restore(chapter.id, redo.id)
        assertEquals("B", dao.chapterOnce(chapter.id)!!.event)
    }

    @Test fun fieldLabelsAreNotInternalIds() {
        listOf("event", "hook", "cliff", "note", "storyTime").forEach { f ->
            val label = PlanSync.fieldLabel(f)
            assertNotEquals(f, label)
            assertTrue(label.isNotBlank())
        }
    }
}
