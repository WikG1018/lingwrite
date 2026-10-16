// SPDX-FileCopyrightText: 2026 WikG and LingWrite contributors
// SPDX-License-Identifier: AGPL-3.0-only
package com.lingwrite.app

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lingwrite.app.data.*
import com.lingwrite.app.data.db.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = android.app.Application::class)
class OutlineTest {
    private val db = LingDatabase.build(ApplicationProvider.getApplicationContext(), inMemory = true)
    private val repo = BookRepository(db)

    @Test fun json() {
        assertEquals(2, (extractJson("好的：```json\n[{\"a\":1},{\"a\":2}]\n```") as kotlinx.serialization.json.JsonArray).size)
        assertNull(extractJson("没有 JSON"))
        assertEquals(false, parseLanded("""{"event":true,"hook":false}""")!!.hook)
    }

    @Test fun gaps() {
        val p = { id: Long, t: String -> ChapterPlanEntity(id, 1, threads = t) }
        val g = threadGaps(listOf("主线", "感情线", "暗线"), listOf(1 to p(1, "主线,感情线"), 2 to p(2, "主线"), 12 to p(3, "主线")), 12)
        assertEquals(0, g[0].gap); assertEquals(11, g[1].gap); assertNull(g[2].lastAt)
    }

    @Test fun moveAndRenumber() = runBlocking {
        val b = repo.createBook("书")
        val v1 = db.chapters().volumesOnce(b).first().id
        val a = db.chapters().chaptersOnce(b).first().id
        val bId = repo.addChapterTo(b, v1, "二")
        val v2 = repo.addVolume(b, "第二卷")
        val c = repo.addChapterTo(b, v2, "三")
        repo.moveChapter(bId, -1)
        assertEquals(listOf(bId, a, c), db.chapters().chaptersOnce(b).map { it.id })
        repo.moveVolumeIn(b, v2, -1)
        assertEquals(c, db.chapters().chaptersOnce(b).first().id)
        repo.moveChapter(a, 1) // 卷末下移 → 进入下一卷
        assertEquals(listOf(1, 2, 3), db.chapters().chaptersOnce(b).map { it.number })
    }
}
