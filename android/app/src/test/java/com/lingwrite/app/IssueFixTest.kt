// SPDX-FileCopyrightText: 2026 WikG and LingWrite contributors
// SPDX-License-Identifier: AGPL-3.0-only
package com.lingwrite.app

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lingwrite.app.data.IssueFixes
import com.lingwrite.app.data.WriteMode
import com.lingwrite.app.data.db.IssueEntity
import com.lingwrite.app.data.db.LingDatabase
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * A6 一致性问题「给改法」的采纳链路（ai-features §6）：
 * 真实驱动 [IssueFixes.adopt]（IssueCard 的 onFix 按钮调的就是它），断言状态与落点。
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = android.app.Application::class)
class IssueFixTest {
    private lateinit var db: LingDatabase
    private var chapterId = 0L
    private var issueId = 0L

    @Before fun seed() {
        AppGraph.init(ApplicationProvider.getApplicationContext(), inMemory = true)
        db = AppGraph.db
        IssueFixes.PendingFix.clear()
        runBlocking {
            val bookId = AppGraph.books.createBook("改法测试")
            chapterId = db.chapters().chaptersOnce(bookId).single().id
            AppGraph.books.saveContent(chapterId, "沈砚在第三章说自己没有灵根。")
            issueId = db.checks().insertIssue(
                IssueEntity(bookId = bookId, chapterId = chapterId, number = 3, kind = "power", quote = "没有灵根", start = 5, basis = "设定：沈砚灵根已开", explain = "与设定矛盾", status = "open"),
            )
        }
    }

    private fun issue() = runBlocking { db.checks().issue(issueId)!! }

    @Test fun assistModeAdoptionGoesToNoteAndMarksFixed() = runBlocking {
        val before = db.chapters().get(chapterId)!!.content
        val jump = IssueFixes.adopt(issue(), "text", "其实他的灵根被封印了。", WriteMode.Assist)
        // 辅助模式：不跳编辑器、不动正文，只进便签
        assertNull(jump)
        assertEquals("fixed", db.checks().issue(issueId)!!.status)
        assertEquals("辅助模式采纳改法后正文必须不变", before, db.chapters().get(chapterId)!!.content)
        val notes = db.notes().notes(chapterId).first()
        assertEquals(1, notes.size)
        assertTrue(notes.single().text.contains("改正文建议"))
    }

    @Test fun generateModeAdoptionQueuesPendingFix() = runBlocking {
        val before = db.chapters().get(chapterId)!!.content
        val jump = IssueFixes.adopt(issue(), "text", "其实他的灵根被封印了。", WriteMode.Generate)
        // 生成模式：把改法交给编辑器待确认
        assertEquals(chapterId, jump)
        assertTrue(IssueFixes.PendingFix.active)
        assertEquals(chapterId, IssueFixes.PendingFix.chapterId)
        assertEquals("没有灵根", IssueFixes.PendingFix.before)
        assertEquals("其实他的灵根被封印了。", IssueFixes.PendingFix.after)
        assertEquals("fixed", db.checks().issue(issueId)!!.status)
        assertEquals("确认前正文不能变", before, db.chapters().get(chapterId)!!.content)
    }

    @Test fun loreAndBridgeFixesGoToNoteAndMarkFixed() = runBlocking {
        IssueFixes.adopt(issue(), "lore", "把灵根设定改成封印状态。", WriteMode.Generate)
        assertEquals("fixed", db.checks().issue(issueId)!!.status)
        assertTrue(!IssueFixes.PendingFix.active)
        assertNotNull(db.notes().notes(chapterId).first().firstOrNull())
    }

    @Test fun fixCacheRoundTrip() {
        val json = com.lingwrite.app.data.LingJson.encodeToString(
            kotlinx.serialization.builtins.ListSerializer(com.lingwrite.app.data.IssueFix.serializer()),
            listOf(com.lingwrite.app.data.IssueFix("text", "没有灵根", "灵根被封印", "与设定冲突")),
        )
        val back = IssueFixes.parseFixes(json)
        assertEquals(1, back.size)
        assertEquals("灵根被封印", back[0].after)
    }
}
