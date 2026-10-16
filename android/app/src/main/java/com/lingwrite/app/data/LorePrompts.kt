// SPDX-FileCopyrightText: 2026 WikG and LingWrite contributors
// SPDX-License-Identifier: AGPL-3.0-only
package com.lingwrite.app.data

import com.lingwrite.app.data.db.LoreCardEntity
import com.lingwrite.app.data.db.LoreStateEntity

/** AI 设定整理提示词（A2，ai-features §2）。 */
object LorePrompts {
    const val SYS = "你是小说设定整理员。只根据给出的事实和原文判断，不臆测。严格只输出 JSON 数组。"

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
        append("\n输出：[{\"kind\":\"new_card|state|alias|relation\",\"cardId\":可空,\"type\":\"character|faction|place|item|power|term\",\"name\":\"\",\"field\":\"\",\"value\":\"\",\"target\":\"关系对象名\",\"label\":\"关系\",\"quote\":\"原文依据≤40字\",\"why\":\"≤30字\"}]\n")
        append("只输出确有变化的条目，最多 12 条；已有卡的已知值不要重复建议。")
    }
}
