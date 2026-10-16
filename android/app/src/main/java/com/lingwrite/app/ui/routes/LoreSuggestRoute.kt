// SPDX-FileCopyrightText: 2026 WikG and LingWrite contributors
// SPDX-License-Identifier: AGPL-3.0-only
package com.lingwrite.app.ui.routes

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.lazy.itemsIndexed
import com.lingwrite.app.ui.components.enterStagger
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.lingwrite.app.AppGraph
import com.lingwrite.app.data.db.LoreSuggestionEntity
import com.lingwrite.app.data.parseMap
import com.lingwrite.app.ui.components.*
import com.lingwrite.app.ui.theme.Ling
import kotlinx.coroutines.launch
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject

/**
 * 设定整理建议页（A2，ui-spec §5）：路由 lore-suggest/{bookId}。
 * 建议卡按种类分胶囊，状态变化写成旧值→新值；采纳后卡片折叠移出。
 */
@Composable
fun LoreSuggestRoute(bookId: Long, nav: NavHostController) {
    val c = Ling.colors
    val scope = rememberCoroutineScope()
    val pending by remember(bookId) { AppGraph.db.loreSuggest().pending(bookId) }.collectAsState(emptyList())
    val book by remember(bookId) { AppGraph.books.book(bookId) }.collectAsState(null)
    val chapterNo = pending.firstOrNull()?.number ?: 0

    Column(Modifier.fillMaxSize().background(c.background).statusBarsPadding()) {
        LingTopBar(
            "设定整理建议",
            "来自第 $chapterNo 章 · ${pending.size} 条",
            onBack = { nav.popBackStack() },
        ) {
            if (pending.isNotEmpty()) {
                LingButton("全部采纳", BtnKind.Text, height = 36.dp) {
                    scope.launch {
                        com.lingwrite.app.data.LoreSuggest.adoptAll(bookId)
                    }
                }
            }
        }
        if (pending.isEmpty()) {
            Text(
                "没有待处理的建议。写完一章（开着自动检查）或点「AI 整理本章」，AI 会从正文里整理出新人物、状态变化、别名和关系变化。",
                style = Ling.type.body, color = c.onSurfaceVariant, modifier = Modifier.padding(24.dp),
            )
        } else LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            itemsIndexed(pending, key = { _, it -> it.id }) { i, s -> Box(Modifier.enterStagger(i)) { SuggestionCardRow(s) } }
        }
    }
}

/** 建议卡：种类胶囊 + 旧值→新值 + 原文依据 + 采纳/忽略。 */
@Composable
private fun SuggestionCardRow(s: LoreSuggestionEntity) {
    val c = Ling.colors
    val scope = rememberCoroutineScope()
    val o = remember(s.payload) { runCatching { kotlinx.serialization.json.Json.parseToJsonElement(s.payload).jsonObject }.getOrNull() }
    val kind = when (s.kind) {
        "new_card" -> Triple("新人物", PillKind.Amber, LingIcons.PersonAdd)
        "state" -> Triple("状态变化", PillKind.Primary, LingIcons.TrendUp)
        "alias" -> Triple("新别名", PillKind.Neutral, LingIcons.Label)
        else -> Triple("关系", PillKind.Primary, LingIcons.Group)
    }
    var adopting by remember { mutableStateOf(false) }
    LingCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Pill(kind.first, kind.second, kind.third)
                Spacer(Modifier.width(8.dp))
                Text("第 ${s.number} 章", style = Ling.type.caption, color = c.onSurfaceVariant)
            }
            Spacer(Modifier.height(8.dp))
            when (s.kind) {
                "new_card" -> Text(
                    "新${o?.let { it.str("type") } ?: "设定"}：${o?.let { it.str("name") } ?: ""}",
                    style = Ling.type.bodyLarge, color = c.onSurface,
                )
                "state" -> {
                    val old = o?.let { it.str("value") }?.let { "（第 ${s.number} 章起变为）" }
                    Text("${o?.str("field") ?: "状态"}：→ ${o?.str("value") ?: ""}$old", style = Ling.type.bodyLarge, color = c.onSurface)
                }
                "alias" -> Text("别名：${o?.str("value") ?: o?.str("name") ?: ""}", style = Ling.type.bodyLarge, color = c.onSurface)
                else -> Text("关系：${o?.str("target") ?: ""} — ${o?.str("label") ?: "关联"}", style = Ling.type.bodyLarge, color = c.onSurface)
            }
            if (s.quote.isNotBlank()) {
                Text("原文依据：「${s.quote}」", style = Ling.type.caption, color = c.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
            }
            o?.str("why")?.takeIf { it.isNotBlank() }?.let {
                Text(it, style = Ling.type.caption, color = c.primary, modifier = Modifier.padding(top = 4.dp))
            }
            Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                LingButton(
                    if (adopting) "采纳中…" else "✓ 已采纳", BtnKind.Text, height = 34.dp,
                    enabled = !adopting, onClick = {
                        adopting = true
                        scope.launch {
                            com.lingwrite.app.data.LoreSuggest.adopt(s.id)
                        }
                    },
                )
                LingButton("忽略", BtnKind.Text, height = 34.dp) {
                    scope.launch { com.lingwrite.app.data.LoreSuggest.ignore(s.id) }
                }
            }
        }
    }
}

private fun kotlinx.serialization.json.JsonObject.str(k: String) = (this[k] as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull.orEmpty()
