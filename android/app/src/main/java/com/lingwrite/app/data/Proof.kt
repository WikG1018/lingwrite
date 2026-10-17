// SPDX-FileCopyrightText: 2026 WikG and LingWrite contributors
// SPDX-License-Identifier: AGPL-3.0-only
package com.lingwrite.app.data

import com.lingwrite.app.AppGraph
import com.lingwrite.app.data.ai.JsonReply
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject

/** 校对发现的一处问题（A9，ai-features §9）。 */
@Serializable
data class ProofIssue(
    val quote: String,
    val fix: String,
    val type: String,
    val why: String = "",
)

object ProofPrompts {
    const val SYS = "你是严格的中文校对。只找错别字、的地得、标点误用、明显病句；不改风格、不改用词习惯、不改方言和口语、不改人物语言特色。严格只输出 JSON。"

    fun user(seg: String) = "【段落】\n$seg\n\n输出：[{\"quote\":\"原文最短唯一片段\",\"fix\":\"改后\",\"type\":\"typo|de|punct|grammar\",\"why\":\"≤15字\"}]，没有问题输出 []。"
}

object Proof {
    /** 分段（每段 1500 字）校对；返回按出现顺序的全部问题。 */
    suspend fun run(bookId: Long, chapterId: Long, text: String): List<ProofIssue> {
        val out = mutableListOf<ProofIssue>()
        text.chunked(1500).forEach { seg ->
            val raw = AppGraph.ai.complete("check", ProofPrompts.SYS, ProofPrompts.user(seg), bookId, chapterId, 1200).getOrThrow()
            val arr = (JsonReply.parse(raw) as? JsonReply.Result.Ok)?.json
                ?: extractJson(raw) ?: error("校对返回格式无效，请重试；未应用修改")
            out += arr.jsonArray.map { e ->
                val o = e.jsonObject
                ProofIssue(o.str("quote"), o.str("fix"), o.str("type").ifBlank { "typo" }, o.str("why"))
            }.filter { it.quote.isNotBlank() && it.fix.isNotBlank() }
        }
        return out
    }

    /** 定位一处：同一片段出现多次时取最近的一次（相对 lastAt）。 */
    fun locate(text: String, quote: String, lastAt: Int = 0): Int {
        if (quote.isBlank()) return -1
        val direct = text.indexOf(quote, lastAt)
        if (direct >= 0) return direct
        return text.indexOf(quote).coerceAtLeast(-1)
    }

    /** 只在正文里唯一出现时返回下标；找不到或出现多次返回 -1（采纳前防改错同名句）。 */
    fun locateUnique(text: String, quote: String): Int {
        if (quote.isBlank()) return -1
        val first = text.indexOf(quote)
        if (first < 0) return -1
        return if (text.indexOf(quote, first + 1) < 0) first else -1
    }

    /** 是否需要标「AI 建议 + 人工修改」：病句类改写超过 6 个字（补个标点之类的小改不标）。 */
    fun needsAiMark(issue: ProofIssue): Boolean =
        issue.type == "grammar" && issue.fix.length > 6

    fun parse(json: String): List<ProofIssue> =
        runCatching { LingJson.decodeFromString(ListSerializer(ProofIssue.serializer()), json) }.getOrDefault(emptyList())
}
