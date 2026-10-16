// SPDX-FileCopyrightText: 2026 WikG and LingWrite contributors
// SPDX-License-Identifier: AGPL-3.0-only
package com.lingwrite.app

import androidx.compose.ui.geometry.Offset
import com.lingwrite.app.data.*
import com.lingwrite.app.data.db.*
import com.lingwrite.app.ui.routes.inPolygon
import com.lingwrite.app.ui.routes.layoutGraph
import org.junit.Assert.*
import org.junit.Test

class LoreTest {
    private val card = LoreCardEntity(id = 1, bookId = 1, type = "character", name = "沈砚", aliases = "砚哥，小沈、 阿砚", fields = encodeFields(listOf(Field("境界", "炼气"))))

    @Test fun aliases() = assertEquals(listOf("沈砚", "砚哥", "小沈", "阿砚"), card.allNames())

    @Test fun stateByChapter() {
        val st = listOf(LoreStateEntity(cardId = 1, fromChapter = 10, field = "境界", value = "筑基"), LoreStateEntity(cardId = 1, fromChapter = 30, field = "境界", value = "金丹"))
        assertEquals("炼气", resolveAt(card, st, 5).first { it.k == "境界" }.v)
        assertEquals("筑基", resolveAt(card, st, 12).first { it.k == "境界" }.v)
        assertEquals("金丹", resolveAt(card, st, 30).first { it.k == "境界" }.v)
    }

    @Test fun relationsLatestPerPair() {
        val r = listOf(LoreRelationEntity(1, 1, 1, 2, "师徒", 1), LoreRelationEntity(2, 1, 1, 2, "反目", 20, "enemy"))
        assertEquals("师徒", relationsAt(r, 10).single().label)
        assertEquals("反目", relationsAt(r, 25).single().label)
    }

    @Test fun mentionDetection() = assertEquals(1, mentioned(listOf(card), "那天阿砚独自上山").size)

    @Test fun geometry() {
        assertTrue(inPolygon(Offset(.5f, .5f), listOf(Offset(0f, 0f), Offset(1f, 0f), Offset(1f, 1f), Offset(0f, 1f))))
        assertFalse(inPolygon(Offset(1.5f, .5f), listOf(Offset(0f, 0f), Offset(1f, 0f), Offset(1f, 1f))))
        val l = layoutGraph(listOf(1, 2, 3), listOf(1L to 2L))
        assertEquals(3, l.size); assertTrue(l.values.all { it.x in 0f..1f && it.y in 0f..1f })
    }
}
