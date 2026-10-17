// SPDX-FileCopyrightText: 2026 WikG and LingWrite contributors
// SPDX-License-Identifier: AGPL-3.0-only
package com.lingwrite.app

import com.lingwrite.app.data.ForeshadowCalendar
import com.lingwrite.app.data.db.ForeshadowEntity
import org.junit.Assert.*
import org.junit.Test

class ForeshadowCalendarTest {
    private fun f(id: Long, payoff: Int?, plant: Int = 1) = ForeshadowEntity(id = id, bookId = 1, content = "伏笔$id", plantNumber = plant, payoff = payoff)

    @Test fun groupsByStateAndPlannedChapter() {
        val c = ForeshadowCalendar.build(listOf(f(1, 2), f(2, 4), f(3, 5), f(4, 9), f(5, null)), current = 5)
        assertEquals(listOf(1L, 2L), c.overdue.map { it.id })
        assertEquals(listOf(3L), c.due.map { it.id })
        assertEquals(listOf(9), c.upcoming.map { it.chapter })
        assertEquals(listOf(5L), c.unscheduled.map { it.id })
    }

    @Test fun etaAndPacing() {
        assertEquals(3, ForeshadowCalendar.etaDays(9, 6, 1.0))
        assertEquals(2, ForeshadowCalendar.etaDays(9, 6, 2.0))
        assertNull(ForeshadowCalendar.etaDays(9, 9, 1.0))
        assertNull(ForeshadowCalendar.etaDays(9, 6, 0.0))
        assertTrue(ForeshadowCalendar.pacingWarning(listOf(f(1, 5, plant = 1)), 10))
        assertFalse(ForeshadowCalendar.pacingWarning(listOf(f(1, 5, plant = 8)), 10))
    }
}
