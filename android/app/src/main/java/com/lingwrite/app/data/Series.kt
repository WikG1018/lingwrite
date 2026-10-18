// SPDX-FileCopyrightText: 2026 WikG and LingWrite contributors
// SPDX-License-Identifier: AGPL-3.0-only
package com.lingwrite.app.data

import com.lingwrite.app.AppGraph
import com.lingwrite.app.data.db.BookEntity
import com.lingwrite.app.data.db.LoreCardEntity
import com.lingwrite.app.data.db.LoreStateEntity
import com.lingwrite.app.data.db.SeriesEntity
import com.lingwrite.app.data.db.SeriesLoreEntity
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer

@Serializable
data class SeriesState(val fromChapter: Int = 1, val field: String = "", val value: String = "")

/** 系列圣经：跨书参考库；复制到本书后才参与本书的一致性检查。 */
object SeriesStore {
    suspend fun list(): List<SeriesEntity> = AppGraph.db.series().once()
    suspend fun get(id: Long): SeriesEntity? = AppGraph.db.series().get(id)
    suspend fun create(name: String): Long = AppGraph.db.series().insert(SeriesEntity(name = name.trim().ifBlank { "未命名系列" }))
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

    /** 把系列卡复制成目标书的设定卡（含按章状态）；重名不覆盖，返回新卡 id。 */
    suspend fun copyToBook(item: SeriesLoreEntity, bookId: Long): Long {
        val states = parseStates(item.states)
        val card = LoreCardEntity(
            bookId = bookId, type = item.type, name = item.name, aliases = item.aliases, role = item.role,
            fields = item.fields, inject = "auto", firstChapter = states.minOfOrNull { it.fromChapter },
        )
        val id = AppGraph.db.lore().insertCard(card)
        states.filter { it.field.isNotBlank() }.forEach { s ->
            AppGraph.db.lore().insertState(LoreStateEntity(cardId = id, fromChapter = s.fromChapter, field = s.field, value = s.value))
        }
        return id
    }
}
