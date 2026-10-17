// SPDX-FileCopyrightText: 2026 WikG and LingWrite contributors
// SPDX-License-Identifier: AGPL-3.0-only
package com.lingwrite.app

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lingwrite.app.data.*
import com.lingwrite.app.data.db.CallLogEntity
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = Application::class)
class AiUsageTest {
    private val ctx get() = ApplicationProvider.getApplicationContext<Application>()

    @Before fun seed() { AppGraph.init(ctx, inMemory = true) }

    @Test fun reportCountsMarkedCharsAndAdoption() = runBlocking {
        val book = AppGraph.books.createBook("AI 体检")
        val ch = AppGraph.db.chapters().chaptersOnce(book).single()
        AppGraph.books.saveContent(ch.id, "0123456789", """[{"s":0,"e":5,"k":"gen"},{"s":5,"e":8,"k":"edited"}]""")
        val task = "continue"
        AppGraph.db.ai().insertLog(CallLogEntity(bookId = book, chapterId = ch.id, provider = "p", model = "m", task = task, outcome = "accepted"))
        AppGraph.db.ai().insertLog(CallLogEntity(bookId = book, chapterId = ch.id, provider = "p", model = "m", task = task, outcome = "rejected"))
        val r = AiUsage.report(book)
        assertEquals(1, r.chapters.size)
        assertEquals(5, r.chapters[0].share.genChars)
        assertEquals(3, r.chapters[0].share.editedChars)
        assertEquals(2, r.chapters[0].calls)
        assertEquals(50, r.adoptedPercent)
        assertEquals(1, r.tasks.single().calls)
        assertEquals(1, r.tasks.single().accepted)
    }
}
