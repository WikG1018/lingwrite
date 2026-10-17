// SPDX-FileCopyrightText: 2026 WikG and LingWrite contributors
// SPDX-License-Identifier: AGPL-3.0-only
package com.lingwrite.app.data

import com.lingwrite.app.AppGraph
import com.lingwrite.app.data.db.*
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject

/**
 * AI 设定整理（A2，ai-features §2）：
 * 从一章抽出事实 → 生成 new_card / state / alias / relation 建议 → 作者采纳或忽略。
 */
object LoreSuggest {
    /** 本章的原文节选（按 facts 里的 quote 定位，前后各 80 字）。 */
    private fun excerpts(text: String, quotes: List<String>): String = quotes.distinct().mapNotNull { q ->
        val at = locate(text, q).takeIf { it >= 0 } ?: return@mapNotNull null
        text.substring((at - 80).coerceAtLeast(0), (at + q.length + 80).coerceAtMost(text.length))
    }.joinToString("\n---\n")

    /** 生成并入库建议；返回新增条数。 */
    suspend fun generate(chapterId: Long): Int {
        val db = AppGraph.db
        val c = db.chapters().get(chapterId) ?: return 0
        if (c.content.isBlank()) return 0
        // 第一步：抽取事实（与 Consistency.check 同源；当次没有跑检查时单独抽一次）
        val facts = mutableListOf<kotlinx.serialization.json.JsonElement>()
        c.content.chunked(6000).forEach { seg ->
            val r = AppGraph.ai.complete("extract", Consistency.SYS, Consistency.extractPrompt(seg), c.bookId, c.id, 1500, outputLang = false).getOrNull() ?: return@forEach
            extractJson(r)?.let { j -> runCatching { facts.addAll(j.jsonArray) } }
        }
        if (facts.isEmpty()) return 0
        val cards = db.lore().cardsOnce(c.bookId).filter { !it.pending && it.inject != "never" && (it.firstChapter == null || it.firstChapter <= c.number) }
        val states = db.lore().statesForBook(c.bookId).filter { it.fromChapter <= c.number }.groupBy { it.cardId }
        val quotes = facts.mapNotNull { it.jsonObject.str("quote") }
        val user = LorePrompts.suggest(
            LorePrompts.cardsBrief(cards, states, c.number),
            kotlinx.serialization.json.JsonArray(facts).toString(),
            excerpts(c.content, quotes),
        )
        val raw = AppGraph.ai.complete("extract", LorePrompts.SYS, user, c.bookId, c.id, 2000).getOrNull() ?: return 0
        val arr = extractJson(raw)?.let { runCatching { it.jsonArray }.getOrNull() } ?: return 0
        val items = arr.map { e -> e.jsonObject }.mapNotNull { o ->
            val kind = o.str("kind").takeIf { it in setOf("new_card", "state", "alias", "relation") } ?: return@mapNotNull null
            LoreSuggestionEntity(
                bookId = c.bookId, chapterId = chapterId, number = c.number, kind = kind,
                cardId = o.str("cardId").toLongOrNull(),
                payload = o.toString(),
                quote = o.str("quote").take(40),
            )
        }.take(12)
        if (items.isNotEmpty()) db.loreSuggest().insert(items)
        return items.size
    }

    /** 采纳一条建议（写设定库）；返回成功与否。 */
    suspend fun adopt(id: Long): Boolean {
        val db = AppGraph.db
        val s = db.loreSuggest().get(id) ?: return false
        if (s.status != "pending") return s.status == "adopted"
        val o = runCatching { kotlinx.serialization.json.Json.parseToJsonElement(s.payload).jsonObject }.getOrNull() ?: return false
        when (s.kind) {
            "new_card" -> {
                val type = o.str("type").ifBlank { "term" }
                val name = o.str("name")
                if (name.isBlank()) return false
                val fields = encodeFields(listOf(Field("要点", o.str("value").ifBlank { o.str("why") })).filter { it.v.isNotBlank() })
                db.lore().insertCard(
                    LoreCardEntity(bookId = s.bookId, type = type, name = name, fields = fields,
                        role = o.str("why"), firstChapter = s.number, pending = false),
                )
            }
            "state" -> {
                val cardId = s.cardId ?: o.str("cardId").toLongOrNull() ?: return false
                db.lore().insertState(LoreStateEntity(cardId = cardId, fromChapter = s.number, field = o.str("field").ifBlank { "状态" }, value = o.str("value")))
            }
            "alias" -> {
                val cardId = s.cardId ?: o.str("cardId").toLongOrNull() ?: return false
                val card = db.lore().cardOnce(cardId) ?: return false
                val alias = o.str("value").ifBlank { o.str("name") }
                if (alias.isBlank()) return false
                db.lore().updateCard(card.copy(aliases = (card.aliases.split('，', ',', '、').map { it.trim() } + alias).filter { it.isNotBlank() }.distinct().joinToString("，")))
            }
            "relation" -> {
                val fromId = s.cardId ?: o.str("cardId").toLongOrNull() ?: return false
                val targetName = o.str("target")
                val toId = o.str("targetId").toLongOrNull()?.takeIf { id -> db.lore().cardOnce(id)?.bookId == s.bookId }
                    ?: db.lore().cardsOnce(s.bookId).filter { it.name == targetName }.singleOrNull()?.id ?: return false
                db.lore().insertRelation(
                    LoreRelationEntity(bookId = s.bookId, fromId = fromId, toId = toId,
                        label = o.str("label").ifBlank { "关联" }, fromChapter = s.number),
                )
            }
        }
        db.loreSuggest().setStatus(id, "adopted")
        return true
    }

    suspend fun ignore(id: Long) = AppGraph.db.loreSuggest().setStatus(id, "ignored")

    /** 全部采纳：逐条成功计数。 */
    suspend fun adoptAll(bookId: Long): Int {
        val items = AppGraph.db.loreSuggest().pending(bookId).first()
        return items.count { adopt(it.id) }
    }
}
