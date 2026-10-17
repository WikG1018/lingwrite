// SPDX-FileCopyrightText: 2026 WikG and LingWrite contributors
// SPDX-License-Identifier: AGPL-3.0-only
package com.lingwrite.app.data

import com.lingwrite.app.AppGraph
import com.lingwrite.app.data.ai.JsonReply
import com.lingwrite.app.data.ai.PromptContract
import com.lingwrite.app.data.db.BookEntity
import com.lingwrite.app.data.db.ChapterEntity
import kotlinx.coroutines.flow.first
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject

/** 连载看板（A11，ai-features §11）：存稿、日更、章纲余量、连续更新天数。 */
data class SerialState(
    val todayWords: Int,
    val dailyTarget: Int,
    val buffer: Int,
    val bufferTarget: Int,
    val streak: Int,
    val planAhead: Int,
    val publishedUpTo: Int,
) {
    /** 存稿不足或章纲余量 < 3 章时卡片变琥珀色，显示“短更预案”。 */
    val low: Boolean get() = buffer < bufferTarget || planAhead < 3
}

/** 短更预案（A11）：拆章方案 + 两个过渡章思路。 */
@Serializable
data class ShortUpdatePlan(
    val from: Int = 0,
    val splitA: PlanFields = PlanFields(),
    val splitB: PlanFields = PlanFields(),
    val bridges: List<BridgeIdea> = emptyList(),
)

@Serializable
data class PlanFields(val event: String = "", val hook: String = "", val cliff: String = "")

@Serializable
data class BridgeIdea(val event: String = "", val why: String = "")

object SerialBoard {
    /** 存稿：draft=false 且排在“已发布到”之后的章节数。 */
    suspend fun state(bookId: Long): SerialState {
        val db = AppGraph.db
        val b = db.books().get(bookId) ?: return SerialState(0, 0, 0, 5, 0, 0, 0)
        val chs = db.chapters().headersOnce(bookId)
        val buffer = chs.count { it.state == "done" && it.content.isNotBlank() && it.number > b.publishedUpTo }
        val plans = db.plan().chapters(bookId).first().associateBy { it.chapterId }
        // 章纲余量：已有章纲、正文还没写的章数
        val planAhead = chs.count { it.content.isBlank() && plans[it.id]?.isEmpty() == false }
        val today = db.stats().wordDay(TimeFmt.day(System.currentTimeMillis()), bookId)?.words ?: 0
        val streak = streakDates(db.stats().bookWordDays(bookId).first().filter { it.words > 0 }.map { it.day })
        return SerialState(today, b.dailyTarget, buffer, b.bufferTarget, streak, planAhead, b.publishedUpTo)
    }

    /** 连续更新天数：有字数写入的连续自然日。 */
    fun streakDates(writtenDays: List<String>, today: java.time.LocalDate = java.time.LocalDate.now()): Int {
        val days = writtenDays.distinct().sortedDescending()
        if (days.isEmpty()) return 0
        if (java.time.LocalDate.parse(days.first()) < today.minusDays(1)) return 0
        var n = 1
        for (i in 1 until days.size) {
            val prev = java.time.LocalDate.parse(days[i])
            val cur = java.time.LocalDate.parse(days[i - 1])
            if (java.time.temporal.ChronoUnit.DAYS.between(prev, cur) == 1L) n++ else break
        }
        return n
    }

    val SYS = "你是连载节奏顾问。帮作者在不注水的前提下维持更新。只给章纲级提纲，不写正文。\n" + PromptContract.JSON_ONLY

    fun prompt(b: BookEntity, s: SerialState, upcoming: String, summaries: String): String = buildString {
        append("【存稿】").append(s.buffer).append(" 章，目标 ").append(s.bufferTarget).append('\n')
        if (upcoming.isNotBlank()) append("【后续章纲】\n").append(upcoming).append('\n')
        if (summaries.isNotBlank()) append("【最近 3 章摘要】\n").append(summaries)
        append("\n给出 1 个拆章方案：把 from 章拆成 a/b 两章（a/b 各自有完整的小事件、钩子和章尾悬念，不能注水），再给 1–2 个可插入的过渡章纲。\n")
        append(PromptContract.JSON_ONLY).append('\n')
        append("格式：{\"split\":{\"from\":章号,\"a\":{\"event\":\"\",\"hook\":\"\",\"cliff\":\"\"},\"b\":{\"event\":\"\",\"hook\":\"\",\"cliff\":\"\"}},\"bridges\":[{\"event\":\"\",\"why\":\"≤30字\"}]}")
    }

    /** 短更预案：拆下一章成两章 + 两个过渡章思路。 */
    suspend fun plan(bookId: Long, s: SerialState): ShortUpdatePlan {
        val db = AppGraph.db
        val b = db.books().get(bookId) ?: return ShortUpdatePlan()
        val chs = db.chapters().headersOnce(bookId)
        val upcoming = chs.filter { it.number > b.publishedUpTo && (it.state != "done" || it.content.isBlank()) }.take(5).mapNotNull { ch ->
            db.plan().chapterOnce(ch.id)?.takeIf { !it.isEmpty() }?.let { "第 ${ch.number} 章：${it.event}" }
        }.joinToString("\n")
        val sums = db.context().summaries(bookId).associateBy { it.chapterId }
        val summaries = chs.takeLast(3).mapNotNull { c -> sums[c.id]?.let { "第 ${c.number} 章：${it.text}" } }.joinToString("\n")
        val raw = AppGraph.ai.complete("outline", SYS, prompt(b, s, upcoming, summaries), bookId, null, 1200).getOrThrow()
        val o = (JsonReply.parse(raw, array = false) as? JsonReply.Result.Ok)?.json as? kotlinx.serialization.json.JsonObject
            ?: extractJson(raw, array = false)?.jsonObject ?: error("模型没有按格式返回")
        val split = o["split"]?.jsonObject
        val a = split?.get("a")?.jsonObject
        val bb = split?.get("b")?.jsonObject
        val bridges = runCatching {
            (o["bridges"]?.jsonArray ?: emptyList()).map { e ->
                val bo = e.jsonObject
                BridgeIdea(bo.str("event"), bo.str("why"))
            }
        }.getOrDefault(emptyList())
        return ShortUpdatePlan(
            from = split?.str("from")?.toIntOrNull() ?: 0,
            splitA = PlanFields(a?.str("event").orEmpty(), a?.str("hook").orEmpty(), a?.str("cliff").orEmpty()),
            splitB = PlanFields(bb?.str("event").orEmpty(), bb?.str("hook").orEmpty(), bb?.str("cliff").orEmpty()),
            bridges = bridges,
        )
    }
}
