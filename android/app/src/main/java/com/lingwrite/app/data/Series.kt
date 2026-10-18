// SPDX-FileCopyrightText: 2026 WikG and LingWrite contributors
// SPDX-License-Identifier: AGPL-3.0-only
package com.lingwrite.app.data

import androidx.room.withTransaction
import com.lingwrite.app.AppGraph
import com.lingwrite.app.R
import com.lingwrite.app.data.db.LoreRelationEntity
import com.lingwrite.app.i18n.trOr
import com.lingwrite.app.data.db.BookEntity
import com.lingwrite.app.data.db.LoreCardEntity
import com.lingwrite.app.data.db.LoreStateEntity
import com.lingwrite.app.data.db.SeriesEntity
import com.lingwrite.app.data.db.SeriesLoreEntity
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer

@Serializable
data class SeriesRelation(val to: String = "", val label: String = "", val fromChapter: Int = 1, val tone: String = "neutral")

/** 复制系列卡到本书的结果。 */
sealed interface CopyResult {
    data class Copied(val cardId: Long) : CopyResult
    /** 本书已有同名卡：不覆盖，提示作者。 */
    data class Duplicate(val cardId: Long) : CopyResult
}

@Serializable
data class SeriesState(val fromChapter: Int = 1, val field: String = "", val value: String = "")

/** 系列圣经：跨书参考库；复制到本书后才参与本书的一致性检查。 */
object SeriesStore {
    suspend fun list(): List<SeriesEntity> = AppGraph.db.series().once()
    suspend fun get(id: Long): SeriesEntity? = AppGraph.db.series().get(id)
    suspend fun create(name: String): Long = AppGraph.db.series().insert(SeriesEntity(name = name.trim().ifBlank { trOr(R.string.series_024, "未命名系列") }))
    suspend fun rename(id: Long, name: String) { AppGraph.db.series().get(id)?.let { AppGraph.db.series().update(it.copy(name = name.trim().ifBlank { it.name })) } }
    suspend fun delete(id: Long) { AppGraph.db.series().delete(id) }

    suspend fun books(seriesId: Long): List<BookEntity> = AppGraph.db.books().all().filter { it.seriesId == seriesId }
    suspend fun unlinkedBooks(): List<BookEntity> = AppGraph.db.books().all().filter { it.seriesId == null }
    suspend fun addBook(seriesId: Long, bookId: Long) { AppGraph.db.series().linkBook(bookId, seriesId) }
    suspend fun removeBook(bookId: Long) { AppGraph.db.series().linkBook(bookId, null) }

    fun lore(seriesId: Long) = AppGraph.db.series().lore(seriesId)
    suspend fun loreOnce(seriesId: Long): List<SeriesLoreEntity> = AppGraph.db.series().loreOnce(seriesId)
    suspend fun insertLore(item: SeriesLoreEntity): Long = AppGraph.db.series().insertLore(item)
    suspend fun updateLore(item: SeriesLoreEntity) = AppGraph.db.series().updateLore(item)
    suspend fun deleteLore(id: Long) = AppGraph.db.series().deleteLore(id)

    fun parseStates(json: String): List<SeriesState> =
        runCatching { LingJson.decodeFromString(ListSerializer(SeriesState.serializer()), json) }.getOrDefault(emptyList())

    fun parseRelations(json: String): List<SeriesRelation> =
        runCatching { LingJson.decodeFromString(ListSerializer(SeriesRelation.serializer()), json) }.getOrDefault(emptyList())

    /**
     * 把系列卡复制成目标书的设定卡（含按章状态、关系），在一个事务里完成。
     * - 本书已有同名卡时不复制，返回 [CopyResult.Duplicate]。
     * - 追加字段「系列来源 = 系列名」。
     * - 关系按名字引用：对方卡已在本书里就建关系；同系列其他卡指向本卡的关系也一并补上。
     */
    suspend fun copyToBook(item: SeriesLoreEntity, bookId: Long): CopyResult = AppGraph.db.withTransaction {
        val lore = AppGraph.db.lore()
        val name = item.name.trim()
        lore.cardByName(bookId, name)?.let { return@withTransaction CopyResult.Duplicate(it.id) }
        val states = parseStates(item.states)
        val seriesName = AppGraph.db.series().get(item.seriesId)?.name.orEmpty()
        val srcKey = trOr(R.string.series_025, "系列来源")
        val fields = parseFields(item.fields).filter { it.k != srcKey } + listOfNotNull(seriesName.takeIf { it.isNotBlank() }?.let { Field(srcKey, it) })
        val card = LoreCardEntity(
            bookId = bookId, type = item.type, name = name, aliases = item.aliases, role = item.role,
            fields = encodeFields(fields), inject = "auto", firstChapter = states.minOfOrNull { it.fromChapter },
        )
        val id = lore.insertCard(card)
        states.filter { it.field.isNotBlank() }.forEach { s ->
            lore.insertState(LoreStateEntity(cardId = id, fromChapter = s.fromChapter, field = s.field, value = s.value))
        }
        // 关系：本卡 → 已在本书的卡
        parseRelations(item.relations).filter { it.to.isNotBlank() }.forEach { r ->
            val target = lore.cardByName(bookId, r.to.trim()) ?: return@forEach
            if (target.id != id) lore.insertRelation(LoreRelationEntity(bookId = bookId, fromId = id, toId = target.id, label = r.label, fromChapter = r.fromChapter, tone = r.tone))
        }
        // 关系：同系列其他已复制的卡 → 本卡
        AppGraph.db.series().loreOnce(item.seriesId).filter { it.id != item.id }.forEach { other ->
            val rels = parseRelations(other.relations).filter { it.to.trim() == name }
            if (rels.isEmpty()) return@forEach
            val from = lore.cardByName(bookId, other.name.trim()) ?: return@forEach
            rels.forEach { r -> lore.insertRelation(LoreRelationEntity(bookId = bookId, fromId = from.id, toId = id, label = r.label, fromChapter = r.fromChapter, tone = r.tone)) }
        }
        CopyResult.Copied(id)
    }
}
