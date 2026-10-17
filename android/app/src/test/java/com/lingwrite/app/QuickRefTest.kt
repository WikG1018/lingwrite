// SPDX-FileCopyrightText: 2026 WikG and LingWrite contributors
// SPDX-License-Identifier: AGPL-3.0-only
package com.lingwrite.app

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lingwrite.app.data.*
import com.lingwrite.app.data.db.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = Application::class)
class QuickRefTest {
    private val ctx get() = ApplicationProvider.getApplicationContext<Application>()

    @Before fun seed() { AppGraph.init(ctx, inMemory = true) }

    @Test fun cardsResolveCurrentChapterStateAndForeshadows() = runBlocking {
        val book = AppGraph.books.createBook("速查测试")
        val chapter = AppGraph.db.chapters().chaptersOnce(book).single()
        AppGraph.books.saveContent(chapter.id, "沈砚推门进来，手里拿着断剑。")
        AppGraph.db.plan().upsertChapter(ChapterPlanEntity(chapter.id, book, event = "沈砚登场"))
        val card = AppGraph.db.lore().insertCard(LoreCardEntity(bookId = book, type = "character", name = "沈砚", fields = encodeFields(listOf(Field("境界", "炼气"), Field("性格", "冷静")))))
        AppGraph.db.lore().insertState(LoreStateEntity(cardId = card, fromChapter = 1, field = "境界", value = "筑基"))
        AppGraph.db.checks().insertForeshadow(ForeshadowEntity(bookId = book, content = "断剑的缺口", plantNumber = 1, payoff = 2))
        val current = AppGraph.db.chapters().get(chapter.id)!!

        val cards = QuickRef.cards(book, chapter.id, 1, current.content)
        assertEquals(1, cards.size)
        assertEquals("沈砚", cards[0].card.name)
        assertEquals("筑基", cards[0].fields.first { it.k == "境界" }.v)

        val fs = QuickRef.foreshadows(book, 2, current.content)
        assertEquals(1, fs.size)
        assertEquals("断剑的缺口", fs[0].content)
    }

    @Test fun summariesReturnPreviousChaptersAndVolume() = runBlocking {
        val book = AppGraph.books.createBook("摘要测试")
        val first = AppGraph.db.chapters().headersOnce(book).single()
        AppGraph.db.chapters().updateContent(first.id, "第一章正文", 100, "[]", System.currentTimeMillis())
        val second = AppGraph.books.nextChapter(book)
        AppGraph.db.chapters().updateContent(second, "第二章正文", 100, "[]", System.currentTimeMillis())
        AppGraph.db.context().upsertSummary(SummaryEntity(first.id, book, "第一章摘要"))
        val ch2 = AppGraph.db.chapters().get(second)!!
        AppGraph.db.context().upsertVolumeSummary(VolumeSummaryEntity(ch2.volumeId, book, "本卷摘要"))
        val (lines, volume) = QuickRef.summaries(book, second, ch2.number)
        assertEquals(listOf(1), lines.map { it.number })
        assertEquals("第一章摘要", lines[0].text)
        assertEquals("本卷摘要", volume?.text)
    }
}
