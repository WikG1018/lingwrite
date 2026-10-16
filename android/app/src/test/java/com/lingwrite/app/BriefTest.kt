// SPDX-FileCopyrightText: 2026 WikG and LingWrite contributors
// SPDX-License-Identifier: AGPL-3.0-only
package com.lingwrite.app

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lingwrite.app.data.ContextEngine
import com.lingwrite.app.data.db.ChapterPlanEntity
import com.lingwrite.app.data.db.LingDatabase
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** F2：非正文任务的上下文构建（ai-features §0.2）。 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = android.app.Application::class)
class BriefTest {
    private lateinit var db: LingDatabase
    private var bookId = 0L

    @Before fun seed() {
        AppGraph.init(ApplicationProvider.getApplicationContext(), inMemory = true)
        db = AppGraph.db
        runBlocking { bookId = AppGraph.books.seedSample() }
        runBlocking {
            db.plan().upsertBook(com.lingwrite.app.data.db.BookPlanEntity(bookId = bookId, synopsis = "断剑少年入青云，十年旧案一线牵。"))
            val ch = db.chapters().chaptersOnce(bookId).first()
            db.plan().upsertChapter(ChapterPlanEntity(ch.id, bookId, event = "外门比试", hook = "雨夜来客", cliff = "剑鸣"))
        }
    }

    @Test fun briefIncludesRequestedParts() = runBlocking {
        val ch = db.chapters().chaptersOnce(bookId).first()
        val text = ContextEngine.brief(bookId, ch.id, setOf(ContextEngine.Part.Synopsis, ContextEngine.Part.ChapterPlan))
        assertTrue(text.contains("总纲"))
        assertTrue(text.contains("断剑少年"))
        assertTrue(text.contains("核心事件"))
        assertTrue(text.contains("外门比试"))
    }

    @Test fun briefOmitsUnrequestedParts() = runBlocking {
        val ch = db.chapters().chaptersOnce(bookId).first()
        val text = ContextEngine.brief(bookId, ch.id, setOf(ContextEngine.Part.Synopsis))
        assertTrue(text.contains("总纲"))
        assertTrue("未请求的部分不应出现", !text.contains("核心事件"))
    }

    @Test fun briefTrimsToBudget() = runBlocking {
        db.plan().upsertBook(com.lingwrite.app.data.db.BookPlanEntity(bookId = bookId, synopsis = "少年背着断剑走进山门。".repeat(80)))
        val ch = db.chapters().chaptersOnce(bookId).first()
        val long = ContextEngine.brief(bookId, ch.id, setOf(ContextEngine.Part.Synopsis), budget = 8000)
        val tight = ContextEngine.brief(bookId, ch.id, setOf(ContextEngine.Part.Synopsis), budget = 200)
        // 小预算下高优先级块被截断而不是删除
        assertTrue(tight.isNotEmpty())
        assertTrue("预算小时应截断：${tight.length} vs ${long.length}", tight.length < long.length)
    }
}
