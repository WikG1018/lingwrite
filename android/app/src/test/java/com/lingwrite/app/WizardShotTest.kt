// SPDX-FileCopyrightText: 2026 WikG and LingWrite contributors
// SPDX-License-Identifier: AGPL-3.0-only
package com.lingwrite.app

import com.lingwrite.app.data.*
import com.lingwrite.app.ui.routes.WizardScreen
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

private val demo = WizardData(
    idea = "一个能看见别人寿命的捕快，发现师父的寿命停在了三年前。",
    pitches = listOf(Pitch("问寿", "捕快沈砚天生能看见他人余寿。师父“病故”三年后，他在一具无名尸身上看见了师父的寿数……"),
        Pitch("长夜问刀", "王朝末年，寿命可以买卖。沈砚追查师父失踪案，卷入一场以命换命的黑市交易。"),
        Pitch("寿簿", "地府寿簿流落人间，沈砚意外成为持簿人，第一页写着的是早已下葬的师父。")),
    premise = "王朝末年，寿命可以买卖。沈砚追查师父失踪案，卷入一场以命换命的黑市交易。",
    genre = "悬疑", reader = "男频",
    chars = listOf(CharDraft("沈砚", "主角", "砚哥", "县衙捕快", "沉默、执拗、心软", "查清师父下落", "看见他人余寿"),
        CharDraft("林晚", "女主", "", "药铺掌柜", "机敏、嘴硬", "替父还债"), CharDraft("顾长风", "反派", "", "寿市主人", "温和而冷酷", "长生", take = false)),
)

class WizardShotTest : RouteShotBase() {
    @Test fun step1() = shoot("12-wizard-idea") { WizardScreen(0, demo, {}, {}, {}, {}, {}) }
    @Test fun step3() = shoot("13-wizard-chars") { WizardScreen(2, demo, {}, {}, {}, {}, {}) }

    @Test fun adoptWritesModules() = runBlocking {
        val b = AppGraph.books.createBook("", status = "wizard")
        val d = demo.copy(world = listOf(WorldDraft("power", "寿数", "人皆有寿，可典可卖")), synopsis = "总纲", volumeOutline = "卷纲", title = "长夜问刀", blurb = "简介",
            chapters = listOf(ChapterDraft("雨夜", "命案", "敲门", "铜钱"), ChapterDraft("药铺", "结识林晚"), ChapterDraft("卷宗", "卷宗失踪")))
        for (s in 1..6) com.lingwrite.app.ui.routes.adopt(b, s, d)
        com.lingwrite.app.ui.routes.finish(b, d, true)
        val cards = AppGraph.db.lore().cardsOnce(b)
        assertEquals(setOf("沈砚", "林晚", "寿数"), cards.map { it.name }.toSet())
        assertEquals("always", cards.first { it.name == "沈砚" }.inject)
        assertEquals("总纲", AppGraph.db.plan().bookOnce(b)!!.synopsis)
        val book = AppGraph.db.books().get(b)!!
        assertEquals("长夜问刀", book.title); assertEquals("outline", book.status); assertEquals("悬疑", book.genre)
        val chs = AppGraph.db.chapters().chaptersOnce(b)
        assertEquals(3, chs.size)
        assertEquals("命案", AppGraph.db.plan().chapterOnce(chs.first { it.number == 1 }.id)!!.event)
        assertEquals(d, WizardData.parse(d.encode()))
    }
}
