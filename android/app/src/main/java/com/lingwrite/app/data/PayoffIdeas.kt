// SPDX-FileCopyrightText: 2026 WikG and LingWrite contributors
// SPDX-License-Identifier: AGPL-3.0-only
package com.lingwrite.app.data

import com.lingwrite.app.AppGraph
import com.lingwrite.app.data.ai.JsonReply
import com.lingwrite.app.data.db.ForeshadowEntity
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject

/** 伏笔回收建议（A8，ai-features §8）：在哪章、怎么收、需要提前铺垫什么。 */
@Serializable
data class PayoffIdea(val chapter: Int, val how: String, val setup: String = "")

object PayoffIdeas {
    const val SYS = "你是伏笔设计顾问。回收要合情理、有爽感，不能突兀。严格只输出 JSON。"

    fun prompt(f: ForeshadowEntity, current: Int, volumeOutline: String, upcoming: String, card: String): String = buildString {
        append("【伏笔】${f.content}，第 ${f.plantNumber} 章埋下，原文「${f.quote}」，计划第 ").append(f.payoff ?: 0).append(" 章回收，现在写到第 ").append(current).append(" 章\n")
        if (volumeOutline.isNotBlank()) append("【当前卷纲】").append(volumeOutline).append('\n')
        if (upcoming.isNotBlank()) append("【后续章纲】\n").append(upcoming).append('\n')
        if (card.isNotBlank()) append("【相关设定卡】").append(card)
        append("\n输出：[{\"chapter\":数字,\"how\":\"≤60字\",\"setup\":\"需要提前铺垫什么，≤30字\"}] 3 条。")
    }

    suspend fun generate(f: ForeshadowEntity): List<PayoffIdea> {
        val db = AppGraph.db
        val chs = db.chapters().chaptersOnce(f.bookId).sortedBy { it.number }
        val current = chs.lastOrNull()?.number ?: 1
        val volId = chs.lastOrNull()?.volumeId
        val volOutline = volId?.let { db.plan().volumeOnce(it)?.outline }.orEmpty()
        val upcoming = chs.filter { it.number > current }.take(8).mapNotNull { ch ->
            db.plan().chapterOnce(ch.id)?.takeIf { !it.isEmpty() }?.let { "第 ${ch.number} 章：${it.event}" }
        }.joinToString("\n")
        val card = f.cardId?.let { db.lore().cardOnce(it)?.let { c -> "${c.name}：${c.role}" } }.orEmpty()
        val raw = AppGraph.ai.complete("foreshadow", SYS, prompt(f, current, volOutline, upcoming, card), f.bookId, f.chapterId, 700).getOrThrow()
        val arr = (JsonReply.parse(raw) as? JsonReply.Result.Ok)?.json
            ?: extractJson(raw) ?: error("模型没有按格式返回")
        val ideas = arr.jsonArray.map { e ->
            val o = e.jsonObject
            PayoffIdea(
                o["chapter"]?.jsonPrimitiveInt() ?: current + 1,
                o.str("how"),
                o.str("setup"),
            )
        }.filter { it.how.isNotBlank() }.take(3)
        if (ideas.isNotEmpty()) {
            AppGraph.db.checks().updateForeshadow(
                f.copy(payoffIdeas = LingJson.encodeToString(ListSerializer(PayoffIdea.serializer()), ideas)),
            )
        }
        return ideas
    }

    fun parseIdeas(json: String): List<PayoffIdea> =
        runCatching { LingJson.decodeFromString(ListSerializer(PayoffIdea.serializer()), json) }.getOrDefault(emptyList())
}

private fun kotlinx.serialization.json.JsonElement.jsonPrimitiveInt(): Int? = (this as? kotlinx.serialization.json.JsonPrimitive)?.let { it.content.toIntOrNull() }
