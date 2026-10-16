// SPDX-FileCopyrightText: 2026 WikG and LingWrite contributors
// SPDX-License-Identifier: AGPL-3.0-only
package com.lingwrite.app

import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lingwrite.app.data.LingJson
import com.lingwrite.app.data.db.LingDatabase
import com.lingwrite.app.data.db.MIGRATION_1_2
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.File

/**
 * 迁移测试：按 schemas/ 里导出的旧版 DDL 建库、插入数据，跑迁移后断言（docs/v0.2-plan.md §4）。
 * 不用 MigrationTestHelper：AGP 9 的单测 assets 不进 Robolectric 的 apk-for-local-test，
 * 这里直接从文件系统读 schema JSON，只验证迁移本身对数据的效果。
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = android.app.Application::class)
class MigrationTest {
    private val schemaDir = sequenceOf(File("schemas"), File("app/schemas")).firstOrNull { it.isDirectory }!!

    /** 旧版 schema 里的建表语句（含 FTS 虚拟表）。 */
    private fun ddl(version: Int): List<String> {
        val root = LingJson.parseToJsonElement(File(schemaDir, "com.lingwrite.app.data.db.LingDatabase/$version.json").readText()).jsonObject["database"]!!.jsonObject
        // 建表 + 建索引：Room 真实建库时两者都会执行；只建表会让索引校验误报（如 index_volumes_bookId）
        return root["entities"]!!.jsonArray.flatMap { e ->
            val o = e.jsonObject
            val table = o["tableName"]!!.jsonPrimitive.content
            val idx = o["indices"]?.jsonArray.orEmpty().map { it.jsonObject["createSql"]!!.jsonPrimitive.content }
            (listOf(o["createSql"]!!.jsonPrimitive.content) + idx).map { it.replace("\${TABLE_NAME}", table) }
        }
    }

    private fun openV1(): SupportSQLiteDatabase = openAt(1)

    /** 按指定版本的 schema DDL 建库。 */
    private fun openAt(version: Int): SupportSQLiteDatabase {
        val config = SupportSQLiteOpenHelper.Configuration.builder(ApplicationProvider.getApplicationContext())
            .name(null)
            .callback(object : SupportSQLiteOpenHelper.Callback(version) {
                override fun onCreate(db: SupportSQLiteDatabase) = ddl(version).forEach(db::execSQL)
                override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) {}
            })
            .build()
        return FrameworkSQLiteOpenHelperFactory().create(config).writableDatabase
    }

    private fun scalar(db: SupportSQLiteDatabase, sql: String): String =
        db.query(sql).use { c -> c.moveToFirst(); c.getString(0) ?: "" }

    @Test fun migrate1To2() {
        val db = openV1()
        db.execSQL("INSERT INTO books (id, title, genre, status, coverIndex, dailyTarget, blurb, wizardStep, createdAt, updatedAt) VALUES (1, '测试书', '仙侠', 'serial', 0, 0, '', 0, 0, 0)")
        db.execSQL("INSERT INTO volumes (id, bookId, title, sort) VALUES (1, 1, '第一卷', 0)")
        db.execSQL("INSERT INTO volumes (id, bookId, title, sort) VALUES (2, 1, '第2卷 · 风起', 1)")
        db.execSQL("INSERT INTO volumes (id, bookId, title, sort) VALUES (3, 1, '回到第一卷的故事', 2)")
        db.execSQL("INSERT INTO chapters (id, bookId, volumeId, number, title, state, content, wordCount, aiMarks, updatedAt) VALUES (1, 1, 1, 1, '第一章', 'draft', '', 0, '[]', 0)")
        db.execSQL("INSERT INTO chapters (id, bookId, volumeId, number, title, state, content, wordCount, aiMarks, updatedAt) VALUES (2, 1, 1, 2, '第 2 章', 'draft', '', 0, '[]', 0)")
        db.execSQL("INSERT INTO chapters (id, bookId, volumeId, number, title, state, content, wordCount, aiMarks, updatedAt) VALUES (3, 1, 1, 3, '第 3 章 夜雨', 'draft', '', 0, '[]', 0)")

        MIGRATION_1_2.migrate(db)

        assertEquals("", scalar(db, "SELECT title FROM volumes WHERE id = 1"))
        assertEquals("风起", scalar(db, "SELECT title FROM volumes WHERE id = 2"))
        // 不在开头的编号不动
        assertEquals("回到第一卷的故事", scalar(db, "SELECT title FROM volumes WHERE id = 3"))
        assertEquals("", scalar(db, "SELECT title FROM chapters WHERE id = 1"))
        assertEquals("", scalar(db, "SELECT title FROM chapters WHERE id = 2"))
        assertEquals("夜雨", scalar(db, "SELECT title FROM chapters WHERE id = 3"))
        db.close()
    }

    @Test fun migrate2To3() {
        val db = openV1()
        // 先跑 1→2，再插入 M2 相关数据并升到 3
        MIGRATION_1_2.migrate(db)
        db.execSQL("INSERT INTO books (id, title, genre, status, coverIndex, dailyTarget, blurb, wizardStep, createdAt, updatedAt) VALUES (1, '测试书', '仙侠', 'serial', 0, 0, '', 0, 0, 0)")
        db.execSQL("INSERT INTO chapters (id, bookId, volumeId, number, title, state, content, wordCount, aiMarks, updatedAt) VALUES (1, 1, 1, 1, '夜雨', 'draft', '', 0, '[]', 0)")
        db.execSQL("INSERT INTO call_logs (id, time, provider, model, task, inputTokens, outputTokens, outcome, scope, error, estimated) VALUES (1, 0, 'p', 'm', 'continue', 10, 20, 'accepted', '', '', 0)")
        db.execSQL("INSERT INTO ideas (id, text, kind, answer, usedAs, pinned, createdAt) VALUES (1, '问题', 'ask', '回答', '', 0, 0)")

        com.lingwrite.app.data.db.MIGRATION_2_3.migrate(db)

        // 新列带默认值，旧行可用
        assertEquals("1", scalar(db, "SELECT candidates FROM call_logs WHERE id = 1"))
        assertEquals("-1", scalar(db, "SELECT chosen FROM call_logs WHERE id = 1"))
        assertEquals("", scalar(db, "SELECT writeMode FROM books WHERE id = 1"))
        assertEquals("0", scalar(db, "SELECT COUNT(*) FROM chapter_notes"))
        db.execSQL("INSERT INTO chapter_notes (chapterId, kind, text, done, createdAt) VALUES (1, 'direction', '方向', 0, 0)")
        assertEquals("方向", scalar(db, "SELECT text FROM chapter_notes WHERE chapterId = 1"))
        db.close()
    }

    @Test fun migrate3To4() {
        val db = openV1()
        MIGRATION_1_2.migrate(db)
        com.lingwrite.app.data.db.MIGRATION_2_3.migrate(db)
        // 按 v3 schema 插入：带向导草稿和总纲的书
        db.execSQL("INSERT INTO books (id, title, genre, status, coverIndex, dailyTarget, blurb, wizardStep, createdAt, updatedAt) VALUES (1, '测试书', '仙侠', 'serial', 0, 0, '', 8, 0, 0)")
        db.execSQL("INSERT INTO volumes (id, bookId, title, sort) VALUES (1, 1, '山门', 0)")
        db.execSQL("INSERT INTO chapters (id, bookId, volumeId, number, title, state, content, wordCount, aiMarks, updatedAt) VALUES (1, 1, 1, 1, '夜雨', 'draft', '', 0, '[]', 0)")
        db.execSQL("INSERT INTO book_plans (bookId, synopsis, fields, threads, gapThreshold, golden, goldenResult, goldenAt, templates, style, wizard) VALUES (1, '断剑少年查旧案', '[]', '[\"主线\"]', 8, '', '[]', 0, '', '', '{\"idea\":\"断剑少年\",\"premise\":\"梗概文本\"}')")
        db.execSQL("INSERT INTO volume_plans (volumeId, bookId, outline) VALUES (1, 1, '本卷卷纲')")
        db.execSQL("INSERT INTO chapter_plans (chapterId, bookId, event, hook, cliff, note, custom, threads, fromAi, landed) VALUES (1, 1, '命案', '钩子', '悬念', '', '{}', '主线', 1, '')")

        com.lingwrite.app.data.db.MIGRATION_3_4.migrate(db)

        // wizard JSON 里的 idea/premise 拷进新列；有总纲的书 chainStep 记到 3
        assertEquals("断剑少年", scalar(db, "SELECT idea FROM book_plans WHERE bookId = 1"))
        assertEquals("梗概文本", scalar(db, "SELECT logline FROM book_plans WHERE bookId = 1"))
        assertEquals("3", scalar(db, "SELECT chainStep FROM book_plans WHERE bookId = 1"))
        // volume_plans 新列默认值，旧行可用
        assertEquals("", scalar(db, "SELECT goal FROM volume_plans WHERE volumeId = 1"))
        assertEquals("本卷卷纲", scalar(db, "SELECT outline FROM volume_plans WHERE volumeId = 1"))
        assertEquals("0", scalar(db, "SELECT chapterCount FROM volume_plans WHERE volumeId = 1"))
        assertEquals("[]", scalar(db, "SELECT beats FROM chapter_plans WHERE chapterId = 1"))
        assertEquals("[]", scalar(db, "SELECT titleIdeas FROM chapter_plans WHERE chapterId = 1"))
        // 新列可写
        db.execSQL("UPDATE chapter_plans SET beats = '[{\"text\":\"抛钩子\",\"done\":false}]' WHERE chapterId = 1")
        assertTrue("beats 列应可写", scalar(db, "SELECT beats FROM chapter_plans WHERE chapterId = 1").contains("抛钩子"))
        db.close()
    }

    @Test fun migrate4To5() {
        val db = openV1()
        MIGRATION_1_2.migrate(db)
        com.lingwrite.app.data.db.MIGRATION_2_3.migrate(db)
        com.lingwrite.app.data.db.MIGRATION_3_4.migrate(db)
        db.execSQL("INSERT INTO books (id, title, genre, status, coverIndex, dailyTarget, blurb, wizardStep, createdAt, updatedAt) VALUES (1, '测试书', '仙侠', 'serial', 0, 0, '', 0, 0, 0)")
        db.execSQL("INSERT INTO volumes (id, bookId, title, sort) VALUES (1, 1, '山门', 0)")
        db.execSQL("INSERT INTO chapters (id, bookId, volumeId, number, title, state, content, wordCount, aiMarks, updatedAt) VALUES (1, 1, 1, 1, '夜雨', 'draft', '', 0, '[]', 0)")
        db.execSQL("INSERT INTO issues (id, bookId, chapterId, number, kind, quote, start, basis, explain, status, note, createdAt) VALUES (1, 1, 1, 1, 'persona', 'q', -1, 'b', 'e', 'open', '', 0)")
        db.execSQL("INSERT INTO foreshadows (id, bookId, content, chapterId, plantNumber, quote, quoteStart, payoff, status, cardId, pending, paidAt, createdAt) VALUES (1, 1, '断剑来历', 1, 1, 'q', -1, 8, 'open', NULL, 0, NULL, 0)")

        com.lingwrite.app.data.db.MIGRATION_4_5.migrate(db)

        // 新列默认值，旧行可用
        assertEquals("", scalar(db, "SELECT fix FROM issues WHERE id = 1"))
        assertEquals("", scalar(db, "SELECT payoffIdeas FROM foreshadows WHERE id = 1"))
        // 新表可写
        assertEquals("0", scalar(db, "SELECT COUNT(*) FROM lore_suggestions"))
        db.execSQL("INSERT INTO lore_suggestions (bookId, chapterId, number, kind, cardId, payload, quote, status, createdAt) VALUES (1, 1, 1, 'new_card', NULL, '{}', 'q', 'pending', 0)")
        assertEquals("new_card", scalar(db, "SELECT kind FROM lore_suggestions WHERE bookId = 1"))
        assertEquals("1", scalar(db, "SELECT COUNT(*) FROM lore_suggestions WHERE bookId = 1 AND status = 'pending'"))
        db.close()
    }

    @Test fun migrate5To6() {
        val db = openV1()
        MIGRATION_1_2.migrate(db)
        com.lingwrite.app.data.db.MIGRATION_2_3.migrate(db)
        com.lingwrite.app.data.db.MIGRATION_3_4.migrate(db)
        com.lingwrite.app.data.db.MIGRATION_4_5.migrate(db)
        db.execSQL("INSERT INTO books (id, title, genre, status, coverIndex, dailyTarget, blurb, wizardStep, createdAt, updatedAt) VALUES (1, '测试书', '仙侠', 'serial', 0, 4000, '', 0, 0, 0)")
        db.execSQL("INSERT INTO ideas (id, text, kind, answer, usedAs, pinned, createdAt) VALUES (1, '灵感', 'idea', '', '', 0, 0)")

        com.lingwrite.app.data.db.MIGRATION_5_6.migrate(db)

        // 新列默认值，旧行可用
        assertEquals("5", scalar(db, "SELECT bufferTarget FROM books WHERE id = 1"))
        assertEquals("0", scalar(db, "SELECT publishedUpTo FROM books WHERE id = 1"))
        assertEquals("[]", scalar(db, "SELECT expansions FROM ideas WHERE id = 1"))
        // 新列可写
        db.execSQL("UPDATE books SET publishedUpTo = 115 WHERE id = 1")
        assertEquals("115", scalar(db, "SELECT publishedUpTo FROM books WHERE id = 1"))
        db.close()
    }

    @Test fun migrate6To7() {        val db = openV1()
        MIGRATION_1_2.migrate(db)
        com.lingwrite.app.data.db.MIGRATION_2_3.migrate(db)
        com.lingwrite.app.data.db.MIGRATION_3_4.migrate(db)
        com.lingwrite.app.data.db.MIGRATION_4_5.migrate(db)
        com.lingwrite.app.data.db.MIGRATION_5_6.migrate(db)

        com.lingwrite.app.data.db.MIGRATION_6_7.migrate(db)

        // 新表可写入分析结果与读者报告
        db.execSQL("INSERT INTO breakdowns (id, bookId, name, chapters, curve, template, notes, createdAt) VALUES (1, NULL, '样章', '[]', '[3,4]', '[{\"chapter\":1,\"pattern\":\"主角困境\"}]', '[]', 0)")
        db.execSQL("INSERT INTO reader_reports (id, bookId, fromChapter, toChapter, persona, result, contentHash, createdAt) VALUES (1, 1, 1, 1, 'veteran', '{}', 'abc', 0)")
        assertEquals("样章", scalar(db, "SELECT name FROM breakdowns WHERE id = 1"))
        assertEquals("veteran", scalar(db, "SELECT persona FROM reader_reports WHERE id = 1"))
        db.close()
    }

    /**
     * 全链迁移走 Room（v0.2-plan §6 风险项）：按 v1 schema 建库并写数据，
     * 再用 Room（version 7 + MIGRATIONS）打开。Room 会把迁移结果与编译期 schema 逐项校验
     * （列、索引、外键、identity hash），迁移写错会直接抛异常；同时验证旧数据可用、新表可写。
     */
    @Test fun roomOpensAfterFullChainMigration() {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        val name = "mig-1to7-test.db"
        ctx.deleteDatabase(name)
        // 1) 按 v1 schema 手工建库 + 插入 v0.1 时代的数据
        val cfg = SupportSQLiteOpenHelper.Configuration.builder(ctx).name(name)
            .callback(object : SupportSQLiteOpenHelper.Callback(1) {
                override fun onCreate(db: SupportSQLiteDatabase) = ddl(1).forEach(db::execSQL)
                override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) {}
            }).build()
        val raw = FrameworkSQLiteOpenHelperFactory().create(cfg).writableDatabase
        raw.execSQL("INSERT INTO books (id, title, genre, status, coverIndex, dailyTarget, blurb, wizardStep, createdAt, updatedAt) VALUES (1, '旧书', '仙侠', 'serial', 0, 0, '', 0, 0, 0)")
        raw.execSQL("INSERT INTO volumes (id, bookId, title, sort) VALUES (1, 1, '第一卷', 0)")
        raw.execSQL("INSERT INTO chapters (id, bookId, volumeId, number, title, state, content, wordCount, aiMarks, updatedAt) VALUES (1, 1, 1, 1, '第一章', 'done', '正文', 2, '[]', 0)")
        raw.close()

        // 2) Room 打开：依次跑 1→7 的全部迁移并校验 schema
        val db = androidx.room.Room.databaseBuilder(ctx, LingDatabase::class.java, name)
            .addMigrations(*com.lingwrite.app.data.db.MIGRATIONS)
            .allowMainThreadQueries() // 测试里直接查，不关心主线程
            .build()
        try {
            val book = kotlinx.coroutines.runBlocking { db.books().get(1) }
            assertEquals("旧书", book?.title)
            // 1→2 的编号剥离确实生效
            assertEquals("", kotlinx.coroutines.runBlocking { db.chapters().get(1) }?.title)
            assertEquals("正文", kotlinx.coroutines.runBlocking { db.chapters().get(1) }?.content)
            // v7 才有的新表可写
            kotlinx.coroutines.runBlocking {
                db.notes().insert(com.lingwrite.app.data.db.ChapterNoteEntity(chapterId = 1, kind = "direction", text = "迁移后可用"))
            }
            assertEquals("1", db.query("SELECT COUNT(*) FROM chapter_notes", null).use { c -> c.moveToFirst(); c.getString(0) })
            assertEquals("8", db.query("PRAGMA user_version", null).use { c -> c.moveToFirst(); c.getString(0) })
        } finally {
            db.close()
            ctx.deleteDatabase(name)
        }
    }
}
