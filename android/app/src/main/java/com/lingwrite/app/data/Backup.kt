// SPDX-FileCopyrightText: 2026 WikG and LingWrite contributors
// SPDX-License-Identifier: AGPL-3.0-only
package com.lingwrite.app.data

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import com.lingwrite.app.AppGraph
import kotlinx.serialization.json.*
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/** 表结构：主键列（自增时可重映射）+ 外键列 → 目标表。 */
data class TableSpec(val name: String, val id: String?, val fks: Map<String, String> = emptyMap(), val pkIsFk: Boolean = false)

object Backup {
    const val FORMAT = 2
    /** 按依赖顺序；索引（passages / fts / chapter_index）不备份，导入后自动重建。 */
    val tables = listOf(
        TableSpec("books", "id"),
        TableSpec("volumes", "id", mapOf("bookId" to "books")),
        TableSpec("chapters", "id", mapOf("bookId" to "books", "volumeId" to "volumes")),
        TableSpec("chapter_versions", "id", mapOf("chapterId" to "chapters")),
        TableSpec("lore_cards", "id", mapOf("bookId" to "books")),
        TableSpec("lore_states", "id", mapOf("cardId" to "lore_cards")),
        TableSpec("lore_relations", "id", mapOf("bookId" to "books", "fromId" to "lore_cards", "toId" to "lore_cards")),
        TableSpec("maps", "id", mapOf("bookId" to "books")),
        TableSpec("map_marks", "id", mapOf("mapId" to "maps", "cardId" to "lore_cards")),
        TableSpec("book_plans", "bookId", mapOf("bookId" to "books"), pkIsFk = true),
        TableSpec("volume_plans", "volumeId", mapOf("volumeId" to "volumes", "bookId" to "books"), pkIsFk = true),
        TableSpec("chapter_plans", "chapterId", mapOf("chapterId" to "chapters", "bookId" to "books"), pkIsFk = true),
        TableSpec("chapter_summaries", "chapterId", mapOf("chapterId" to "chapters", "bookId" to "books"), pkIsFk = true),
        TableSpec("volume_summaries", "volumeId", mapOf("volumeId" to "volumes", "bookId" to "books"), pkIsFk = true),
        TableSpec("issues", "id", mapOf("bookId" to "books", "chapterId" to "chapters")),
        TableSpec("foreshadows", "id", mapOf("bookId" to "books", "chapterId" to "chapters", "cardId" to "lore_cards")),
        TableSpec("lore_suggestions", "id", mapOf("bookId" to "books", "chapterId" to "chapters", "cardId" to "lore_cards")),
        TableSpec("chapter_notes", "id", mapOf("chapterId" to "chapters")),
        TableSpec("word_days", null, mapOf("bookId" to "books")),
        TableSpec("ideas", "id", mapOf("bookId" to "books", "chapterId" to "chapters")),
        TableSpec("call_logs", "id", mapOf("bookId" to "books", "chapterId" to "chapters")),
        TableSpec("breakdowns", "id", mapOf("bookId" to "books")),
        TableSpec("reader_reports", "id", mapOf("bookId" to "books")),
    )

    private val nullableFks = setOf("map_marks.cardId", "foreshadows.chapterId", "foreshadows.cardId", "lore_suggestions.cardId", "ideas.bookId", "ideas.chapterId", "call_logs.bookId", "call_logs.chapterId", "breakdowns.bookId")

    private fun value(c: Cursor, i: Int): JsonElement = when (c.getType(i)) {
        Cursor.FIELD_TYPE_NULL -> JsonNull
        Cursor.FIELD_TYPE_INTEGER -> JsonPrimitive(c.getLong(i))
        Cursor.FIELD_TYPE_FLOAT -> JsonPrimitive(c.getDouble(i))
        else -> JsonPrimitive(c.getString(i))
    }

    /** 导出整库为 JSON（Key 永不导出：服务商只导出配置，keyCipher 置空）。 */
    fun dump(): JsonObject {
        val db = AppGraph.db.openHelper.readableDatabase
        db.beginTransaction()
        try { return buildJsonObject {
            put("format", FORMAT); put("app", "lingwrite"); put("exportedAt", System.currentTimeMillis()); put("schema", 8)
            putJsonObject("tables") {
                (tables.map { it.name } + "providers").forEach { t ->
                    putJsonArray(t) {
                        db.query("SELECT * FROM $t").use { c ->
                            while (c.moveToNext()) addJsonObject {
                                for (i in 0 until c.columnCount) {
                                    val col = c.getColumnName(i)
                                    put(col, if (t == "providers" && col == "keyCipher") JsonPrimitive("") else value(c, i))
                                }
                            }
                        }
                    }
                }
            }
        } } finally { db.endTransaction() }
    }

    fun writeZip(out: OutputStream, ctx: Context, prefs: AppPrefs) {
        ZipOutputStream(out).use { z ->
            z.putNextEntry(ZipEntry("lingwrite.json"))
            val j = JsonObject(dump() + ("prefs" to LingJson.encodeToJsonElement(AppPrefs.serializer(), prefs.copy(writingModel = "", autoModel = "", embedModel = ""))))
            z.write(j.toString().toByteArray()); z.closeEntry()
            File(ctx.filesDir, "maps").listFiles()?.forEach { f -> z.putNextEntry(ZipEntry("maps/${f.name}")); f.inputStream().use { it.copyTo(z) }; z.closeEntry() }
            File(ctx.filesDir, "covers").listFiles()?.filter { it.isFile && it.name.matches(Regex("[0-9]+\\.png")) }?.forEach { f -> z.putNextEntry(ZipEntry("covers/${f.name}")); f.inputStream().use { it.copyTo(z) }; z.closeEntry() }
        }
    }

    data class Archive(val json: JsonObject, val files: Map<String, ByteArray>) {
        val tables get() = json["tables"]!!.jsonObject
        fun count(t: String) = tables[t]?.jsonArray?.size ?: 0
        val bookTitles get() = tables["books"]?.jsonArray?.map { it.jsonObject.str("title").ifBlank { "未命名" } }.orEmpty()
        val exportedAt get() = json["exportedAt"]?.jsonPrimitive?.longOrNull ?: 0
    }

    fun readZip(input: InputStream): Archive {
        var json: JsonObject? = null
        val files = mutableMapOf<String, ByteArray>()
        ZipInputStream(input).use { z ->
            while (true) {
                val e = z.nextEntry ?: break
                val bytes = z.readBytes()
                if (e.name == "lingwrite.json") json = LingJson.parseToJsonElement(bytes.decodeToString()).jsonObject
                else if ((e.name.startsWith("maps/") || e.name.startsWith("covers/")) && !e.name.contains("..")) files[e.name] = bytes
            }
        }
        val j = json ?: error("不是灵写的备份文件")
        if (j.str("app") != "lingwrite") error("不是灵写的备份文件")
        if ((j["format"]?.jsonPrimitive?.intOrNull ?: 0) > FORMAT) error("备份来自更新的版本，请先升级灵写")
        if ((j["schema"]?.jsonPrimitive?.intOrNull ?: 1) > 8) error("备份数据库来自更新的版本，请先升级灵写")
        return Archive(j, files).also(::validate)
    }

    /** 空库备份仍有基本表；不能把残缺 JSON 当成可覆盖的空库。旧版新增表可缺省。 */
    fun validate(a: Archive) {
        val t = a.json["tables"] as? JsonObject ?: error("备份缺少数据表")
        listOf("books", "volumes", "chapters", "chapter_versions").forEach { require(t[it] is JsonArray) { "备份缺少 $it，未导入" } }
        a.files.keys.forEach { n ->
            val leaf = n.substringAfter('/', "")
            val mapOk = n.startsWith("maps/") && leaf.isNotBlank() && leaf != "." && !leaf.contains('/') && !leaf.contains('\\') && !leaf.contains(':') && !leaf.contains("..")
            val coverOk = n.startsWith("covers/") && leaf.matches(Regex("[0-9]+\\.png"))
            require(mapOk || coverOk) { "备份文件路径无效，未导入" }
        }
        val books = t["books"]!!.jsonArray.map { it.jsonObject["id"]!!.jsonPrimitive.long }.toSet()
        val volumes = t["volumes"]!!.jsonArray.map { it.jsonObject["id"]!!.jsonPrimitive.long }.toSet()
        t["volumes"]!!.jsonArray.forEach { require(it.jsonObject["bookId"]!!.jsonPrimitive.long in books) { "分卷引用缺失作品" } }
        t["chapters"]!!.jsonArray.forEach { c ->
            require(c.jsonObject["bookId"]!!.jsonPrimitive.long in books && c.jsonObject["volumeId"]!!.jsonPrimitive.long in volumes) { "章节引用缺失作品或分卷" }
        }
    }

    private val legacyDefaults = mapOf(
        "books" to mapOf("writeMode" to JsonNull, "bufferTarget" to JsonPrimitive(5), "publishedUpTo" to JsonPrimitive(0)),
        "chapters" to mapOf("deletedAt" to JsonPrimitive(0)),
        "ideas" to mapOf("chapterId" to JsonNull, "expansions" to JsonPrimitive("[]")),
        "call_logs" to mapOf("candidates" to JsonPrimitive(1), "chosen" to JsonPrimitive(-1)),
        "book_plans" to mapOf("idea" to JsonPrimitive(""), "logline" to JsonPrimitive(""), "sellingPoints" to JsonPrimitive("[]"), "chainStep" to JsonPrimitive(0), "chain" to JsonPrimitive("{}")),
        "volume_plans" to mapOf("goal" to JsonPrimitive(""), "climax" to JsonPrimitive(""), "chapterCount" to JsonPrimitive(0)),
        "chapter_plans" to mapOf("beats" to JsonPrimitive("[]"), "titleIdeas" to JsonPrimitive("[]")),
        "issues" to mapOf("fix" to JsonPrimitive("")), "foreshadows" to mapOf("payoffIdeas" to JsonPrimitive("")),
    )

    private fun cv(o: JsonObject, cols: Set<String>): ContentValues = ContentValues().apply {
        o.forEach { (k, v) ->
            if (k !in cols) return@forEach
            when {
                v is JsonNull -> putNull(k)
                v is JsonPrimitive && v.isString -> put(k, v.content)
                v is JsonPrimitive && v.longOrNull != null -> put(k, v.long)
                v is JsonPrimitive && v.doubleOrNull != null -> put(k, v.double)
                v is JsonPrimitive && v.booleanOrNull != null -> put(k, if (v.boolean) 1 else 0)
            }
        }
    }

    private fun columns(t: String): Set<String> {
        val db = AppGraph.db.openHelper.writableDatabase
        return db.query("PRAGMA table_info($t)").use { c -> buildSet { while (c.moveToNext()) add(c.getString(c.getColumnIndexOrThrow("name"))) } }
    }

    /** 导入。overwrite：清空作品数据后按原样写回；merge：作为新作品追加（同名且创建时间相同的作品跳过）。返回导入的作品数。 */
    fun restore(a: Archive, ctx: Context, overwrite: Boolean): Int {
        validate(a)
        val db = AppGraph.db.openHelper.writableDatabase
        val dir = File(ctx.filesDir, "maps").apply { mkdirs() }
        val staging = File(ctx.filesDir, "restore-${java.util.UUID.randomUUID()}").apply { check(mkdirs()) }
        val incoming = File(staging, "incoming").apply { check(mkdirs()) }
        val originals = File(staging, "originals").apply { check(mkdirs()) }
        // 在触碰数据库之前先写入完整图片；磁盘满或路径错误到此就停止。
        try { a.files.filterKeys { it.startsWith("maps/") }.forEach { (n, b) -> File(incoming, n.removePrefix("maps/")).writeBytes(b) } }
        catch (e: Exception) { staging.deleteRecursively(); throw e }
        val changed = mutableListOf<Pair<File, File?>>()
        val idMap = mutableMapOf<String, MutableMap<Long, Long>>()
        var imported = 0
        db.beginTransaction()
        var success = false
        try {
            val skipBooks = mutableSetOf<Long>()
            if (overwrite) {
                (tables.reversed().map { it.name } + listOf("passages_fts", "passages", "chapter_index")).forEach { db.execSQL("DELETE FROM $it") }
            } else {
                val existing = db.query("SELECT title, createdAt FROM books").use { c -> buildSet { while (c.moveToNext()) add(c.getString(0) + "|" + c.getLong(1)) } }
                a.tables["books"]?.jsonArray?.forEach { b -> val o = b.jsonObject; if (o.str("title") + "|" + o["createdAt"]!!.jsonPrimitive.long in existing) skipBooks += o["id"]!!.jsonPrimitive.long }
            }
            for (spec in tables) {
                val cols = columns(spec.name)
                val map = idMap.getOrPut(spec.name) { mutableMapOf() }
                rows@ for (row in a.tables[spec.name]?.jsonArray.orEmpty()) {
                    var o = JsonObject(legacyDefaults[spec.name].orEmpty() + row.jsonObject)
                    if (spec.name == "lore_cards" && o.str("type") == "concept") o = JsonObject(o + ("type" to JsonPrimitive("term")))
                    val bookRef = if (spec.name == "books") o["id"]?.jsonPrimitive?.longOrNull else o["bookId"]?.jsonPrimitive?.longOrNull
                    if (bookRef != null && bookRef in skipBooks) continue@rows
                    if (!overwrite) {
                        val m = o.toMutableMap()
                        for ((col, target) in spec.fks) {
                            val old = m[col]?.jsonPrimitive?.longOrNull ?: continue
                            val nv = idMap[target]?.get(old)
                            if (nv == null) { if (spec.pkIsFk && col == spec.id) continue@rows; m[col] = JsonNull } else m[col] = JsonPrimitive(nv)
                        }
                        // 不可空外键丢失时跳过整行
                        if (spec.fks.keys.any { k -> m[k] is JsonNull && o[k] !is JsonNull && "${spec.name}.$k" !in nullableFks }) continue@rows
                        val oldId = if (spec.id != null && !spec.pkIsFk) o[spec.id]?.jsonPrimitive?.longOrNull else null
                        if (oldId != null) m.remove(spec.id)
                        // JSON 内部的关联也必须随表主键重映射，避免合并后候选串到旧作品。
                        if (spec.name == "book_plans") m["chain"]?.jsonPrimitive?.contentOrNull?.let { raw ->
                            val st = ChainState.parse(raw)
                            m["chain"] = JsonPrimitive(st.copy(chapterVolumeId = st.chapterVolumeId?.let { idMap["volumes"]?.get(it) }).encode())
                        }
                        if (spec.name == "lore_suggestions") m["payload"]?.jsonPrimitive?.contentOrNull?.let { raw ->
                            val payload = runCatching { LingJson.parseToJsonElement(raw).jsonObject.toMutableMap() }.getOrNull()
                            if (payload != null) {
                                listOf("cardId", "targetId").forEach { key -> payload[key]?.jsonPrimitive?.longOrNull?.let { old -> payload[key] = idMap["lore_cards"]?.get(old)?.let(::JsonPrimitive) ?: JsonNull } }
                                m["payload"] = JsonPrimitive(JsonObject(payload).toString())
                            }
                        }
                        o = JsonObject(m)
                        val nid = db.insert(spec.name, android.database.sqlite.SQLiteDatabase.CONFLICT_REPLACE, cv(o, cols))
                        if (oldId != null) map[oldId] = nid
                    } else db.insert(spec.name, android.database.sqlite.SQLiteDatabase.CONFLICT_REPLACE, cv(o, cols))
                    if (spec.name == "books") imported++
                }
            }
            // 服务商：只补充本机没有的（Key 需重新填写）
            val have = db.query("SELECT name FROM providers").use { c -> buildSet { while (c.moveToNext()) add(c.getString(0)) } }
            val pc = columns("providers")
            a.tables["providers"]?.jsonArray?.forEach { p ->
                val o = p.jsonObject
                if (o.str("name") !in have) db.insert("providers", android.database.sqlite.SQLiteDatabase.CONFLICT_IGNORE, cv(JsonObject(o - "id" + ("keyCipher" to JsonPrimitive("")) + ("lastTest" to JsonPrimitive(""))), pc))
            }
            a.files.filterKeys { it.startsWith("maps/") }.forEach { (n, _) ->
                val name = n.removePrefix("maps/"); val target = File(dir, name)
                if (overwrite || !target.exists()) {
                    val original = if (target.exists()) File(originals, name).also { check(target.renameTo(it)) { "无法暂存原地图，恢复取消" } } else null
                    changed += target to original
                    check(File(incoming, name).renameTo(target)) { "无法恢复地图，恢复取消" }
                }
            }
            db.setTransactionSuccessful(); success = true
        } finally {
            var committed = false
            try { db.endTransaction(); committed = success }
            finally {
                if (!committed) changed.asReversed().forEach { (target, original) ->
                    if (target.exists()) check(target.delete()) { "恢复未完成；原地图保留在 $staging" }
                    if (original != null) check(original.renameTo(target)) { "恢复未完成；原地图保留在 $staging" }
                }
                staging.deleteRecursively()
            }
        }
        if (success) {
            val coverDir = File(ctx.filesDir, "covers").apply { mkdirs() }
            if (overwrite) coverDir.listFiles()?.forEach { it.delete() }
            a.files.forEach { (n, bytes) ->
                if (!n.startsWith("covers/")) return@forEach
                val old = n.removePrefix("covers/").removeSuffix(".png").toLongOrNull() ?: return@forEach
                val id = if (overwrite) old else idMap["books"]?.get(old) ?: return@forEach
                File(coverDir, "$id.png").writeBytes(bytes)
            }
        }
        AppGraph.db.invalidationTracker.refreshVersionsAsync()
        return imported
    }

    /** 导入前的自动备份，保留最近 5 个。 */
    fun autoBackup(ctx: Context, prefs: AppPrefs): File {
        val dir = File(ctx.filesDir, "backups").apply { mkdirs() }
        val f = File(dir, "auto-${java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.ROOT).format(java.util.Date())}.zip")
        f.outputStream().use { writeZip(it, ctx, prefs) }
        dir.listFiles { x -> x.name.startsWith("auto-") }?.sortedByDescending { it.name }?.drop(5)?.forEach { it.delete() }
        return f
    }

    fun autoBackups(ctx: Context): List<File> = File(ctx.filesDir, "backups").listFiles()?.sortedByDescending { it.name }.orEmpty()
}

/** 文本导出。 */
object Exporter {
        data class Options(val markdown: Boolean = false, val from: Int = 1, val to: Int = Int.MAX_VALUE, val titles: Boolean = true, val blurb: Boolean = true, val volumes: Boolean = true, val numStyle: ExportNumStyle = ExportNumStyle.Arabic)

    suspend fun render(bookId: Long, o: Options): String {
        val db = AppGraph.db
        val b = db.books().get(bookId) ?: return ""
        val volList = db.chapters().volumesOnce(bookId)
        val volIdx = volList.withIndex().associate { (i, v) -> v.id to (i + 1) }
        val vols = volList.associateBy { it.id }
        val chs = db.chapters().chaptersOnce(bookId).filter { it.number in o.from..o.to }.sortedBy { it.number }
        return buildString {
            append(if (o.markdown) "# ${b.title}\n\n" else "${b.title}\n\n")
            if (o.blurb && b.blurb.isNotBlank()) append(if (o.markdown) "> ${b.blurb.replace("\n", "\n> ")}\n\n" else "${b.blurb}\n\n")
            var lastVol = -1L
            chs.forEach { c ->
                if (o.volumes && c.volumeId != lastVol && vols.size > 1) {
                    lastVol = c.volumeId
                    val vt = vols[c.volumeId]?.let { VolumeLabel.full(volIdx[it.id] ?: 0, it.title) }.orEmpty()
                    append(if (o.markdown) "## $vt\n\n" else "$vt\n\n")
                }
                if (o.titles) append(if (o.markdown) "### ${ChapterLabel.export(c.number, c.title, o.numStyle)}\n\n" else "${ChapterLabel.export(c.number, c.title, o.numStyle)}\n\n")
                val paras = c.content.split('\n').map { it.trim() }.filter { it.isNotEmpty() }
                append(paras.joinToString(if (o.markdown) "\n\n" else "\n") { if (o.markdown) it else "　　$it" })
                append("\n\n")
            }
        }.trimEnd() + "\n"
    }
}
