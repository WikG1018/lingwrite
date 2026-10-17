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
class TimelineTest {
    private val ctx get() = ApplicationProvider.getApplicationContext<Application>()

    @Before fun seed() { AppGraph.init(ctx, inMemory = true) }

    @Test fun buildAndFilterTimeline() = runBlocking {
        val book = AppGraph.books.createBook("时间线")
        val ch = AppGraph.db.chapters().chaptersOnce(book).single()
        AppGraph.db.chapters().updateContent(ch.id, "正文", 100, "[]", System.currentTimeMillis())
        val hero = AppGraph.db.lore().insertCard(LoreCardEntity(bookId = book, type = "character", name = "沈砚"))
        val place = AppGraph.db.lore().insertCard(LoreCardEntity(bookId = book, type = "place", name = "雾港"))
        AppGraph.db.lore().insertState(LoreStateEntity(cardId = hero, fromChapter = 1, field = "境界", value = "筑基"))
        AppGraph.db.lore().insertState(LoreStateEntity(cardId = place, fromChapter = 2, field = "状态", value = "封城"))
        AppGraph.db.lore().insertRelation(LoreRelationEntity(bookId = book, fromId = hero, toId = hero, label = "自我", fromChapter = 1))
        AppGraph.db.plan().upsertChapter(ChapterPlanEntity(chapterId = ch.id, bookId = book, event = "沈砚来到雾港", storyTime = "第三天清晨"))
        AppGraph.db.checks().insertForeshadow(ForeshadowEntity(bookId = book, content = "断剑的缺口", plantNumber = 1, payoff = 3))

        val all = Timeline.build(book)
        assertTrue(all.any { it.kind == "lore" && it.title.contains("境界") })
        assertTrue(all.any { it.kind == "lore" && it.cardType == "place" })
        assertTrue(all.any { it.kind == "relation" })
        assertTrue(all.any { it.kind == "plan" && it.storyTime == "第三天清晨" })
        assertTrue(all.any { it.kind == "foreshadow" })
        assertTrue(all.any { it.kind == "payoff" && it.chapter == 3 })
        assertEquals(all.map { it.chapter }, all.map { it.chapter }.sorted())

        assertEquals(1, Timeline.filter(all, "place").size)
        assertTrue(Timeline.filter(all, "person").all { it.cardType == "character" })
        assertTrue(Timeline.filter(all, "foreshadow").all { it.kind == "foreshadow" || it.kind == "payoff" })
        assertTrue(Timeline.filter(all, "plan").all { it.kind == "plan" })
    }
}
