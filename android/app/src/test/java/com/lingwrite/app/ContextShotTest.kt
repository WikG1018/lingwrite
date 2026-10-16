// SPDX-FileCopyrightText: 2026 WikG and LingWrite contributors
// SPDX-License-Identifier: AGPL-3.0-only
package com.lingwrite.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.ui.Modifier
import com.lingwrite.app.data.*
import com.lingwrite.app.data.db.*
import com.lingwrite.app.ui.components.LingTopBar
import com.lingwrite.app.ui.routes.ContextViewer
import com.lingwrite.app.ui.routes.SearchResults
import com.lingwrite.app.ui.theme.Ling
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class ContextShotTest : RouteShotBase() {
    @Test fun gramsAndChunks() {
        assertEquals("沈砚 砚说 abc", Grams.bigrams("沈砚说，ABC"))
        assertNull(Grams.phrase("刀")); assertEquals("刀", Grams.unis(listOf("刀")))
        assertEquals("\"师父 父的\" OR \"铜钱\"", Grams.query(listOf("师父的", "铜钱"), or = true))
        val ch = chunk("一".repeat(200) + "\n" + "二".repeat(200) + "\n短")
        assertEquals(2, ch.size); assertEquals(402, ch[1].first)
    }

    @Test fun budget() {
        val parts = listOf(CtxPart("plan", "章纲", 2.0, "章".repeat(160)), CtxPart("retrieved", "旧", 8.0, "旧".repeat(1600)), CtxPart("summary", "摘要", 7.0, "摘".repeat(800)))
        val f = fitBudget(parts, 400)
        assertTrue(f.sumOf { it.tokens } <= 400)
        assertEquals(listOf("plan", "summary"), f.map { it.key })
        assertEquals(1, fitBudget(listOf(CtxPart("plan", "章纲", 2.0, "章".repeat(3000))), 100).size)
    }

    @Test fun ftsAndBuild() = runBlocking {
        val chs = AppGraph.db.chapters().chaptersOnce(bookId)
        ContextEngine.refresh(bookId)
        val word = Grams.runs(chs.first().content).first { it.length >= 4 }.substring(1, 4)
        val hits = ContextEngine.search(word, bookId)
        assertTrue("命中 $word", hits.any { it.chapterId == chs.first().id })
        assertTrue(ContextEngine.search("不存在的词组合啊", bookId).isEmpty())
        val last = chs.maxBy { it.number }
        AppGraph.db.plan().upsertChapter(ChapterPlanEntity(last.id, bookId, event = "主角夜探"))
        val ctx = ContextEngine.build(bookId, last.id, last.content.length, last.content)
        assertTrue(ctx.contains("【当前章纲】")); assertTrue(ctx.contains("【前几章结尾】"))
        assertEquals(last.id, ContextEngine.last!!.chapterId)
    }

    @Test fun searchShot() {
        val hits = listOf(Hit(1, 1, 1, 3, 0, "沈砚把那枚铜钱翻过来，背面刻着一个极小的“砚”字。他忽然想起师父临走那晚说过的话。"),
            Hit(2, 1, 2, 11, 0, "林晚把铜钱推回他面前：“这东西你还是收好，城里认得它的人，比你想的多。”"),
            Hit(3, 1, 3, 27, 0, "铜钱落进雨水里，叮的一声。顾长风低头看了一眼，笑了。"))
        shoot("14-search") {
            Column(Modifier.fillMaxSize().background(Ling.colors.background)) {
                LingTopBar("搜索", "长夜问刀 · 铜钱", onBack = {})
                SearchResults("铜钱", hits, false, { "" }, { listOf("", "雨夜来客", "药铺", "城门").getOrElse(it.toInt()) { "" } }) {}
            }
        }
    }

    @Test fun viewerShot() {
        val snap = CtxSnapshot(1, "你是一位资深中文网络小说编辑兼写手……【文风要求】多用短句，对白口语化。", listOf(
            CtxPart("plan", "当前章纲", 2.0, "核心事件：沈砚夜探寿市\n开篇钩子：门缝里递进来一枚铜钱\n章尾悬念：寿市主人是他认识的人"),
            CtxPart("cursor", "本章已写内容（末尾）", 2.5, "雨下得更大了。".repeat(80)),
            CtxPart("lore", "出场人物与设定（第 42 章时）", 3.0, "· 沈砚（又名 砚哥）主角：境界：筑基；性格：沉默执拗\n· 林晚 女主：当前目标：替父还债"),
            CtxPart("volume", "当前卷纲", 4.0, "第二卷：寿市风云。".repeat(10)), CtxPart("synopsis", "总纲", 5.0, "落魄捕快追查师父之死……".repeat(8)),
            CtxPart("tail", "前几章结尾", 6.0, "（第 41 章结尾）".repeat(60)), CtxPart("summary", "前情摘要", 7.0, "第 30 章：……".repeat(90)),
            CtxPart("retrieved", "相关旧片段", 8.0, "（第 3 章）铜钱背面刻着砚字".repeat(20))), 22_000, System.currentTimeMillis() - 60_000)
        shoot("15-context") {
            Column(Modifier.fillMaxSize().background(Ling.colors.surface)) {
                LingTopBar("上下文查看器", onBack = {})
                ContextViewer(snap)
            }
        }
    }
}
