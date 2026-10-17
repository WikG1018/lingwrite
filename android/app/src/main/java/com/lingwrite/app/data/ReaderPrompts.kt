// SPDX-FileCopyrightText: 2026 WikG and LingWrite contributors
// SPDX-License-Identifier: AGPL-3.0-only
package com.lingwrite.app.data

import com.lingwrite.app.AppGraph
import com.lingwrite.app.data.ai.JsonReply
import com.lingwrite.app.data.ai.PromptContract
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** A16 读者画像（ai-features §16）。 */
data class ReaderPersona(val id: String, val name: String, val desc: String)

val READER_PERSONAS = listOf(
    ReaderPersona("veteran", "老白", "阅读量大、挑剔逻辑"),
    ReaderPersona("newbie", "小白", "追爽点，节奏要快"),
    ReaderPersona("female", "女频读者", "重情感和人物关系"),
    ReaderPersona("custom", "自定义", "自己描述目标读者"),
)

/** A16 一次画像的反馈结果。 */
@Serializable
data class ReaderReport(
    val stars: Int = 3,
    val oneLine: String = "",
    val drops: List<ReaderDrop> = emptyList(),
    val hooks: List<String> = emptyList(),
    val wantNext: String = "",
    val advice: List<String> = emptyList(),
) {
    val starsLabel: String get() = "★".repeat(stars.coerceIn(1, 5)) + "☆".repeat((5 - stars.coerceIn(1, 5)).coerceAtLeast(0))
}

/** 弃书风险点：原文片段 + 原因。 */
@Serializable
data class ReaderDrop(val quote: String, val why: String)

object ReaderPrompts {
    val SYS = "你在扮演一位网文读者，刚读完下面的章节。用读者口吻、诚实地反馈，不要客气，也不要替作者改写。\n" + PromptContract.JSON_ONLY

    fun user(persona: ReaderPersona, customDesc: String, blurb: String, prev: String, text: String): String {
        val desc = if (persona.id == "custom") customDesc.ifBlank { "普通网文读者" } else "${persona.name}（${persona.desc}）"
        val body = if (text.length > 8000) "（正文超长，截取开头与结尾）\n" + text.take(3000) + "\n……\n" + text.takeLast(3000) else text
        return buildString {
            if (blurb.isNotBlank()) append("【作品简介】").append(blurb).append('\n')
            if (prev.isNotBlank()) append("【前情摘要】\n").append(prev).append('\n')
            append("【你的画像】").append(desc).append('\n')
            append("【章节】").append(body).append('\n')
            append(PromptContract.JSON_ONLY).append('\n')
            append(PromptContract.VERBATIM_QUOTE).append('\n')
            append("输出：{\"stars\":1-5 的整数,\"oneLine\":\"≤30字整体评价\",\"drops\":[{\"quote\":\"原文≤20字\",\"why\":\"≤30字为什么想弃书\"}]（0–3 条，没有就 []）,")
            append("\"hooks\":[\"爽点或期待点，0–3 条\"],\"wantNext\":\"≤40字想不想看下一章及原因\",\"advice\":[\"≤40字建议\"×最多3条]}")
            append("\n示例：{\"stars\":4,\"oneLine\":\"开局够狠，中段有点拖\",\"drops\":[{\"quote\":\"他缓缓抬起头\",\"why\":\"动作拖沓\"}],\"hooks\":[\"断剑来历\"],\"wantNext\":\"想知道师父死因\",\"advice\":[\"删掉两段环境描写\"]}")
        }
    }

    /** 费用预估（fitBudget 同口径）：按正文字数粗估 tokens。 */
    fun estTokens(text: String, personas: Int): Int = (text.length * 3 / 2 + 1200) * personas.coerceAtLeast(1)
}

object ReaderFeedback {
    /** 单个画像跑一次试读，结果落库。 */
    suspend fun run(bookId: Long, persona: ReaderPersona, customDesc: String, fromChapter: Int, toChapter: Int, text: String): Result<ReaderReport> = runCatching {
        val db = AppGraph.db
        val b = db.books().get(bookId) ?: error("作品不存在")
        val chs = db.chapters().chaptersOnce(bookId).sortedBy { it.number }.filter { it.number in fromChapter..toChapter }
        val sums = db.context().summaries(bookId).associateBy { it.chapterId }
        val prev = chs.mapNotNull { c -> sums[c.id]?.let { "第 ${c.number} 章：${it.text}" } }.take(3).joinToString("\n")
        val raw = AppGraph.ai.complete("ask", ReaderPrompts.SYS,
            ReaderPrompts.user(persona, customDesc, b.blurb, prev, text), bookId, chs.firstOrNull()?.id, 1000, outputLang = false).getOrThrow()
        val o = (JsonReply.parse(raw, array = false) as? JsonReply.Result.Ok)?.json as? kotlinx.serialization.json.JsonObject
            ?: extractJson(raw, array = false)?.jsonObject ?: error("模型没有按格式返回")
        val r = ReaderReport(
            stars = o.str("stars").toIntOrNull()?.coerceIn(1, 5) ?: 3,
            oneLine = o.str("oneLine"),
            drops = (o["drops"]?.jsonArray ?: emptyList()).mapNotNull { e ->
                val d = e.jsonObject
                if (d.str("quote").isBlank()) null else ReaderDrop(d.str("quote"), d.str("why"))
            },
            hooks = (o["hooks"]?.jsonArray ?: emptyList()).map { it.jsonPrimitive.content }.filter { it.isNotBlank() },
            wantNext = o.str("wantNext"),
            advice = (o["advice"]?.jsonArray ?: emptyList()).map { it.jsonPrimitive.content }.filter { it.isNotBlank() }.take(3),
        )
        val hash = contentHash(text)
        db.readerReports().insert(com.lingwrite.app.data.db.ReaderReportEntity(
            bookId = bookId, fromChapter = fromChapter, toChapter = toChapter, persona = persona.id,
            result = LingJson.encodeToString(ReaderReport.serializer(), r), contentHash = hash))
        r
    }

    /** 正文指纹（改稿前后对比用）。 */
    fun contentHash(text: String): String {
        var h = 1125899906842597L
        for (ch in text) h = 31 * h + ch.code
        return h.toString(16)
    }

    fun parse(json: String): ReaderReport? =
        runCatching { LingJson.decodeFromString(ReaderReport.serializer(), json) }.getOrNull()
}
