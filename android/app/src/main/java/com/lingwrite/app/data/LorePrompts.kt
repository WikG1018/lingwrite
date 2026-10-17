// SPDX-FileCopyrightText: 2026 WikG and LingWrite contributors
// SPDX-License-Identifier: AGPL-3.0-only
package com.lingwrite.app.data

import com.lingwrite.app.data.ai.PromptContract
import com.lingwrite.app.data.db.LoreCardEntity
import com.lingwrite.app.data.db.LoreStateEntity

/** AI 设定整理提示词（A2，ai-features §2）。 */
object LorePrompts {
    val SYS = "你是小说设定整理员。只根据给出的事实和原文判断，不臆测。\n" + PromptContract.JSON_ARRAY

    /** 已有设定卡的简表。 */
    fun cardsBrief(cards: List<LoreCardEntity>, states: Map<Long, List<LoreStateEntity>>, chapter: Int = 1): String = buildString {
        cards.take(30).forEach { c ->
            append("· [").append(c.id).append("] ").append(c.type).append(" ").append(c.name)
            if (c.aliases.isNotBlank()) append("（又名 ").append(c.aliases).append("）")
            val f = resolveAt(c, states[c.id].orEmpty(), chapter).joinToString("；") { "${it.k}=${it.v}" + (it.changedAt?.let { a -> "（第 $a 章起）" } ?: "") }
            if (f.isNotBlank()) append("：").append(f)
            append('\n')
        }
    }

    fun suggest(cards: String, facts: String, quotes: String): String = buildString {
        append("【已有设定卡】\n").append(cards.ifBlank { "（暂无）" })
        append("\n【本章事实】\n").append(facts)
        append("\n【本章原文节选】\n").append(quotes)
        append("\n只输出确有变化的条目，最多 12 条；已有卡的已知值不要重复建议；没有就输出 []。\n")
        append(PromptContract.JSON_ARRAY).append('\n')
        append(PromptContract.VERBATIM_QUOTE).append('\n')
        append("kind 只能是 new_card / state / alias / relation；type 只能是 character / faction / place / item / power / term。\n")
        append("格式示例：[{\"kind\":\"state\",\"cardId\":3,\"type\":\"character\",\"name\":\"林砚\",\"field\":\"境界\",\"value\":\"筑基三层\",\"quote\":\"他体内的灵力已凝成三层\",\"why\":\"本章突破\"}]")
    }
}
