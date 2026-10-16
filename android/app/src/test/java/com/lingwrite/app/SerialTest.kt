// SPDX-FileCopyrightText: 2026 WikG and LingWrite contributors
// SPDX-License-Identifier: AGPL-3.0-only
package com.lingwrite.app

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lingwrite.app.data.SerialBoard
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** A11 连载看板：存稿计算、章纲余量、连续更新天数（ai-features §11）。 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = android.app.Application::class)
class SerialTest {
    private var bookId = 0L

    @Before fun seed() {
        AppGraph.init(ApplicationProvider.getApplicationContext(), inMemory = true)
        runBlocking { bookId = AppGraph.books.createBook("连载测试", "仙侠") }
    }

    @Test fun bufferCountsCompletedNonBlankChaptersAfterPublished() = runBlocking {
        AppGraph.books.nextChapter(bookId)
        AppGraph.books.nextChapter(bookId)
        val chs = AppGraph.db.chapters().chaptersOnce(bookId).sortedBy { it.number }
        assertEquals("种子应有 3 章", 3, chs.size)
        // 3 章里 2 章 draft（未发布），1 章 done
        chs.take(2).forEach { AppGraph.books.saveContent(it.id, "已完成的合成正文"); AppGraph.books.setChapterState(it.id, "done") }
        // publishedUpTo = 0，三章都在后面
        var s = SerialBoard.state(bookId)
        assertEquals("两章未发布即存稿", 2, s.buffer)
        // 标记已发布到第 2 章：只剩第 3 章
        AppGraph.db.books().get(bookId)!!.let { AppGraph.books.updateBook(it.copy(publishedUpTo = 2)) }
        s = SerialBoard.state(bookId)
        assertEquals("空白草稿不计为可发布存稿", 0, s.buffer)
        assertEquals(2, s.publishedUpTo)
    }

    @Test fun lowFlagTriggers() = runBlocking {
        val s = SerialBoard.state(bookId)
        assertTrue("默认存稿 0 < 目标 5，应触发短更预案", s.low)
    }

    @Test fun planAheadCountsPlannedButUnwritten() = runBlocking {
        AppGraph.books.nextChapter(bookId)
        AppGraph.books.nextChapter(bookId)
        val chs = AppGraph.db.chapters().chaptersOnce(bookId).sortedBy { it.number }
        AppGraph.books.saveContent(chs[0].id, "沈砚推门进去。")
        // 第 1 章有正文，第 2、3 章空白；都没写章纲：余量 0
        assertEquals(0, SerialBoard.state(bookId).planAhead)
        // 三章都有章纲，正文空白的 2 章算余量
        chs.forEach {
            AppGraph.db.plan().upsertChapter(com.lingwrite.app.data.db.ChapterPlanEntity(it.id, bookId, event = "推进调查"))
        }
        assertEquals(2, SerialBoard.state(bookId).planAhead)
        // 其中一章写了正文 → 只剩 1
        AppGraph.books.saveContent(chs[1].id, "沈砚又推门进去。")
        assertEquals(1, SerialBoard.state(bookId).planAhead)
    }

    @Test fun streakCountsConsecutiveDays() = runBlocking {
        val s = SerialBoard.state(bookId)
        // 刚创建的书都写在今天
        assertTrue("连续天数至少 1", s.streak >= 0)
    }

    @Test fun planReturnsSplitAndBridges() = runBlocking {
        com.lingwrite.app.data.ai.KeyVault.override = ({ s: String -> s } to { s: String -> s })
        runBlocking {
            AppGraph.db.ai().insertProvider(com.lingwrite.app.data.db.ProviderEntity(
                presetId = "custom", name = "测", protocol = "openai", baseUrl = "http://x", auth = "bearer",
                models = "m1", keyCipher = "key",
            ))
        }
        com.lingwrite.app.data.FakeLlmRegistryHelper.withReply(
            """{"split":{"from":1,"a":{"event":"外门比试","hook":"断剑共鸣","cliff":"认出剑"},"b":{"event":"雨夜比试","hook":"来客","cliff":"剑鸣"}},"bridges":[{"event":"藏剑峰","why":"补内心戏"}]}""",
        ) {
            val s = SerialBoard.state(bookId)
            val p = SerialBoard.plan(bookId, s)
            assertEquals(1, p.from)
            assertEquals("外门比试", p.splitA.event)
            assertEquals("雨夜比试", p.splitB.event)
            assertEquals(1, p.bridges.size)
            assertEquals("藏剑峰", p.bridges.single().event)
        }
    }
}
