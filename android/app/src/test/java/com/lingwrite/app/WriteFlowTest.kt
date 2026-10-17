// SPDX-FileCopyrightText: 2026 WikG and LingWrite contributors
// SPDX-License-Identifier: AGPL-3.0-only
package com.lingwrite.app

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lingwrite.app.data.WriteFlow
import com.lingwrite.app.data.db.ChapterPlanEntity
import com.lingwrite.app.data.db.ForeshadowEntity
import com.lingwrite.app.data.db.SummaryEntity
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = Application::class)
class WriteFlowTest {
    private val ctx get() = ApplicationProvider.getApplicationContext<Application>()

    @Before fun seed() { AppGraph.init(ctx, inMemory = true) }

    @Test fun beforeAndAfterGatherLocalState() = runBlocking {
        val book = AppGraph.books.createBook("写作流")
        val ch = AppGraph.db.chapters().chaptersOnce(book).single()
        AppGraph.books.saveContent(ch.id, "沈砚推门进来。", "[]")
        AppGraph.db.plan().upsertChapter(ChapterPlanEntity(chapterId = ch.id, bookId = book, event = "沈砚登场", hook = "门响", cliff = "灯灭", storyTime = "第三天", landed = """{"event":true,"hook":false,"cliff":true,"note":""}"""))
        AppGraph.db.checks().insertForeshadow(ForeshadowEntity(bookId = book, content = "断剑的缺口", plantNumber = 1, payoff = 2))
        AppGraph.db.context().upsertSummary(SummaryEntity(ch.id, book, "摘要"))

        val b = WriteFlow.before(book, ch.id, 2)
        assertEquals("沈砚登场", b.plan?.event)
        assertTrue(b.foreshadows.any { it.content == "断剑的缺口" })
        val a = WriteFlow.after(book, ch.id)
        assertTrue(a.planFilled)
        assertEquals(listOf("hook"), a.landedGaps)
        assertTrue(a.openForeshadows >= 1)
        assertTrue(a.wordCount > 0)
    }
}
