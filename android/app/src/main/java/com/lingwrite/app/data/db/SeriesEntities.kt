// SPDX-FileCopyrightText: 2026 WikG and LingWrite contributors
// SPDX-License-Identifier: AGPL-3.0-only
package com.lingwrite.app.data.db

import androidx.room.*
import kotlinx.coroutines.flow.Flow

/** 系列（同一世界观的多本书）。删系列不删书，只把 books.seriesId 置空。 */
@Entity(tableName = "series")
data class SeriesEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val note: String = "",
    val createdAt: Long = System.currentTimeMillis(),
)

/**
 * 系列共享设定卡：fields / states / relations 都是 JSON。
 * 只作为参考库；复制到本书时才写入 lore_cards / lore_states。
 */
@Entity(
    tableName = "series_lore",
    foreignKeys = [ForeignKey(entity = SeriesEntity::class, parentColumns = ["id"], childColumns = ["seriesId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("seriesId")],
)
data class SeriesLoreEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val seriesId: Long,
    /** character / faction / place / item / power / term */
    val type: String,
    val name: String,
    val aliases: String = "",
    val role: String = "",
    /** JSON [{"k":"境界","v":"筑基"}] */
    val fields: String = "[]",
    /** JSON [{"fromChapter":1,"field":"境界","value":"筑基"}] */
    val states: String = "[]",
    /** JSON [{"to":"沈砚","label":"师徒"}]，按名字引用 */
    val relations: String = "[]",
    val updatedAt: Long = System.currentTimeMillis(),
)

@Dao
interface SeriesDao {
    @Query("SELECT * FROM series ORDER BY createdAt") fun all(): Flow<List<SeriesEntity>>
    @Query("SELECT * FROM series ORDER BY createdAt") suspend fun once(): List<SeriesEntity>
    @Query("SELECT * FROM series WHERE id = :id") suspend fun get(id: Long): SeriesEntity?
    @Insert suspend fun insert(s: SeriesEntity): Long
    @Update suspend fun update(s: SeriesEntity)
    @Query("DELETE FROM series WHERE id = :id") suspend fun delete(id: Long)

    @Query("SELECT * FROM series_lore WHERE seriesId = :seriesId ORDER BY type, name") fun lore(seriesId: Long): Flow<List<SeriesLoreEntity>>
    @Query("SELECT * FROM series_lore WHERE seriesId = :seriesId ORDER BY type, name") suspend fun loreOnce(seriesId: Long): List<SeriesLoreEntity>
    @Query("SELECT * FROM series_lore WHERE id = :id") suspend fun loreItem(id: Long): SeriesLoreEntity?
    @Insert suspend fun insertLore(item: SeriesLoreEntity): Long
    @Update suspend fun updateLore(item: SeriesLoreEntity)
    @Query("DELETE FROM series_lore WHERE id = :id") suspend fun deleteLore(id: Long)

    @Query("UPDATE books SET seriesId = :seriesId WHERE id = :bookId") suspend fun linkBook(bookId: Long, seriesId: Long?)
}
