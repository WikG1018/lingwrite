// SPDX-FileCopyrightText: 2026 WikG and LingWrite contributors
// SPDX-License-Identifier: AGPL-3.0-only
package com.lingwrite.app.data

import com.lingwrite.app.data.db.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer

@Serializable data class Field(val k: String, val v: String)

enum class LoreType(val id: String, val label: String) {
    Character("character", "人物"), Faction("faction", "势力"), Place("place", "地点"), Item("item", "物品"), Power("power", "能力体系"), Term("term", "名词");
    companion object { fun of(id: String) = entries.firstOrNull { it.id == id } ?: Term }
}

/** 各类卡片的默认字段。 */
fun defaultFields(t: LoreType) = when (t) {
    LoreType.Character -> listOf("境界", "外貌", "性格", "当前目标", "秘密")
    LoreType.Faction -> listOf("首领", "据点", "立场", "实力")
    LoreType.Place -> listOf("所属", "环境", "要点")
    LoreType.Item -> listOf("持有者", "来历", "能力")
    LoreType.Power -> listOf("等级划分", "规则", "代价")
    LoreType.Term -> listOf("释义")
}

fun parseFields(json: String): List<Field> = runCatching { LingJson.decodeFromString(ListSerializer(Field.serializer()), json) }.getOrDefault(emptyList())
fun encodeFields(f: List<Field>): String = LingJson.encodeToString(ListSerializer(Field.serializer()), f)

fun LoreCardEntity.allNames(): List<String> = (listOf(name) + aliases.split(',', '，', '、')).map { it.trim() }.filter { it.isNotEmpty() }

/** 某一章时某卡片的有效字段：基础值叠加 fromChapter ≤ 当章的最新变化。 */
data class ResolvedField(val k: String, val v: String, val changedAt: Int?, val previous: String?)

fun resolveAt(card: LoreCardEntity, states: List<LoreStateEntity>, chapter: Int): List<ResolvedField> {
    val base = parseFields(card.fields)
    val keys = (base.map { it.k } + states.map { it.field }).distinct()
    return keys.mapNotNull { k ->
        val hist = states.filter { it.field == k && it.fromChapter <= chapter }.sortedBy { it.fromChapter }
        val baseV = base.firstOrNull { it.k == k }?.v
        val last = hist.lastOrNull()
        val v = last?.value ?: baseV ?: return@mapNotNull null
        if (v.isBlank()) return@mapNotNull null
        val prev = if (last != null) (hist.dropLast(1).lastOrNull()?.value ?: baseV) else null
        ResolvedField(k, v, last?.fromChapter, prev)
    }
}

/** 当章生效的关系（每对人物取 fromChapter ≤ 当章的最新一条；标签为“断绝”视为不存在）。 */
fun relationsAt(all: List<LoreRelationEntity>, chapter: Int): List<LoreRelationEntity> =
    all.filter { it.fromChapter <= chapter }.groupBy { minOf(it.fromId, it.toId) to maxOf(it.fromId, it.toId) }
        .mapNotNull { (_, l) -> l.maxByOrNull { it.fromChapter } }.filter { it.label != "断绝" && it.label.isNotBlank() }

/** 在文本中找出提及的卡片（名字或别名）。 */
fun mentioned(cards: List<LoreCardEntity>, text: String): List<LoreCardEntity> = cards.filter { c -> c.inject != "never" && c.allNames().any { text.contains(it) } }
