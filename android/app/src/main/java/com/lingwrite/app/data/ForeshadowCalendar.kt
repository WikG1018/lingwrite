// SPDX-FileCopyrightText: 2026 WikG and LingWrite contributors
// SPDX-License-Identifier: AGPL-3.0-only
package com.lingwrite.app.data

import com.lingwrite.app.data.db.ForeshadowEntity
import kotlin.math.ceil

data class PayoffGroup(val chapter: Int, val items: List<ForeshadowEntity>)

data class PayoffCalendar(
    val overdue: List<ForeshadowEntity>,
    val due: List<ForeshadowEntity>,
    val upcoming: List<PayoffGroup>,
    val unscheduled: List<ForeshadowEntity>,
)

/** 伏笔回收日历：按计划回收章分组，按更新速度估算天数；只读。 */
object ForeshadowCalendar {
    fun build(fs: List<ForeshadowEntity>, current: Int, dueWithin: Int = 3): PayoffCalendar {
        val open = fs.filter { !it.pending && it.status == "open" }
        val overdue = open.filter { it.payoff != null && it.payoff < current }.sortedBy { it.payoff }
        val due = open.filter { it.payoff != null && it.payoff in current..(current + dueWithin) }.sortedBy { it.payoff }
        val upcoming = open.filter { it.payoff != null && it.payoff > current + dueWithin }
            .groupBy { it.payoff!! }.toSortedMap().map { (ch, list) -> PayoffGroup(ch, list) }
        val unscheduled = open.filter { it.payoff == null }
        return PayoffCalendar(overdue, due, upcoming, unscheduled)
    }

    /** 按最近 7 天平均完成章数估算还需几天；数据不足返回 null。 */
    fun etaDays(payoffChapter: Int, current: Int, chaptersPerDay: Double): Int? {
        if (chaptersPerDay <= 0.0 || payoffChapter <= current) return null
        return ceil((payoffChapter - current) / chaptersPerDay).toInt()
    }

    /** 每 3 章至少埋一条新伏笔；最近 3 章没有新埋点就给提醒。 */
    fun pacingWarning(fs: List<ForeshadowEntity>, current: Int): Boolean {
        if (current < 3) return false
        val from = current - 2
        return fs.none { !it.pending && it.plantNumber in from..current }
    }
}
