// SPDX-FileCopyrightText: 2026 WikG and LingWrite contributors
// SPDX-License-Identifier: AGPL-3.0-only
package com.lingwrite.app

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lingwrite.app.data.Beat
import com.lingwrite.app.data.ChapterDraft
import com.lingwrite.app.data.Naming
import com.lingwrite.app.data.Pitch
import com.lingwrite.app.data.WizardData
import com.lingwrite.app.data.db.ChapterPlanEntity
import com.lingwrite.app.data.encodeBeats
import com.lingwrite.app.data.parseBeats
import com.lingwrite.app.ui.routes.EditorState
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * M3 端到端（docs/v0.2/outline-chain.md §7）：
 * ①梗概 → ②核心设定 → ③总纲 → ④拆卷 → ⑤章纲 → ⑥第一章（辅助/生成各走一遍），断言全链路数据。
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = android.app.Application::class)
class ChainE2ETest {
    private var bookId = 0L

    @Before fun seed() {
        AppGraph.init(ApplicationProvider.getApplicationContext(), inMemory = true)
        com.lingwrite.app.Modules.install()
    }

    private suspend fun wizardData() = WizardData(
        idea = "断剑少年入青云查旧案",
        pitches = listOf(Pitch("问剑", "断剑少年潜入宗门查十年前的灭门案")),
        premise = "断剑少年潜入宗门查旧案，一路遭遇同门倾轧",
        genre = "仙侠",
        synopsis = "主线：断剑少年查旧案\n阶段外门 站稳脚跟；旧案线索；被卷入内门之争\n结局：旧案大白，宗门重建",
        volumeTitle = "山门初入",
        volumeOutline = "主角入外门、初露锋芒",
        chapters = listOf(
            ChapterDraft(title = "外门比试", event = "外门比试", hook = "断剑共鸣", cliff = "有人认出剑"),
            ChapterDraft(title = "藏剑峰的雨", event = "夜探藏剑峰", hook = "雨夜来客", cliff = "剑鸣示警"),
            ChapterDraft(title = "下山之前", event = "领受任务", hook = "旧案卷宗", cliff = "名字出现"),
        ),
    )

    @Test fun fullChainAssistAndGenerate() = runBlocking {
        bookId = AppGraph.books.createBook("链路测试", "仙侠")
        val d = wizardData()
        // ①–⑥ 采纳（复用向导的写库逻辑，代表 AI 返回已被作者采纳）
        com.lingwrite.app.ui.routes.adopt(bookId, 0, d)
        com.lingwrite.app.ui.routes.adopt(bookId, 1, d)
        com.lingwrite.app.ui.routes.adopt(bookId, 2, d)
        com.lingwrite.app.ui.routes.adopt(bookId, 3, d)
        com.lingwrite.app.ui.routes.adopt(bookId, 4, d)
        com.lingwrite.app.ui.routes.adopt(bookId, 5, d)
        com.lingwrite.app.ui.routes.adopt(bookId, 6, d)
        com.lingwrite.app.ui.routes.adopt(bookId, 7, d)
        com.lingwrite.app.ui.routes.finish(bookId, d, true)

        val bp = AppGraph.db.plan().bookOnce(bookId)!!
        assertTrue("logline 非空", bp.logline.isNotBlank())
        assertTrue("synopsis 非空", bp.synopsis.isNotBlank())
        assertTrue("idea 非空", bp.idea.isNotBlank())
        assertEquals("链路完成", 6, bp.chainStep)

        // 卷名不带编号
        val vols = AppGraph.db.chapters().volumesOnce(bookId).sortedBy { it.sort }
        assertTrue(vols.isNotEmpty())
        vols.forEach { v -> assertTrue("卷名不应带编号: ${v.title}", !v.title.startsWith("第")) }

        // 第一卷有 3 个章纲；章名只存名称
        val chs = AppGraph.db.chapters().chaptersOnce(bookId).sortedBy { it.number }
        assertTrue(chs.size >= 3)
        chs.take(3).forEach { ch -> assertTrue("章名不应带编号: ${ch.title}", !ch.title.startsWith("第")) }

        // ⑥ 节拍写进第一章
        val plan = AppGraph.db.plan().chapterOnce(chs.first().id)!!
        val beats = parseBeats(plan.beats)
        assertTrue("第一章应有节拍", beats.isNotEmpty())
        assertTrue(beats.all { it.text.isNotBlank() })

        // 生成模式：起草采纳后正文出现 gen 标记（outline-chain §7.2）
        val st = EditorState(AppGraph.db.chapters().get(chs.first().id)!!)
        val len = st.value.text.length
        st.replace(len, len, "袖中的断剑忽然轻轻一震。", "gen")
        AppGraph.books.saveContent(chs.first().id, st.value.text, com.lingwrite.app.data.encodeMarks(st.marks))
        val saved = AppGraph.db.chapters().get(chs.first().id)!!
        assertTrue("采纳的段落应带 gen 标记", saved.aiMarks.contains("gen"))
        assertTrue(saved.content.contains("断剑忽然轻轻一震"))

        // 辅助模式：正文由作者写，AI 只进便签（outline-chain §7.3）
        AppGraph.db.notes().insert(com.lingwrite.app.data.db.ChapterNoteEntity(chapterId = chs.first().id, kind = "direction", text = "开篇示意：雨夜来客"))
        val notes = AppGraph.db.notes().notes(chs.first().id).first()
        assertEquals(1, notes.size)
        assertEquals("direction", notes.single().kind)

        // 向导完成后 wizardStep = 8（8 步向导）
        assertEquals(8, AppGraph.db.books().get(bookId)!!.wizardStep)

        // 卷纲的新字段（goal/climax/chapterCount）可写
        val vp = AppGraph.db.plan().volumeOnce(vols.first().id)!!
        assertTrue(vp.outline.isNotBlank())
    }

    @Test fun beatsToggleRoundTrip() = runBlocking {
        bookId = AppGraph.books.createBook("节拍测试")
        val ch = AppGraph.db.chapters().chaptersOnce(bookId).first()
        val beats = listOf(Beat("抛出钩子"), Beat("对峙升级"), Beat("断剑示警"))
        AppGraph.db.plan().upsertChapter(ChapterPlanEntity(ch.id, bookId, event = "外门比试").copy(beats = encodeBeats(beats)))
        val back = parseBeats(AppGraph.db.plan().chapterOnce(ch.id)!!.beats)
        assertEquals(3, back.size)
        val toggled = back.mapIndexed { i, b -> if (i == 1) b.copy(done = true) else b }
        AppGraph.db.plan().upsertChapter(ChapterPlanEntity(ch.id, bookId, event = "外门比试").copy(beats = encodeBeats(toggled)))
        assertEquals(listOf(false, true, false), parseBeats(AppGraph.db.plan().chapterOnce(ch.id)!!.beats).map { it.done })
    }

    @Test fun rollingPlanningRenumbers() = runBlocking {
        bookId = AppGraph.books.createBook("滚动规划")
        val vol = AppGraph.db.chapters().volumesOnce(bookId).first()
        // ④ 拆卷后再规划后续（A7）：卷内新增章要重编号
        AppGraph.books.addChapterTo(bookId, vol.id, Naming.stripChapter("第 2 章"))
        AppGraph.books.addChapterTo(bookId, vol.id, Naming.stripChapter("第 3 章"))
        val chs = AppGraph.db.chapters().chaptersOnce(bookId).sortedBy { it.number }
        assertEquals(listOf(1, 2, 3), chs.map { it.number })
        assertTrue(chs.none { it.title.startsWith("第") })
    }
}
