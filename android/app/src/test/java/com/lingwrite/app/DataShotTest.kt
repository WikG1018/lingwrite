// SPDX-FileCopyrightText: 2026 WikG and LingWrite contributors
// SPDX-License-Identifier: AGPL-3.0-only
package com.lingwrite.app

import androidx.test.core.app.ApplicationProvider
import com.lingwrite.app.data.*
import com.lingwrite.app.data.db.*
import com.lingwrite.app.ui.routes.BackupRoute
import com.lingwrite.app.ui.routes.ExportRoute
import com.lingwrite.app.ui.routes.LocalNav
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

class DataShotTest : RouteShotBase() {
    private val ctx get() = ApplicationProvider.getApplicationContext<android.app.Application>()

    override suspend fun extraSeed(bookId: Long) {
        val card = AppGraph.db.lore().insertCard(LoreCardEntity(bookId = bookId, type = "character", name = "沈砚"))
        AppGraph.db.lore().insertState(LoreStateEntity(cardId = card, fromChapter = 3, field = "境界", value = "筑基"))
        val ch = AppGraph.db.chapters().chaptersOnce(bookId).first()
        AppGraph.db.checks().insertForeshadow(ForeshadowEntity(bookId = bookId, content = "铜钱", chapterId = ch.id, plantNumber = 1, cardId = card))
        AppGraph.db.plan().upsertChapter(ChapterPlanEntity(ch.id, bookId, event = "命案"))
        AppGraph.db.ai().insertProvider(ProviderEntity(presetId = "custom", name = "测", protocol = "openai", baseUrl = "x", auth = "bearer", models = "m", keyCipher = "SECRET"))
    }

    private fun zip(): ByteArray = ByteArrayOutputStream().also { Backup.writeZip(it, ctx, AppPrefs()) }.toByteArray()

    @Test fun backupNoKeysAndMerge() = runBlocking {
        val bytes = zip()
        val a = Backup.readZip(ByteArrayInputStream(bytes))
        assertFalse(a.json.toString().contains("SECRET"))
        val chapters = AppGraph.db.chapters().chaptersOnce(bookId).size
        // 合并同一部作品：跳过
        assertEquals(0, Backup.restore(a, ctx, overwrite = false))
        // 改名后合并：作为新作品加入，外键全部重映射
        AppGraph.db.books().get(bookId)!!.let { AppGraph.db.books().update(it.copy(title = "改名")) }
        assertEquals(1, Backup.restore(a, ctx, overwrite = false))
        val books = AppGraph.db.books().all()
        assertEquals(2, books.size)
        val nb = books.first { it.id != bookId }.id
        assertEquals(chapters, AppGraph.db.chapters().chaptersOnce(nb).size)
        val card = AppGraph.db.lore().cardsOnce(nb).single()
        assertEquals(1, AppGraph.db.lore().statesForBook(nb).count { it.cardId == card.id })
        val fs = AppGraph.db.checks().foreshadowsOnce(nb).single()
        assertEquals(card.id, fs.cardId)
        assertEquals(nb, AppGraph.db.chapters().get(fs.chapterId!!)!!.bookId)
        assertEquals("命案", AppGraph.db.plan().chaptersOnce(nb).single().event)
        assertEquals(1, AppGraph.db.ai().providersOnce().size)
    }

    @Test fun overwriteRestores() = runBlocking {
        val a = Backup.readZip(ByteArrayInputStream(zip()))
        AppGraph.books.createBook("临时")
        Backup.restore(a, ctx, overwrite = true)
        assertEquals(listOf(bookId), AppGraph.db.books().all().map { it.id })
        assertTrue(Backup.autoBackup(ctx, AppPrefs()).length() > 100)
        assertEquals("ok", runCatching { Backup.readZip(ByteArrayInputStream("x".toByteArray())) }.fold({ "bad" }, { "ok" }))
    }

    @Test fun exportText() = runBlocking {
        val t = Exporter.render(bookId, Exporter.Options(from = 1, to = 2))
        val md = Exporter.render(bookId, Exporter.Options(markdown = true, from = 1, to = 1))
        assertTrue(t.contains("第1章")); assertFalse(t.contains("第3章")); assertTrue(md.startsWith("# ")); assertTrue(md.contains("### 第1章"))
    }

    @Test fun exportShot() = shoot("22-export") { ExportRoute(bookId, LocalNav.current) }
    @Test fun backupShot() = shoot("23-backup") { BackupRoute(LocalNav.current) }
}
