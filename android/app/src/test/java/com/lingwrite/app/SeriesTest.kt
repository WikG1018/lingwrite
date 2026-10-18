// SPDX-FileCopyrightText: 2026 WikG and LingWrite contributors
// SPDX-License-Identifier: AGPL-3.0-only
package com.lingwrite.app

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lingwrite.app.data.SeriesStore
import com.lingwrite.app.data.CopyResult
import com.lingwrite.app.data.Backup
import com.lingwrite.app.data.AppPrefs
import com.lingwrite.app.data.parseFields
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import com.lingwrite.app.data.db.SeriesLoreEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = Application::class)
class SeriesTest {
    private val ctx get() = ApplicationProvider.getApplicationContext<Application>()

    @Before fun seed() { AppGraph.init(ctx, inMemory = true) }

    @Test fun createLinkCopyAndDeleteSemantics() = runBlocking {
        val a = AppGraph.books.createBook("第一本")
        val b = AppGraph.books.createBook("第二本")
        val sid = SeriesStore.create("雾港宇宙")
        SeriesStore.addBook(sid, a)
        assertEquals(1, SeriesStore.books(sid).size)
        assertEquals(a, SeriesStore.books(sid).single().id)

        val loreId = SeriesStore.insertLore(SeriesLoreEntity(
            seriesId = sid, type = "character", name = "沈砚", aliases = "小沈",
            fields = """[{"k":"境界","v":"炼气"}]""",
            states = """[{"fromChapter":1,"field":"境界","value":"筑基"}]""",
        ))
        val item = SeriesStore.loreOnce(sid).single { it.id == loreId }
        val cardId = (SeriesStore.copyToBook(item, b) as com.lingwrite.app.data.CopyResult.Copied).cardId
        val card = AppGraph.db.lore().cardOnce(cardId)!!
        assertEquals("沈砚", card.name)
        assertEquals(b, card.bookId)
        assertEquals("筑基", AppGraph.db.lore().states(cardId).first().single().value)
        assertEquals("筑基", SeriesStore.parseStates(item.states).single().value)

        SeriesStore.removeBook(a)
        assertNull(AppGraph.db.books().get(a)!!.seriesId)
        SeriesStore.delete(sid)
        assertNull(AppGraph.db.books().get(b)!!.seriesId)
        assertEquals(0, SeriesStore.list().size)
        assertNotNull(AppGraph.db.books().get(b))
    }

    // 审查 #21：重名不重复复制；事务内复制状态与关系；追加系列来源。
    @Test fun copyDedupesAddsSourceAndRelations() = runBlocking {
        val b = AppGraph.books.createBook("目标书")
        val sid = SeriesStore.create("雾港宇宙")
        SeriesStore.addBook(sid, b)
        val master = SeriesStore.insertLore(SeriesLoreEntity(seriesId = sid, type = "character", name = "师父",
            relations = """[{"to":"沈砚","label":"师徒"}]"""))
        val pupil = SeriesStore.insertLore(SeriesLoreEntity(seriesId = sid, type = "character", name = "沈砚",
            relations = """[{"to":"师父","label":"弟子"}]"""))
        val items = SeriesStore.loreOnce(sid).associateBy { it.id }
        val first = SeriesStore.copyToBook(items.getValue(master), b)
        assertTrue(first is CopyResult.Copied)
        val second = SeriesStore.copyToBook(items.getValue(master), b)
        assertTrue("重名卡不重复复制", second is CopyResult.Duplicate)
        assertEquals((first as CopyResult.Copied).cardId, (second as CopyResult.Duplicate).cardId)
        assertEquals(1, AppGraph.db.lore().cardsOnce(b).count { it.name == "师父" })

        val card = AppGraph.db.lore().cardOnce(first.cardId)!!
        assertTrue("追加系列来源字段", parseFields(card.fields).any { it.v == "雾港宇宙" })

        assertEquals("对方卡还没复制时不建关系", 0, AppGraph.db.lore().relationsOnce(b).size)
        val p = SeriesStore.copyToBook(items.getValue(pupil), b) as CopyResult.Copied
        val rels = AppGraph.db.lore().relationsOnce(b)
        assertEquals(2, rels.size)
        assertTrue(rels.any { it.fromId == p.cardId && it.toId == first.cardId && it.label == "弟子" })
        assertTrue("补上先复制的卡指向本卡的关系", rels.any { it.fromId == first.cardId && it.toId == p.cardId && it.label == "师徒" })
    }

    @Test fun seriesBooksFlowUpdatesAfterLinking() = runBlocking {
        val a = AppGraph.books.createBook("A")
        val sid = SeriesStore.create("系列")
        assertEquals(0, AppGraph.db.books().inSeries(sid).first().size)
        SeriesStore.addBook(sid, a)
        assertEquals(listOf(a), AppGraph.db.books().inSeries(sid).first().map { it.id })
        assertTrue(AppGraph.db.books().unlinked().first().none { it.id == a })
        SeriesStore.removeBook(a)
        assertEquals(0, AppGraph.db.books().inSeries(sid).first().size)
    }

    @Test fun mergeImportTwiceDoesNotDuplicateSeries() = runBlocking {
        val a = AppGraph.books.createBook("系列书")
        val sid = SeriesStore.create("雾港宇宙")
        SeriesStore.addBook(sid, a)
        SeriesStore.insertLore(SeriesLoreEntity(seriesId = sid, type = "place", name = "雾港"))
        val bytes = ByteArrayOutputStream().also { Backup.writeZip(it, ctx, AppPrefs()) }.toByteArray()
        // 书同名同创建时间 → 跳过；系列同名同创建时间 → 复用，共享卡不翻倍
        repeat(2) { Backup.restore(Backup.readZip(ByteArrayInputStream(bytes)), ctx, overwrite = false) }
        assertEquals(1, SeriesStore.list().size)
        assertEquals(1, SeriesStore.loreOnce(sid).size)
        assertEquals(1, AppGraph.db.books().all().size)
    }

    @Test fun mergeImportNullsDanglingSeriesIdInsteadOfSkippingBook() = runBlocking {
        val a = AppGraph.books.createBook("孤书")
        val sid = SeriesStore.create("临时系列")
        SeriesStore.addBook(sid, a)
        val bytes = ByteArrayOutputStream().also { Backup.writeZip(it, ctx, AppPrefs()) }.toByteArray()
        // 手工编辑过的备份：删掉 series 表，books.seriesId 指向不存在的系列
        val arc = Backup.readZip(ByteArrayInputStream(bytes))
        val tables = kotlinx.serialization.json.JsonObject(arc.tables - "series" - "series_lore")
        val edited = arc.copy(json = kotlinx.serialization.json.JsonObject(arc.json + ("tables" to tables)))
        AppGraph.db.books().delete(a)
        Backup.restore(edited, ctx, overwrite = false)
        val restored = AppGraph.db.books().all().single()
        assertEquals("孤书", restored.title)
        assertNull(restored.seriesId)
    }
}
