// SPDX-FileCopyrightText: 2026 WikG and LingWrite contributors
// SPDX-License-Identifier: AGPL-3.0-only
package com.lingwrite.app.data

import com.lingwrite.app.data.ai.AiService
import com.lingwrite.app.data.db.CallLogEntity
import com.lingwrite.app.data.db.ProviderEntity
import java.time.*
import java.time.temporal.TemporalAdjusters

enum class Period(val label: String) { Day("日"), Week("周"), Month("月") }

data class Range(val from: Long, val to: Long, val label: String, val start: LocalDate)

fun periodRange(p: Period, offset: Int, today: LocalDate = LocalDate.now(), zone: ZoneId = ZoneId.systemDefault()): Range {
    fun ms(d: LocalDate) = d.atStartOfDay(zone).toInstant().toEpochMilli()
    return when (p) {
        Period.Day -> today.plusDays(offset.toLong()).let { d -> Range(ms(d), ms(d.plusDays(1)), "${d.year} 年 ${d.monthValue} 月 ${d.dayOfMonth} 日", d) }
        Period.Week -> today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).plusWeeks(offset.toLong()).let { d ->
            val e = d.plusDays(6); Range(ms(d), ms(d.plusDays(7)), "${d.monthValue}月${d.dayOfMonth}日 – ${e.monthValue}月${e.dayOfMonth}日", d)
        }
        Period.Month -> today.withDayOfMonth(1).plusMonths(offset.toLong()).let { d -> Range(ms(d), ms(d.plusMonths(1)), "${d.year} 年 ${d.monthValue} 月", d) }
    }
}

data class Bucket(val label: String, val calls: Int, val tokens: Long)
data class Group(val key: String, val label: String, val sub: String, val calls: Int, val inTok: Long, val outTok: Long, val cost: Double?)
data class StatsData(
    val calls: Int, val inTok: Long, val outTok: Long, val cost: Double?, val unpriced: List<String>,
    val buckets: List<Bucket>, val byModel: List<Group>, val byTask: List<Group>,
)

fun taskName(t: String) = when (t) {
    "continue" -> "续写"; "directions" -> "给方向"; "rewrite" -> "改写"; "expand" -> "扩写"; "polish" -> "润色"; "deai" -> "去 AI 腔"; "dialog" -> "对话"
    "describe" -> "描写"; "shorten" -> "缩写"; "annotate" -> "批注修改"; "summary" -> "摘要"; "check" -> "自动检查"; "extract" -> "设定提取"; "foreshadow" -> "伏笔建议"
    "wizard" -> "创作向导"; "style" -> "文风学习"; "aitaste" -> "AI 味改法"; "ask" -> "随手问"; "outline" -> "拆章纲"; "embed" -> "语义检索"; else -> t
}

fun aggregate(logs: List<CallLogEntity>, period: Period, range: Range, providers: List<ProviderEntity>, zone: ZoneId = ZoneId.systemDefault()): StatsData {
    val prices = providers.associate { it.name to AiService.prices(it.prices) }
    fun costOf(l: CallLogEntity) = prices[l.provider]?.get(l.model)?.let { (i, o) -> l.inputTokens / 1e6 * i + l.outputTokens / 1e6 * o }
    val n = when (period) { Period.Day -> 24; Period.Week -> 7; Period.Month -> range.start.lengthOfMonth() }
    val calls = IntArray(n); val toks = LongArray(n)
    logs.forEach { l ->
        val t = Instant.ofEpochMilli(l.time).atZone(zone)
        val i = when (period) { Period.Day -> t.hour; else -> (t.toLocalDate().toEpochDay() - range.start.toEpochDay()).toInt() }
        if (i in 0 until n) { calls[i]++; toks[i] += (l.inputTokens + l.outputTokens).toLong() }
    }
    val wk = listOf("一", "二", "三", "四", "五", "六", "日")
    val buckets = (0 until n).map { i -> Bucket(when (period) { Period.Day -> "$i"; Period.Week -> wk[i]; Period.Month -> "${i + 1}" }, calls[i], toks[i]) }
    fun group(key: (CallLogEntity) -> String, label: (String) -> String, sub: (List<CallLogEntity>) -> String) = logs.groupBy(key).map { (k, l) ->
        val costs = l.map(::costOf)
        Group(k, label(k), sub(l), l.size, l.sumOf { it.inputTokens.toLong() }, l.sumOf { it.outputTokens.toLong() }, if (costs.all { it == null }) null else costs.sumOf { it ?: 0.0 })
    }.sortedByDescending { it.calls }
    val costs = logs.map(::costOf)
    return StatsData(
        logs.size, logs.sumOf { it.inputTokens.toLong() }, logs.sumOf { it.outputTokens.toLong() },
        if (costs.all { it == null }) null else costs.sumOf { it ?: 0.0 },
        logs.filter { costOf(it) == null }.map { it.model }.distinct(),
        buckets,
        group({ it.provider + "|" + it.model }, { it.substringAfter('|') }, { it.first().provider }),
        group({ it.task }, ::taskName, { l -> "${l.count { it.outcome == "accepted" || it.outcome == "edited" }} 次采纳" }),
    )
}
