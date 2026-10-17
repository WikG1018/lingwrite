// SPDX-FileCopyrightText: 2026 WikG and LingWrite contributors
// SPDX-License-Identifier: AGPL-3.0-only
package com.lingwrite.app

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lingwrite.app.data.SeriesStore
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
        val cardId = SeriesStore.copyToBook(item, b)
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
}
