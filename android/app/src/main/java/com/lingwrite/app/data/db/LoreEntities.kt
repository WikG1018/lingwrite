// SPDX-FileCopyrightText: 2026 WikG and LingWrite contributors
// SPDX-License-Identifier: AGPL-3.0-only
package com.lingwrite.app.data.db

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "lore_cards", foreignKeys = [ForeignKey(entity = BookEntity::class, parentColumns = ["id"], childColumns = ["bookId"], onDelete = ForeignKey.CASCADE)], indices = [Index("bookId")])
data class LoreCardEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val bookId: Long,
    /** character / faction / place / item / power / term */
    val type: String,
    val name: String,
    /** 逗号分隔别名 */
    val aliases: String = "",
    /** 一句话定位，例如“主要配角” */
    val role: String = "",
    /** 基础字段 JSON [{"k":"性格","v":"…"}] */
    val fields: String = "[]",
    /** auto（名字或别名出现时带入）/ always / never */
    val inject: String = "auto",
    val firstChapter: Int? = null,
    /** AI 建议卡待确认 */
    val pending: Boolean = false,
    val updatedAt: Long = System.currentTimeMillis(),
)

/** 按章生效的状态变化：从第 fromChapter 章起，字段 field 的值变为 value。 */
@Entity(tableName = "lore_states", foreignKeys = [ForeignKey(entity = LoreCardEntity::class, parentColumns = ["id"], childColumns = ["cardId"], onDelete = ForeignKey.CASCADE)], indices = [Index("cardId")])
data class LoreStateEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val cardId: Long,
    val fromChapter: Int,
    val field: String,
    val value: String,
    val note: String = "",
)

/** 人物关系：同一对人物可有多条记录，按 fromChapter 取当章最新的一条。 */
@Entity(tableName = "lore_relations", foreignKeys = [ForeignKey(entity = BookEntity::class, parentColumns = ["id"], childColumns = ["bookId"], onDelete = ForeignKey.CASCADE)], indices = [Index("bookId")])
data class LoreRelationEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val bookId: Long,
    val fromId: Long,
    val toId: Long,
    val label: String,
    val fromChapter: Int = 1,
    /** ally / enemy / love / family / mentor / neutral */
    val tone: String = "neutral",
)

@Entity(tableName = "maps", foreignKeys = [ForeignKey(entity = BookEntity::class, parentColumns = ["id"], childColumns = ["bookId"], onDelete = ForeignKey.CASCADE)], indices = [Index("bookId")])
data class MapEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val bookId: Long,
    val name: String,
    /** filesDir 下的相对路径 */
    val image: String,
)

/** 地图标注：点（地点）或多边形（势力范围），坐标为 0..1 归一化。 */
@Entity(tableName = "map_marks", foreignKeys = [ForeignKey(entity = MapEntity::class, parentColumns = ["id"], childColumns = ["mapId"], onDelete = ForeignKey.CASCADE)], indices = [Index("mapId")])
data class MapMarkEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val mapId: Long,
    val cardId: Long? = null,
    /** point / region */
    val kind: String,
    val label: String,
    /** JSON [[x,y],…] */
    val points: String,
    val color: Long = 0xFF3E5C8A,
    val fromChapter: Int = 1,
    val untilChapter: Int? = null,
)

@Dao
interface LoreDao {
    @Query("SELECT * FROM lore_cards WHERE bookId = :bookId ORDER BY pending DESC, type, name") fun cards(bookId: Long): Flow<List<LoreCardEntity>>
    @Query("SELECT * FROM lore_cards WHERE bookId = :bookId") suspend fun cardsOnce(bookId: Long): List<LoreCardEntity>
    @Query("SELECT COUNT(*) FROM lore_cards WHERE bookId = :bookId AND pending = 0") fun count(bookId: Long): Flow<Int>
    @Query("SELECT * FROM lore_cards WHERE id = :id") fun card(id: Long): Flow<LoreCardEntity?>
    @Query("SELECT * FROM lore_cards WHERE id = :id") suspend fun cardOnce(id: Long): LoreCardEntity?
    @Insert suspend fun insertCard(c: LoreCardEntity): Long
    @Update suspend fun updateCard(c: LoreCardEntity)
    @Query("DELETE FROM lore_cards WHERE id = :id") suspend fun deleteCard(id: Long)

    @Query("SELECT * FROM lore_states WHERE cardId = :cardId ORDER BY fromChapter") fun states(cardId: Long): Flow<List<LoreStateEntity>>
    @Query("SELECT s.* FROM lore_states s JOIN lore_cards c ON c.id = s.cardId WHERE c.bookId = :bookId") suspend fun statesForBook(bookId: Long): List<LoreStateEntity>
    @Insert suspend fun insertState(s: LoreStateEntity): Long
    @Query("DELETE FROM lore_states WHERE id = :id") suspend fun deleteState(id: Long)

    @Query("SELECT * FROM lore_relations WHERE bookId = :bookId ORDER BY fromChapter") fun relations(bookId: Long): Flow<List<LoreRelationEntity>>
    @Query("SELECT * FROM lore_relations WHERE bookId = :bookId") suspend fun relationsOnce(bookId: Long): List<LoreRelationEntity>
    @Insert suspend fun insertRelation(r: LoreRelationEntity): Long
    @Query("DELETE FROM lore_relations WHERE id = :id") suspend fun deleteRelation(id: Long)

    @Query("SELECT * FROM maps WHERE bookId = :bookId") fun maps(bookId: Long): Flow<List<MapEntity>>
    @Insert suspend fun insertMap(m: MapEntity): Long
    @Query("DELETE FROM maps WHERE id = :id") suspend fun deleteMap(id: Long)
    @Query("SELECT * FROM map_marks WHERE mapId = :mapId") fun marks(mapId: Long): Flow<List<MapMarkEntity>>
    @Insert suspend fun insertMark(m: MapMarkEntity): Long
    @Update suspend fun updateMark(m: MapMarkEntity)
    @Query("DELETE FROM map_marks WHERE id = :id") suspend fun deleteMark(id: Long)
}
