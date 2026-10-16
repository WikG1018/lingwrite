// SPDX-FileCopyrightText: 2026 WikG and LingWrite contributors
// SPDX-License-Identifier: AGPL-3.0-only
package com.lingwrite.app.data

import com.lingwrite.app.AppGraph
import com.lingwrite.app.data.ai.JsonReply
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** 卡文急救（A5，ai-features §5）：先澄清问题，再给 3 个方向。 */
object StuckPrompts {
    const val SYS = "你是陪作者过卡点的编辑。只提问题和方向，不写小说正文（引用原文除外）。每个方向要具体到“谁做什么、带来什么变化”。严格只输出 JSON。"

    fun questions(type: String, text: String, plan: String, tail: String, summaries: String, foreshadows: String): String = buildString {
        append("【卡点类型】").append(type).append('\n')
        if (text.isNotBlank()) append("【作者补充】").append(text).append('\n')
        if (plan.isNotBlank()) append("【章纲】").append(plan).append('\n')
        if (tail.isNotBlank()) append("【本章已写】结尾 1200 字：\n").append(tail).append('\n')
        if (summaries.isNotBlank()) append("【前情】\n").append(summaries).append('\n')
        if (foreshadows.isNotBlank()) append("【未回收伏笔】\n").append(foreshadows)
        append("\n输出：{\"questions\":[{\"q\":\"\",\"options\":[\"\"]}] } 2–3 个问题，用选项按钮回答。")
    }

    fun ways(answers: String, base: String): String = base + "\n【作者回答】$answers\n输出：{\"ways\":[{\"title\":\"≤12字\",\"how\":\"≤80字\",\"pro\":\"\",\"con\":\"\",\"outlineChange\":\"对本章/后续章纲的改动，没有则空\"}]} 3 个。"

    /** 澄清问题。 */
    data class Clarify(val q: String, val options: List<String>)

    suspend fun clarify(bookId: Long, chapterId: Long, type: String, text: String): List<Clarify> {
        val db = AppGraph.db
        val c = db.chapters().get(chapterId) ?: return emptyList()
        val plan = db.plan().chapterOnce(chapterId)?.asPrompt().orEmpty()
        val tail = c.content.takeLast(1200)
        val sums = db.context().summaries(bookId).associateBy { it.chapterId }
        val summaries = db.chapters().chaptersOnce(bookId).sortedBy { it.number }.takeLast(3)
            .mapNotNull { ch -> sums[ch.id]?.let { "第 ${ch.number} 章：${it.text}" } }.joinToString("\n")
        val fs = db.checks().foreshadowsOnce(bookId).filter { it.status == "open" }.take(5)
            .joinToString("\n") { "· ${it.content}" }
        val raw = AppGraph.ai.complete("outline", SYS, questions(type, text, plan, tail, summaries, fs), bookId, chapterId, 600).getOrThrow()
        val o = (JsonReply.parse(raw, array = false) as? JsonReply.Result.Ok)?.json as? kotlinx.serialization.json.JsonObject
            ?: extractJson(raw, array = false)?.jsonObject ?: error("模型没有按格式返回")
        return o["questions"]?.jsonArray?.map { e ->
            val q = e.jsonObject
            Clarify(q.str("q"), q["options"]?.jsonArray?.map { opt -> opt.jsonPrimitive.contentOrNull.orEmpty() } ?: emptyList())
        } ?: emptyList()
    }

    /** 3 个方向（做法 / 利 / 弊 / 章纲改动）。 */
    @Serializable
    data class Way(val title: String, val how: String, val pro: String = "", val con: String = "", val outlineChange: String = "")

    suspend fun ways(bookId: Long, chapterId: Long, type: String, text: String, answers: String): List<Way> {
        val db = AppGraph.db
        val c = db.chapters().get(chapterId) ?: return emptyList()
        val plan = db.plan().chapterOnce(chapterId)?.asPrompt().orEmpty()
        val base = questions(type, text, plan, c.content.takeLast(1200), "", "")
        val raw = AppGraph.ai.complete("outline", SYS, ways(answers, base), bookId, chapterId, 900).getOrThrow()
        val o = (JsonReply.parse(raw, array = false) as? JsonReply.Result.Ok)?.json as? kotlinx.serialization.json.JsonObject
            ?: extractJson(raw, array = false)?.jsonObject ?: error("模型没有按格式返回")
        return o["ways"]?.jsonArray?.map { e ->
            val w = e.jsonObject
            Way(w.str("title"), w.str("how"), w.str("pro"), w.str("con"), w.str("outlineChange"))
        } ?: emptyList()
    }
}
