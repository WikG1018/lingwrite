// SPDX-FileCopyrightText: 2026 WikG and LingWrite contributors
// SPDX-License-Identifier: AGPL-3.0-only
package com.lingwrite.app.ui.routes

import com.lingwrite.app.i18n.tr

import com.lingwrite.app.R

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lingwrite.app.AppGraph
import com.lingwrite.app.data.SerialBoard
import com.lingwrite.app.data.SerialState
import com.lingwrite.app.data.ShortUpdatePlan
import com.lingwrite.app.ui.components.BtnKind
import com.lingwrite.app.ui.components.LingButton
import com.lingwrite.app.ui.components.LingCard
import com.lingwrite.app.ui.components.LingIcons
import com.lingwrite.app.ui.components.SheetLayout
import com.lingwrite.app.ui.components.LingSheet
import com.lingwrite.app.ui.components.Pill
import com.lingwrite.app.ui.components.PillKind
import com.lingwrite.app.ui.components.rowClick
import com.lingwrite.app.ui.theme.Ling
import kotlinx.coroutines.launch

/**
 * 连载看板卡（A11，ui-spec §9）：今日字数 / 存稿 / 连续更新 / 章纲余量；
 * 存稿不足或章纲余量 < 3 章时整卡转琥珀色，提供「短更预案」。
 */
@Composable
fun SerialBoardCard(bookId: Long) {
    val c = Ling.colors
    val scope = rememberCoroutineScope()
    var state by remember { mutableStateOf<SerialState?>(null) }
    var plan by remember { mutableStateOf<ShortUpdatePlan?>(null) }
    var planBusy by remember { mutableStateOf(false) }
    var planErr by remember { mutableStateOf<String?>(null) }
    var published by remember { mutableStateOf(false) }
    val chapterUpdates by remember(bookId) { AppGraph.books.chapterHeaders(bookId) }.collectAsState(emptyList())
    val planUpdates by remember(bookId) { AppGraph.db.plan().chapters(bookId) }.collectAsState(emptyList())
    val wordUpdates by remember(bookId) { AppGraph.db.stats().bookWordDays(bookId) }.collectAsState(emptyList())
    LaunchedEffect(bookId, chapterUpdates, planUpdates, wordUpdates) {
        runCatching { SerialBoard.state(bookId) }.onSuccess { state = it }
    }

    state?.let { s ->
        LingCard(
            Modifier.padding(horizontal = 16.dp).fillMaxWidth(),
            color = if (s.low) c.tertiaryContainer else c.surface,
        ) {
            Column(Modifier.padding(14.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(tr(R.string.serial_board_card_001), style = Ling.type.label, color = if (s.low) c.onTertiaryContainer else c.primary, modifier = Modifier.weight(1f))
                    Pill(tr(R.string.serial_board_card_002, s.publishedUpTo), PillKind.Neutral, modifier = Modifier.rowClick { published = true })
                }
                Row(Modifier.padding(top = 10.dp).fillMaxWidth().height(IntrinsicSize.Min)) {
                    listOf(
                        Triple("${s.todayWords}", tr(R.string.serial_board_card_003), if (s.dailyTarget > 0 && s.todayWords < s.dailyTarget) c.tertiary else c.onSurface),
                        Triple("${s.buffer}/${s.bufferTarget}", tr(R.string.serial_board_card_004), if (s.buffer < s.bufferTarget) c.tertiary else c.onSurface),
                        Triple("${s.streak}", tr(R.string.serial_board_card_005), c.onSurface),
                        Triple("${s.planAhead}", tr(R.string.serial_board_card_006), if (s.planAhead < 3) c.tertiary else c.onSurface),
                    ).forEachIndexed { i, (n, l, col) ->
                        if (i > 0) Box(Modifier.width(1.dp).fillMaxHeight().background(c.outlineVariant))
                        Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(n, style = Ling.type.numeral.copy(fontSize = 22.sp), color = col)
                            Text(l, style = Ling.type.caption.copy(fontSize = 12.sp), color = c.onSurfaceVariant)
                        }
                    }
                }
                Row(Modifier.padding(top = 10.dp).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End), verticalAlignment = Alignment.CenterVertically) {
                    LingButton(tr(R.string.serial_board_card_007), BtnKind.Text, height = 32.dp) { published = true }
                    if (s.low) {
                        LingButton(if (planBusy) tr(R.string.serial_board_card_008) else tr(R.string.serial_board_card_009), BtnKind.Tonal, height = 32.dp, enabled = !planBusy) {
                            planBusy = true; planErr = null
                            scope.launch {
                                runCatching { SerialBoard.plan(bookId, s) }
                                    .onSuccess { plan = it }
                                    .onFailure { planErr = it.message ?: tr(R.string.book_ai_018) }
                                planBusy = false
                            }
                        }
                    }
                }
                planErr?.let { Text(tr(R.string.serial_board_card_010, it), style = Ling.type.caption, color = c.error, modifier = Modifier.padding(top = 4.dp)) }
            }
        }
    }

    if (published) {
        var n by remember { mutableStateOf("${state?.publishedUpTo ?: 0}") }
        com.lingwrite.app.ui.components.LingConfirm(
            tr(R.string.serial_board_card_011),
            tr(R.string.serial_board_card_012),
            onDismiss = { published = false },
            onConfirm = {
                val v = n.toIntOrNull() ?: 0
                scope.launch {
                    AppGraph.db.books().get(bookId)?.let { AppGraph.books.updateBook(it.copy(publishedUpTo = v)) }
                    state = SerialBoard.state(bookId)
                    published = false
                }
            },
            extra = {
                com.lingwrite.app.ui.components.LingTextField(n, { n = it.filter(Char::isDigit) }, Modifier.padding(top = 4.dp), label = tr(R.string.serial_board_card_013))
            },
        )
    }

    plan?.let { p ->
        var applied by remember { mutableStateOf(false) }
        LingSheet({ plan = null }, tr(R.string.serial_board_card_009)) {
            Column(Modifier.padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Pill(tr(R.string.serial_board_card_014), PillKind.Primary)
                listOf(
                    tr(R.string.serial_board_card_015, p.splitA.event, p.splitA.hook, p.splitA.cliff),
                    tr(R.string.serial_board_card_016, p.splitB.event, p.splitB.hook, p.splitB.cliff),
                ).forEach { t -> Text(t, style = Ling.type.body, color = Ling.colors.onSurface) }
                if (p.bridges.isNotEmpty()) {
                    Pill(tr(R.string.serial_board_card_017), PillKind.Amber)
                    p.bridges.forEach { b ->
                        Text(tr(R.string.serial_board_card_018, b.event, b.why), style = Ling.type.body, color = Ling.colors.onSurface)
                    }
                }
                Text(tr(R.string.serial_board_card_019), style = Ling.type.caption, color = Ling.colors.onSurfaceVariant)
                if (!applied) LingButton(tr(R.string.serial_board_card_020), height = 46.dp, modifier = Modifier.fillMaxWidth()) {
                    applied = true
                    scope.launch {
                        val chs = AppGraph.db.chapters().chaptersOnce(bookId).sortedBy { it.number }
                        val target = chs.firstOrNull { it.state != "done" } ?: chs.lastOrNull()
                        if (target != null) {
                            val cp = AppGraph.db.plan().chapterOnce(target.id) ?: com.lingwrite.app.data.db.ChapterPlanEntity(target.id, bookId)
                            val note = tr(R.string.serial_board_card_021, p.splitA.event, p.splitB.event)
                            AppGraph.db.plan().upsertChapter(cp.copy(note = cp.note + (if (cp.note.isNotBlank()) "\n" else "") + note))
                        }
                        plan = null
                        runCatching { SerialBoard.state(bookId) }.onSuccess { state = it }
                    }
                }
            }
        }
    }
}
