// SPDX-FileCopyrightText: 2026 WikG and LingWrite contributors
// SPDX-License-Identifier: AGPL-3.0-only
package com.lingwrite.app.data

import com.lingwrite.app.AppGraph
import com.lingwrite.app.data.ai.JsonReply
import com.lingwrite.app.data.db.IdeaEntity
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject

/** 灵感扩展（A14，ai-features §14）：一条灵感展开成 3 个方向。 */
@Serializable
data class IdeaExpansion(val title: String, val idea: String)

/** AI 归类结果（A14）。 */
@Serializable
data class IdeaClassify(val kind: String, val name: String, val type: String = "character", val fields: String = "", val content: String = "", val payoffIn: Int = 0, val chapter: Int = 1, val note: String = "")

object IdeaExpand {
    const val SYS = "你是网文创意顾问。给的方向要彼此不同、可落地。严格只输出 JSON。"

    fun expandPrompt(text: String, synopsis: String): String = buildString {
        append("【灵感】").append(text)
        if (synopsis.isNotBlank()) append("\n【所属作品总纲】").append(synopsis)
        append("\n输出：[{\"title\":\"≤10字\",\"idea\":\"≤60字\"}] 3 个，方向要彼此不同。")
    }

    fun classifyPrompt(text: String, cardNames: String): String = buildString {
        append("【灵感】").append(text)
        if (cardNames.isNotBlank()) append("\n【作品设定卡名列表】").append(cardNames)
        append("\n输出：{\"kind\":\"card|foreshadow|outline\",\"card\":{\"type\":\"character|faction|place|item|power|term\",\"name\":\"\",\"fields\":{\"字段\":\"值\"}},\"foreshadow\":{\"content\":\"\",\"payoffIn\":章数},\"outline\":{\"chapter\":章号,\"note\":\"\"}}")
    }

    /** 展开 3 个方向，缓存到 ideas.expansions。 */
    suspend fun expand(i: IdeaEntity): List<IdeaExpansion> {
        val synopsis = i.bookId?.let { AppGraph.db.plan().bookOnce(it)?.synopsis }.orEmpty()
        val raw = AppGraph.ai.complete("idea", SYS, expandPrompt(i.text, synopsis), i.bookId, i.chapterId, 600).getOrThrow()
        val arr = (JsonReply.parse(raw) as? JsonReply.Result.Ok)?.json
            ?: extractJson(raw) ?: error("模型没有按格式返回")
        val list = arr.jsonArray.map { e ->
            val o = e.jsonObject
            IdeaExpansion(o.str("title"), o.str("idea"))
        }.filter { it.idea.isNotBlank() }.take(3)
        if (list.isNotEmpty()) {
            AppGraph.db.stats().updateIdea(i.copy(expansions = LingJson.encodeToString(ListSerializer(IdeaExpansion.serializer()), list)))
        }
        return list
    }

    /** 归类：判断这条灵感适合转成设定卡 / 伏笔 / 章纲，并给出预填内容。 */
    suspend fun classify(i: IdeaEntity): IdeaClassify {
        val cardNames = i.bookId?.let { b ->
            AppGraph.db.lore().cardsOnce(b).take(40).joinToString("、") { it.name }
        }.orEmpty()
        val raw = AppGraph.ai.complete("idea", SYS, classifyPrompt(i.text, cardNames), i.bookId, i.chapterId, 500).getOrThrow()
        val o = (JsonReply.parse(raw, array = false) as? JsonReply.Result.Ok)?.json as? kotlinx.serialization.json.JsonObject
            ?: extractJson(raw, array = false)?.jsonObject ?: error("模型没有按格式返回")
        val kind = o.str("kind").ifBlank { "card" }
        val card = o["card"]?.jsonObject
        val fs = o["foreshadow"]?.jsonObject
        val ol = o["outline"]?.jsonObject
        return IdeaClassify(
            kind = kind,
            name = card?.str("name").orEmpty().ifBlank { i.text.take(12) },
            type = card?.str("type").orEmpty().ifBlank { "character" },
            fields = card?.get("fields")?.toString() ?: "{}",
            content = fs?.str("content").orEmpty().ifBlank { i.text },
            payoffIn = fs?.str("payoffIn")?.toIntOrNull() ?: 5,
            chapter = ol?.str("chapter")?.toIntOrNull() ?: 1,
            note = ol?.str("note").orEmpty().ifBlank { i.text },
        )
    }

    fun parseExpansions(json: String): List<IdeaExpansion> =
        runCatching { LingJson.decodeFromString(ListSerializer(IdeaExpansion.serializer()), json) }.getOrDefault(emptyList())
}
