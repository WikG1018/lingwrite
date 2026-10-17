// SPDX-FileCopyrightText: 2026 WikG and LingWrite contributors
// SPDX-License-Identifier: AGPL-3.0-only
package com.lingwrite.app

import android.app.Application
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lingwrite.app.data.*
import com.lingwrite.app.data.db.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipFile

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = Application::class)
class P0SafetyTest {
    private val ctx get() = ApplicationProvider.getApplicationContext<Application>()

    @Before fun seed() { AppGraph.init(ctx, inMemory = true) }

    private suspend fun seedBook(n: Int, body: (Int) -> String = { "第${it}章。" + "山风过处，松涛如海。".repeat(40) }): Long = AppGraph.db.withTransaction {
        val id = AppGraph.db.books().insert(BookEntity(title = "长篇测试"))
        val vol = AppGraph.db.chapters().insertVolume(VolumeEntity(bookId = id, title = "正文", sort = 0))
        val list = (1..n).map { i ->
            val text = body(i)
            ChapterEntity(bookId = id, volumeId = vol, number = i, title = "章$i", content = text, wordCount = Words.count(text), state = "done", updatedAt = i.toLong())
        }
        AppGraph.db.chapters().insertAll(list)
        AppGraph.db.books().touch(id)
        id
    }

    private fun rawLong(sql: String, args: Array<Any?> = emptyArray()): Long =
        AppGraph.db.openHelper.readableDatabase.query(sql, args).use { c -> if (c.moveToFirst()) c.getLong(0) else 0 }

    @Test fun heroAndBoardUseHeaderProjections() = runBlocking {
        val id = seedBook(500)
        val headers = AppGraph.db.chapters().headersOnce(id)
        assertEquals(500, headers.size)
        assertTrue("章节头不加载正文", headers.all { it.content.isEmpty() })
        val hero = AppGraph.books.hero(id)
        val state = SerialBoard.state(id)
        assertNotNull(hero)
        assertEquals(500, hero!!.chapters)
        assertEquals(500, state.buffer)
        assertEquals("最近编辑章的正文仍可用于摘要", AppGraph.db.chapters().lastContent(id), "第500章。" + "山风过处，松涛如海。".repeat(40))
    }

    @Test fun searchHasFinitePerTermCap() = runBlocking {
        val id = seedBook(220) { "测试关键词" }
        ContextEngine.refresh(id)
        assertEquals(30, ContextEngine.search("测试关键词", id, limit = 30).size)
        // 即使调用方要 1000 条，每个检索词也只在 SQL 里取 200 条，证明没有把 Int.MAX_VALUE 传给 SQL。
        assertEquals(200, ContextEngine.search("测试关键词", id, limit = 1000).size)
    }

    @Test fun refreshOnlyReindexesStaleChapters() = runBlocking {
        val id = seedBook(20) { "第一版内容" }
        ContextEngine.refresh(id)
        val before = AppGraph.db.context().indexState(id).associate { it.chapterId to it.at }
        assertEquals(20, before.size)
        val one = AppGraph.db.chapters().headersOnce(id).first()
        AppGraph.db.chapters().updateContent(one.id, "第二版内容", 5, "[]", System.currentTimeMillis() + 5)
        ContextEngine.refresh(id)
        val after = AppGraph.db.context().indexState(id).associate { it.chapterId to it.at }
        val changed = after.filter { (k, v) -> before[k] != v }.keys
        assertEquals(setOf(one.id), changed)
    }

    @Test fun deleteBookCleansFtsAndFiles() = runBlocking {
        val id = seedBook(1) { "唯一正文内容用于索引" }
        val chapter = AppGraph.db.chapters().get(AppGraph.db.chapters().headersOnce(id).single().id)!!
        ContextEngine.reindex(chapter)
        val passageIds = mutableListOf<Long>()
        AppGraph.db.openHelper.readableDatabase.query("SELECT id FROM passages WHERE bookId = ?", arrayOf(id)).use { c ->
            while (c.moveToNext()) passageIds += c.getLong(0)
        }
        assertTrue(passageIds.isNotEmpty())
        val mapFile = File(ctx.filesDir, "maps/p0-test.png").apply { parentFile?.mkdirs(); writeBytes(byteArrayOf(1, 2, 3)) }
        AppGraph.db.lore().insertMap(MapEntity(bookId = id, name = "测试地图", image = "maps/p0-test.png"))
        val cover = File(ctx.filesDir, "covers/$id.png").apply { parentFile?.mkdirs(); writeBytes(byteArrayOf(4)) }

        AppGraph.books.deleteBook(id)

        val marks = passageIds.joinToString(",") { "?" }
        assertEquals(0, rawLong("SELECT COUNT(*) FROM passages_fts WHERE rowid IN ($marks)", passageIds.toTypedArray()))
        assertFalse("地图文件应删除", mapFile.exists())
        assertFalse("封面文件应删除", cover.exists())
        assertTrue(AppGraph.db.books().all().none { it.id == id })
    }

    @Test fun backupSlimKeepsManualAndOnlyRecentAutoVersions() = runBlocking {
        val id = seedBook(1) { "初始" }
        val chapter = AppGraph.db.chapters().headersOnce(id).single()
        repeat(5) { AppGraph.books.snapshot(chapter.id, "auto") }
        AppGraph.books.snapshot(chapter.id, "manual")
        AppGraph.books.snapshot(chapter.id, "ai")
        val all = AppGraph.books.versions(chapter.id).first()
        assertEquals(7, all.size)

        fun versions(j: JsonObject): List<JsonObject> =
            j["tables"]!!.jsonObject["chapter_versions"]!!.jsonArray.map { it.jsonObject }
        val slim = versions(Backup.dump(includeAllVersions = false))
        val full = versions(Backup.dump(includeAllVersions = true))
        assertEquals(5, slim.size)
        assertEquals(3, slim.count { it["kind"]!!.jsonPrimitive.content == "auto" })
        assertTrue(slim.any { it["kind"]!!.jsonPrimitive.content == "manual" })
        assertTrue(slim.any { it["kind"]!!.jsonPrimitive.content == "ai" })
        assertEquals(7, full.size)

        val bytes = ByteArrayOutputStream().also { Backup.writeZip(it, ctx, AppPrefs(), includeAllVersions = false) }.toByteArray()
        val archive = Backup.readZip(ByteArrayInputStream(bytes))
        assertEquals(5, archive.count("chapter_versions"))
    }

    @Test fun upgradeGuardReadsHeaderAndSnapshotsOldDatabase() {
        val bytes = ByteArray(128).also { it[63] = 7.toByte() }
        val probe = File(ctx.cacheDir, "probe.db").apply { writeBytes(bytes) }
        assertEquals(7, UpgradeGuard.readUserVersion(probe))

        val dbFile = ctx.getDatabasePath("lingwrite.db").apply { parentFile?.mkdirs(); writeBytes(bytes) }
        val snapshot = UpgradeGuard.snapshotIfNeeded(ctx, currentVersion = 8)
        assertNotNull(snapshot)
        ZipFile(snapshot!!).use { z -> assertNotNull("主库应进快照", z.getEntry("lingwrite.db")) }

        bytes[63] = 8.toByte()
        dbFile.writeBytes(bytes)
        assertNull("同版本不重复备份", UpgradeGuard.snapshotIfNeeded(ctx, currentVersion = 8))
    }
}
