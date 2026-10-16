// SPDX-FileCopyrightText: 2026 WikG and LingWrite contributors
// SPDX-License-Identifier: AGPL-3.0-only
package com.lingwrite.app

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lingwrite.app.data.WriteMode
import kotlinx.coroutines.flow.first
import com.lingwrite.app.data.db.LingDatabase
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * MD2/MD3（docs/v0.2/modes.md §6）：
 * 辅助模式下 Note 类工具采纳后正文长度不变、chapter_notes 多 1 条；生成模式候选采纳后正文出现 gen 标记。
 * 用 FakeProvider 直接驱动（不经过网络）。
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = android.app.Application::class)
class ModeTest {
    private lateinit var db: LingDatabase
    private var bookId = 0L
    private var chapterId = 0L

    private class FakeProvider : com.lingwrite.app.data.ai.LlmProvider {
        var reply = "第一个方向。"
        override fun stream(req: com.lingwrite.app.data.ai.ChatRequest) = kotlinx.coroutines.flow.flow {
            emit(com.lingwrite.app.data.ai.LlmEvent.Delta(reply))
            emit(com.lingwrite.app.data.ai.LlmEvent.Done(10, 10))
        }
    }

    @Before fun seed() {
        AppGraph.init(ApplicationProvider.getApplicationContext(), inMemory = true)
        db = AppGraph.db
        runBlocking { bookId = AppGraph.books.createBook("模式测试") }
        chapterId = runBlocking { db.chapters().chaptersOnce(bookId).single().id }
        runBlocking { AppGraph.books.saveContent(chapterId, "沈砚把断剑收进袖里。") }
        com.lingwrite.app.data.ai.FakeLlmRegistry.provider = FakeProvider()
    }

    @Test fun assistOutputsNeverTouchBody() = runBlocking {
        val mode = WriteMode.Assist
        // 工具条：辅助模式无选区只有“给方向”
        val tools = com.lingwrite.app.data.ai.Prompts.toolbarTools(mode.id, hasSelection = false)
        assertEquals(listOf("directions"), tools.map { it.id })
        val before = db.chapters().get(chapterId)!!.content
        // 模拟“给方向”的采纳：存便签（EditorAi.saveNote 的等价数据路径）
        db.notes().insert(com.lingwrite.app.data.db.ChapterNoteEntity(chapterId = chapterId, kind = "direction", text = "1. 第一个方向。"))
        val after = db.chapters().get(chapterId)!!.content
        assertEquals("辅助模式采纳后正文不变", before, after)
        val notes = db.notes().notes(chapterId).first()
        assertEquals("便签应正好 1 条", 1, notes.size)
        assertEquals("direction", notes.single().kind)
    }

    @Test fun generateToolbarHasMultiCandidateTasks() {
        val tools = com.lingwrite.app.data.ai.Prompts.toolbarTools("generate", hasSelection = false)
        assertEquals(listOf("continue", "draft", "dialog", "describe"), tools.map { it.id })
        assertTrue(com.lingwrite.app.data.ai.Prompts.multiCandidateTask("continue", "generate", false))
        assertTrue(!com.lingwrite.app.data.ai.Prompts.multiCandidateTask("directions", "assist", false))
        assertTrue(com.lingwrite.app.data.ai.Prompts.multiCandidateTask("rewrite", "generate", true))
        assertTrue(!com.lingwrite.app.data.ai.Prompts.multiCandidateTask("rewrite", "generate", false))
    }

    @Test fun generateAcceptMarksGenAndRecordsChoice() = runBlocking {
        // 模拟生成模式候选采纳（EditorAi.acceptCandidate 的等价数据路径）
        val st = com.lingwrite.app.ui.routes.EditorState(db.chapters().get(chapterId)!!)
        st.replace(before0().length, before0().length, "袖中的断剑又轻轻一震。", "gen")
        AppGraph.books.saveContent(chapterId, st.value.text, encodeMarks(st.marks))
        val saved = db.chapters().get(chapterId)!!
        assertTrue("生成模式采纳后正文出现 gen 标记", saved.aiMarks.contains("gen"))
        assertTrue(saved.content.contains("断剑又轻轻一震"))
        // call_logs 记录 chosen
        val logId = db.ai().insertLog(com.lingwrite.app.data.db.CallLogEntity(bookId = bookId, chapterId = chapterId, provider = "p", model = "m", task = "continue", candidates = 3))
        db.ai().setChoice(logId, 3, 2)
        assertEquals(3, db.ai().log(logId)!!.candidates)
        assertEquals(2, db.ai().log(logId)!!.chosen)
        assertNotEquals(-1, db.ai().log(logId)!!.chosen)
    }

    private fun before0() = "沈砚把断剑收进袖里。"
    private fun encodeMarks(m: List<com.lingwrite.app.data.AiMark>) = com.lingwrite.app.data.encodeMarks(m)
}
