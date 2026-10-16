// SPDX-FileCopyrightText: 2026 WikG and LingWrite contributors
// SPDX-License-Identifier: AGPL-3.0-only
package com.lingwrite.app

import com.lingwrite.app.data.*
import com.lingwrite.app.data.db.*
import com.lingwrite.app.ui.routes.IdeasScreen
import com.lingwrite.app.ui.routes.StatsScreen
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

class StatsShotTest : RouteShotBase() {
    private val zone = ZoneId.of("Asia/Shanghai")
    private val today = LocalDate.of(2026, 10, 12)
    private val range = periodRange(Period.Month, 0, today, zone)
    private val prov = listOf(ProviderEntity(presetId = "deepseek", name = "DeepSeek", protocol = "openai", baseUrl = "", auth = "bearer", models = "deepseek-chat", prices = """{"deepseek-chat":[2.0,8.0]}"""),
        ProviderEntity(presetId = "kimi", name = "Kimi", protocol = "openai", baseUrl = "", auth = "bearer", models = "kimi-k2"))
    private val logs = (0 until 160).map { i ->
        val d = today.withDayOfMonth(1 + (i * 7 % 12)).atStartOfDay(zone).plusHours((9 + i % 12).toLong())
        CallLogEntity(time = d.toInstant().toEpochMilli(), provider = if (i % 4 == 0) "Kimi" else "DeepSeek", model = if (i % 4 == 0) "kimi-k2" else "deepseek-chat",
            task = listOf("continue", "polish", "summary", "check", "rewrite", "foreshadow", "ask")[i % 7], inputTokens = 3000 + i * 37, outputTokens = 300 + i * 5, outcome = if (i % 3 == 0) "accepted" else "auto")
    }

    @Test fun aggregateMath() {
        val d = aggregate(logs, Period.Month, range, prov, zone)
        assertEquals(160, d.calls); assertEquals(31, d.buckets.size)
        assertEquals(160, d.buckets.sumOf { it.calls })
        assertEquals(listOf("kimi-k2"), d.unpriced)
        val ds = logs.filter { it.provider == "DeepSeek" }
        assertEquals(ds.sumOf { it.inputTokens / 1e6 * 2 + it.outputTokens / 1e6 * 8 }, d.cost!!, 1e-9)
        assertEquals(7, d.byTask.size)
        assertEquals("10月12日 – 10月18日", periodRange(Period.Week, 0, today, zone).label)
        assertEquals(24, aggregate(logs, Period.Day, periodRange(Period.Day, 0, today, zone), prov, zone).buckets.size)
    }

    @Test fun wordDays() = runBlocking {
        val ch = AppGraph.db.chapters().chaptersOnce(bookId).first()
        AppGraph.books.saveContent(ch.id, ch.content + "新写了十个字的一句话。")
        val w = AppGraph.db.stats().wordDays("2000-01-01").first()
        assertTrue(w.sumOf { it.words } >= 10)
    }

    @Test fun statsShot() {
        val words = (0..13).map { WordDayEntity(LocalDate.now().minusDays(it.toLong()).toString(), 1, listOf(4200, 3100, 0, 5200, 2800, 4100, 3900)[it % 7]) }
        shoot("20-stats") { StatsScreen(Period.Month, range.label, aggregate(logs, Period.Month, range, prov, zone), words) }
    }

    @Test fun ideasShot() {
        val now = System.currentTimeMillis()
        val ideas = listOf(
            IdeaEntity(1, "主角的刀其实是师父的骨头炼的——第三卷揭晓", pinned = true, createdAt = now - 3_600_000),
            IdeaEntity(2, "反派出场时一直在剥橘子，橘子皮整整一条不断", usedAs = "card", createdAt = now - 86_400_000),
            IdeaEntity(3, "两条线并行时，怎么避免读者觉得副线拖沓？", kind = "ask", answer = "1. 给副线定一个倒计时，让它每次出现都更紧迫；\n2. 副线每章只写一个场景，结尾必须改变主线的局面；\n3. 用主角视角的“缺席”制造悬念，而不是平铺。", createdAt = now - 2 * 86_400_000),
        )
        shoot("21-ideas") { IdeasScreen(ideas, false, {}, {}, {}) }
    }
}
