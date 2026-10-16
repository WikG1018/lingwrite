// SPDX-FileCopyrightText: 2026 WikG and LingWrite contributors
// SPDX-License-Identifier: AGPL-3.0-only
package com.lingwrite.app

import com.lingwrite.app.data.Breakdown
import com.lingwrite.app.data.BreakdownChapter
import com.lingwrite.app.data.BreakdownResult
import com.lingwrite.app.data.BreakdownTemplate
import com.lingwrite.app.data.FakeLlmRegistryHelper
import com.lingwrite.app.data.ReaderFeedback
import com.lingwrite.app.data.ReaderPrompts
import com.lingwrite.app.data.ReaderReport
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** M6：A12 拆文（分章、人名清洗、往返）与 A16 读者反馈（解析、指纹、费用估算）。 */
@RunWith(androidx.test.ext.junit.runners.AndroidJUnit4::class)
@Config(sdk = [34], application = android.app.Application::class)
class BreakdownTest {
    @Before fun seed() {
        AppGraph.init(ApplicationProvider.getApplicationContext(), inMemory = true)
    }

    @Test fun splitByChapterTitles() {
        val text = """
            第一章 雨夜
            沈砚推门进去，看见桌上的铜钱。
            
            第二章 旧案
            卷宗不见了，上司勒令结案。
            
            第三章 断剑
            断剑在袖中轻轻一震。
        """.trimIndent()
        val chapters = com.lingwrite.app.ui.routes.splitChapters(text)
        assertEquals(3, chapters.size)
        assertTrue(chapters[0].contains("雨夜"))
        assertTrue(chapters[1].contains("旧案"))
        assertTrue(chapters[2].contains("断剑"))
    }

    @Test fun cleanReplacesBannedNames() {
        val out = Breakdown.clean("沈砚在青云宗遇到林晚", listOf("沈砚", "林晚", "青云宗"))
        assertEquals("主角在配角1遇到对手", out)
    }

    @Test fun roundTripSerialization() {
        val r = BreakdownResult(
            name = "样章",
            chapters = listOf(BreakdownChapter(1, "主角受辱", "断剑", "反击", "身世", 4, "困境+金手指暗示")),
            template = listOf(BreakdownTemplate(1, "主角困境 + 金手指暗示")),
            notes = listOf("开篇三章强度递增"),
        )
        val chaptersJson = com.lingwrite.app.data.LingJson.encodeToString(ListSerializer(BreakdownChapter.serializer()), r.chapters)
        assertEquals(r.chapters, Breakdown.parseChapters(chaptersJson))
        val templateJson = com.lingwrite.app.data.LingJson.encodeToString(ListSerializer(BreakdownTemplate.serializer()), r.template)
        assertEquals(r.template, Breakdown.parseTemplate(templateJson))
    }

    /** 验收 §12：输出里不得残留原作人名；原文不落库。 */
    @Test fun analyzeStripsNamesAndNeverStoresText() = runBlocking {
        com.lingwrite.app.data.ai.KeyVault.override = ({ s: String -> s } to { s: String -> s })
        runBlocking {
            AppGraph.db.ai().insertProvider(com.lingwrite.app.data.db.ProviderEntity(
                presetId = "custom", name = "测", protocol = "openai", baseUrl = "http://x", auth = "bearer",
                models = "m1", keyCipher = "key",
            ))
        }
        val sample = "沈砚在擂台上拔出断剑，全场哗然。"
        FakeLlmRegistryHelper.withReply(
            """{"event":"主角在擂台拔出断剑","hook":"断剑现世","cool":"全场哗然","cliff":"长老变色","intensity":4,"pattern":"主角当众亮底牌"}""",
        ) {
            val r = Breakdown.analyze("样章", listOf(sample), bannedNames = listOf("沈砚")).getOrThrow()
            assertEquals(1, r.chapters.size)
            assertEquals(4, r.curve[0])
            // 数据库里搜不到原文
            AppGraph.db.breakdowns().let { dao ->
                val id = Breakdown.save(r, null)
                val saved = dao.get(id)!!
                assertFalse(saved.chapters.contains(sample))
            }
        }
    }

    /** 模板闭环：save → load 往返；applyToOutline 只填没章纲的章节且标 fromAi。 */
    @Test fun templateSaveLoadAndApplyToOutline() = runBlocking {
        val bookId = AppGraph.books.createBook("套用测试")
        AppGraph.books.nextChapter(bookId)
        AppGraph.books.nextChapter(bookId)
        val chs = AppGraph.db.chapters().chaptersOnce(bookId).sortedBy { it.number }
        // 第 1 章已有章纲（不覆盖），2、3 章空白
        AppGraph.db.plan().upsertChapter(com.lingwrite.app.data.db.ChapterPlanEntity(chs[0].id, bookId, event = "已有章纲"))

        val r = BreakdownResult(
            name = "样章模板",
            chapters = listOf(BreakdownChapter(1, pattern = "困境"), BreakdownChapter(2, pattern = "得助手")),
            template = listOf(BreakdownTemplate(1, "主角困境 + 悬念物件"), BreakdownTemplate(2, "得助手 + 线索升级")),
            notes = emptyList(),
        )
        val id = Breakdown.save(r, bookId)
        val loaded = Breakdown.load(id)!!
        assertEquals(r.template, loaded.template)
        assertEquals(r.name, loaded.name)

        val n = Breakdown.applyToOutline(bookId, r.template)
        assertEquals("只填了没章纲的 2 章", 2, n)
        val plans = AppGraph.db.plan().chaptersOnce(bookId).associateBy { it.chapterId }
        assertEquals("已有章纲不被覆盖", "已有章纲", plans[chs[0].id]!!.event)
        assertEquals("主角困境 + 悬念物件", plans[chs[1].id]!!.event)
        assertEquals("得助手 + 线索升级", plans[chs[2].id]!!.event)
        assertTrue("套用结果标 fromAi（作者可辨认）", plans[chs[1].id]!!.fromAi)
    }

    @Test fun readerReportParseAndStars() {
        val json = """{"stars":4,"oneLine":"想看下一章","drops":[{"quote":"他笑了","why":"反派降智"}],"hooks":["断剑认主"],"wantNext":"后山禁地","advice":["补一句动线"]}"""
        val r = ReaderFeedback.parse(json)!!
        assertEquals(4, r.stars)
        assertEquals("★★★★☆", r.starsLabel)
        assertEquals(1, r.drops.size)
        assertEquals("反派降智", r.drops[0].why)
    }

    @Test fun contentHashChangesWithText() {
        val h1 = ReaderFeedback.contentHash("第一稿")
        val h2 = ReaderFeedback.contentHash("第二稿")
        assertFalse(h1 == h2)
        assertEquals(h1, ReaderFeedback.contentHash("第一稿"))
    }

    @Test fun estTokensScalesWithTextAndPersonas() {
        val t1 = ReaderPrompts.estTokens("一二三四五六七八九十", 1)
        val t2 = ReaderPrompts.estTokens("一二三四五六七八九十", 2)
        assertEquals(t1 * 2, t2)
        assertTrue(t1 > 0)
    }

    /** A12：本地规则能从样章里认出反复出现的人名（UI 就是把它传给 Breakdown.analyze 的）。 */
    @Test fun extractNamesFindsRepeatedNames() {
        val text = "沈砚推门进去。沈砚看见桌上的铜钱。沈砚皱眉。沈砚把铜钱收好。沈砚转身离开。林晚说：等等。"
        val names = Breakdown.extractNames(text)
        assertTrue("应认出沈砚：$names", names.contains("沈砚"))
        assertTrue("不应认出句子里的普通词：$names", names.none { it == "进去" || it == "看见" })
    }

    /** 验收 §12 加强版：整库任意表、任意字段都搜不到原文的连续 20 字（原文只在内存里，不落库）。 */
    @Test fun noSourceFragmentAnywhereInDatabase() = runBlocking {
        com.lingwrite.app.data.ai.KeyVault.override = ({ s: String -> s } to { s: String -> s })
        AppGraph.db.ai().insertProvider(com.lingwrite.app.data.db.ProviderEntity(
            presetId = "custom", name = "测", protocol = "openai", baseUrl = "http://x", auth = "bearer",
            models = "m1", keyCipher = "key",
        ))
        val sample = "沈砚推门进去，看见桌上的铜钱，铜钱背面刻着他自己的名字，他把铜钱攥进手心。"
        FakeLlmRegistryHelper.withReply(
            """{"event":"主角查案时发现一枚刻着名字的铜钱","hook":"铜钱现身","cool":"线索浮现","cliff":"名字指向旧案","intensity":3,"pattern":"主角困境 + 悬念物件"}""",
        ) {
            val r = Breakdown.analyze("样章", listOf(sample), Breakdown.extractNames(sample)).getOrThrow()
            Breakdown.save(r, null)
        }
        assertFalse("数据库任意字段都不该出现原文的连续 20 字", dbContainsFragment(sample))
    }

    /** 全库扫描：任意表、任意列是否含 source 的连续 20 字。 */
    private fun dbContainsFragment(source: String): Boolean {
        if (source.length < 20) return false
        val grams = (0..source.length - 20).map { source.substring(it, it + 20) }
        val raw = AppGraph.db.openHelper.readableDatabase
        val tables = raw.query("SELECT name FROM sqlite_master WHERE type IN ('table','view')").use { c ->
            buildList { while (c.moveToNext()) add(c.getString(0)) }
        }
        tables.forEach { t ->
            runCatching {
                raw.query("SELECT * FROM `$t`").use { c ->
                    while (c.moveToNext()) {
                        for (i in 0 until c.columnCount) {
                            val v = runCatching { c.getString(i) }.getOrNull() ?: continue
                            if (grams.any { v.contains(it) }) return true
                        }
                    }
                }
            }
        }
        return false
    }
}
