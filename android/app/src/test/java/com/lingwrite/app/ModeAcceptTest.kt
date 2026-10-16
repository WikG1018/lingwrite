// SPDX-FileCopyrightText: 2026 WikG and LingWrite contributors
// SPDX-License-Identifier: AGPL-3.0-only
package com.lingwrite.app

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lingwrite.app.data.AdoptTo
import com.lingwrite.app.data.AiAdopt
import com.lingwrite.app.data.WriteMode
import com.lingwrite.app.data.ai.Prompts
import com.lingwrite.app.data.db.ChapterNoteEntity
import com.lingwrite.app.data.db.LingDatabase
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
 * MD1 红线回归（modes.md §2）：辅助模式下 AI 的产出绝不自动写正文。
 * 走的是生产代码真正调用的 [AiAdopt.adoptTo] / [AiAdopt.saveNote]（EditorAi.accept / saveNote 用的就是它们），
 * 不是只在测试里模拟一遍。
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = android.app.Application::class)
class ModeAcceptTest {
    private lateinit var db: LingDatabase
    private var bookId = 0L
    private var chapterId = 0L
    private val seedText = "沈砚把断剑收进袖里。"

    @Before fun seed() {
        AppGraph.init(ApplicationProvider.getApplicationContext(), inMemory = true)
        db = AppGraph.db
        runBlocking { bookId = AppGraph.books.createBook("红线测试") }
        chapterId = runBlocking { db.chapters().chaptersOnce(bookId).single().id }
        runBlocking { AppGraph.books.saveContent(chapterId, seedText) }
    }

    @Test fun assistDirectionOnlyWritesNoteNeverBody() = runBlocking {
        val before = db.chapters().get(chapterId)!!.content
        // 生产路径：accept() 先问 adoptTo（给方向 → Note），再走 AiAdopt.saveNote
        val to = AiAdopt.adoptTo(Prompts.directions.id, hasSelection = false)
        assertEquals(AdoptTo.Note, to)
        AiAdopt.saveNote(chapterId, "direction", "1. 让师父先开口。", logId = null)
        assertEquals("辅助模式采纳后正文必须一字不变", before, db.chapters().get(chapterId)!!.content)
        val notes = db.notes().notes(chapterId).first()
        assertEquals(1, notes.size)
        assertEquals("direction", notes.single().kind)
    }

    @Test fun assistToolbarHasNoInsertExitAtAll() {
        // 辅助模式无选区时，工具条上每个工具的采纳去向都必须是 Note
        Prompts.toolbarTools(WriteMode.Assist.id, hasSelection = false).forEach { t ->
            assertEquals("${t.id} 在辅助模式不该有落正文的出口", AdoptTo.Note, AiAdopt.adoptTo(t.id, hasSelection = false))
        }
    }

    @Test fun selectionRewriteIsTheOnlyBodyWriteAndIsMarkedEdited() = runBlocking {
        // 唯一例外：作者主动对选区发起的改写 → 替换选区，标 edited（不新增 AI 生成段落）
        assertEquals(AdoptTo.ReplaceSelection, AiAdopt.adoptTo(Prompts.rewrite.id, hasSelection = true))
        val st = EditorState(db.chapters().get(chapterId)!!)
        val at = st.value.text.indexOf("断剑")
        st.replace(at, at + 2, "长剑", "edited")
        AppGraph.books.saveContent(chapterId, st.value.text, com.lingwrite.app.data.encodeMarks(st.marks))
        val saved = db.chapters().get(chapterId)!!
        assertTrue(saved.content.contains("长剑"))
        assertTrue("应标记为 edited", saved.aiMarks.contains("edited"))
        assertTrue("不该标成 AI 生成段落", !saved.aiMarks.contains("\"gen\""))
        // 其余文字保持不动：替换只发生在选区内
        assertEquals(seedText.length, saved.content.length)
    }

    @Test fun generateCandidateInsertsAtCursor() {
        assertEquals(AdoptTo.InsertAtCursor, AiAdopt.adoptTo(Prompts.continueTask.id, hasSelection = false))
    }

    /** 便签落库与正文互不影响（连续存多条也不动正文）。 */
    @Test fun multipleNotesDoNotTouchBody() = runBlocking {
        repeat(3) { i ->
            db.notes().insert(ChapterNoteEntity(chapterId = chapterId, kind = "stuck", text = "方向 $i"))
        }
        assertEquals(seedText, db.chapters().get(chapterId)!!.content)
        assertEquals(3, db.notes().notes(chapterId).first().size)
    }
}
