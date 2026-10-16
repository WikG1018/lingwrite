// SPDX-FileCopyrightText: 2026 WikG and LingWrite contributors
// SPDX-License-Identifier: AGPL-3.0-only
package com.lingwrite.app.ui.routes

import com.lingwrite.app.i18n.tr
import com.lingwrite.app.i18n.trZh

import com.lingwrite.app.R

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavHostController
import androidx.navigation.compose.composable
import com.lingwrite.app.AppGraph
import com.lingwrite.app.data.*
import com.lingwrite.app.data.ai.PromptHooks
import com.lingwrite.app.data.ai.Slot
import com.lingwrite.app.data.db.BookPlanEntity
import com.lingwrite.app.ui.ExtraDestinations
import com.lingwrite.app.ui.components.*
import com.lingwrite.app.ui.screens.SettingRow
import com.lingwrite.app.ui.screens.Tone
import com.lingwrite.app.ui.theme.Ling
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

object StyleModule {
    var tasteFor by mutableStateOf<Long?>(null)

    fun install() {
        PromptHooks.style = { bookId, p ->
            val card = AppGraph.db.plan().bookOnce(bookId)?.style.orEmpty()
            buildString {
                if (p.styleEnabled) append(trZh(R.string.style_001)).append(p.stylePrompt.ifBlank { DEFAULT_STYLE_PROMPT }).append('\n')
                if (card.isNotBlank()) append(trZh(R.string.style_002)).append(card).append('\n')
                val b = bannedList(p.bannedWords)
                if (b.isNotEmpty()) append(trZh(R.string.style_003)).append(b.joinToString(tr(R.string.data_043))).append('\n')
            }
        }
        ExtraDestinations.list.add { nav: NavHostController ->
            composable("style/{bookId}") { e: androidx.navigation.NavBackStackEntry -> StyleRoute(e.arguments!!.getString("bookId")!!.toLong(), nav) }
        }
        SettingsRegistry.extra += { _, nav -> tr(R.string.author_guide_017) to SettingRow(LingIcons.Draw, tr(R.string.style_004), tr(R.string.style_005), tone = Tone.Primary, onClick = { nav.navigate("style/0") }) }
        val prevMore = EditorHooks.moreItems
        EditorHooks.moreItems = { st, close, nav ->
            prevMore(this, st, close, nav)
            SheetAction(LingIcons.FactCheck, tr(R.string.editor_ai_026), sub = tr(R.string.style_006)) { tasteFor = st.chapter.id; close() }
            SheetAction(LingIcons.Draw, tr(R.string.style_007), sub = tr(R.string.style_008)) { close(); nav.navigate("style/${st.chapter.bookId}") }
        }
        val prevOv = EditorHooks.overlays
        EditorHooks.overlays = { st, nav ->
            prevOv(st, nav)
            if (tasteFor == st.chapter.id) TasteSheet(st.chapter.bookId, st.value.text, onJump = { f ->
                st.value = TextFieldValue(st.value.text, TextRange(f.start, f.end.coerceAtMost(st.value.text.length))); tasteFor = null
            }) { tasteFor = null }
        }
    }
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun StyleRoute(bookId: Long, nav: NavHostController) {
    val c = Ling.colors
    val scope = rememberCoroutineScope()
    val ctx = LocalContext.current
    val p0 by remember { AppGraph.prefs.flow }.collectAsState(AppGraph.prefs.snapshot)
    val p = p0 ?: return
    val plan by remember(bookId) { AppGraph.db.plan().book(bookId) }.collectAsState(null)
    var prompt by remember(p.stylePrompt) { mutableStateOf(p.stylePrompt.ifBlank { DEFAULT_STYLE_PROMPT }) }
    val banned = remember(p.bannedWords) { mutableStateListOf<String>().apply { addAll(bannedList(p.bannedWords)) } }
    var word by remember { mutableStateOf("") }
    var card by remember(plan?.style) { mutableStateOf(plan?.style.orEmpty()) }
    var learning by remember { mutableStateOf(false) }
    var err by remember { mutableStateOf<String?>(null) }
    fun saveBanned() = scope.launch { AppGraph.prefs.update { it.copy(bannedWords = banned.joinToString(",")) } }
    fun learn(text: String) {
        learning = true; err = null
        scope.launch {
            AppGraph.ai.complete("style", trZh(R.string.style_009), WizardPrompts.style(text), bookId.takeIf { it > 0 }, null, 600, Slot.Writing)
                .onSuccess { card = it.trim(); AppGraph.db.plan().upsertBook((AppGraph.db.plan().bookOnce(bookId) ?: BookPlanEntity(bookId)).copy(style = card)) }
                .onFailure { err = it.message }
            learning = false
        }
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris ->
        if (uris.isNotEmpty()) scope.launch { learn(withContext(Dispatchers.IO) { uris.joinToString("\n\n") { ctx.contentResolver.openInputStream(it)?.bufferedReader()?.readText().orEmpty() } }) }
    }
    Column(Modifier.fillMaxSize().background(c.background).statusBarsPadding()) {
        LingTopBar(tr(R.string.style_004), tr(R.string.style_010), onBack = { nav.popBackStack() })
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            LingCard(color = c.primaryContainer.copy(alpha = .5f)) {
                Text(tr(R.string.style_011),
                    style = Ling.type.caption.copy(lineHeight = 19.sp), color = c.onSurface, modifier = Modifier.padding(14.dp))
            }
            SectionHeader(tr(R.string.style_012), tr(R.string.style_013))
            LingCard {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(tr(R.string.style_014), style = Ling.type.bodyLarge, color = c.onSurface, modifier = Modifier.weight(1f))
                        Switch(p.styleEnabled, { v -> scope.launch { AppGraph.prefs.update { it.copy(styleEnabled = v) } } })
                    }
                    LingTextField(prompt, { prompt = it }, singleLine = false, minLines = 4, maxLines = 10)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        LingButton(tr(R.string.outline_089), BtnKind.Text, height = 36.dp) { prompt = DEFAULT_STYLE_PROMPT; scope.launch { AppGraph.prefs.update { it.copy(stylePrompt = "") } } }
                        Spacer(Modifier.weight(1f))
                        LingButton(tr(R.string.check_046), BtnKind.Tonal, height = 36.dp, enabled = prompt != p.stylePrompt.ifBlank { DEFAULT_STYLE_PROMPT }) {
                            scope.launch { AppGraph.prefs.update { it.copy(stylePrompt = if (prompt.trim() == DEFAULT_STYLE_PROMPT.trim()) "" else prompt.trim()) } }
                        }
                    }
                }
            }
            SectionHeader(tr(R.string.style_015), tr(R.string.style_016, banned.size))
            LingCard {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        banned.toList().forEach { w -> InputChip(true, { banned.remove(w); saveBanned() }, { Text(w, style = Ling.type.label) }, trailingIcon = { Icon(LingIcons.Close, tr(R.string.check_044), Modifier.size(14.dp)) }) }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        LingTextField(word, { word = it }, Modifier.weight(1f), placeholder = tr(R.string.style_017))
                        Spacer(Modifier.width(8.dp))
                        LingButton(tr(R.string.models_034), BtnKind.Tonal, enabled = word.isNotBlank()) { bannedList(word).forEach { if (it !in banned) banned += it }; word = ""; saveBanned() }
                    }
                    LingButton(tr(R.string.style_018), BtnKind.Text, height = 34.dp) { banned.clear(); banned.addAll(bannedList(AppPrefs.DEFAULT_BANNED)); saveBanned() }
                }
            }
            if (bookId > 0) {
                SectionHeader(tr(R.string.style_007), tr(R.string.style_019))
                LingCard {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        LingTextField(card, { card = it }, singleLine = false, minLines = 4, maxLines = 10, placeholder = tr(R.string.style_020))
                        err?.let { Text(it, style = Ling.type.caption, color = c.error) }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            LingButton(if (learning) tr(R.string.style_021) else tr(R.string.style_022), BtnKind.Tonal, height = 36.dp, enabled = !learning) {
                                scope.launch { learn(AppGraph.db.chapters().chaptersOnce(bookId).filter { it.content.isNotBlank() }.take(5).joinToString("\n\n") { it.content }) }
                            }
                            LingButton(tr(R.string.style_023), BtnKind.Tonal, height = 36.dp, enabled = !learning) { picker.launch("text/*") }
                            Spacer(Modifier.weight(1f))
                            LingButton(tr(R.string.check_046), height = 36.dp, enabled = card != plan?.style.orEmpty()) {
                                scope.launch { AppGraph.db.plan().upsertBook((AppGraph.db.plan().bookOnce(bookId) ?: BookPlanEntity(bookId)).copy(style = card.trim())) }
                            }
                        }
                        Text(tr(R.string.style_024), style = Ling.type.caption, color = c.onSurfaceVariant)
                    }
                }
            }
            Spacer(Modifier.height(32.dp))
        }
    }
}

@Composable
private fun SectionHeader(title: String, sub: String) {
    Column(Modifier.padding(start = 12.dp, top = 8.dp)) {
        Text(title, style = Ling.type.label.copy(fontWeight = FontWeight.SemiBold), color = Ling.colors.primary)
        Text(sub, style = Ling.type.caption, color = Ling.colors.onSurfaceVariant)
    }
}

@Composable
fun TasteSheet(bookId: Long, text: String, onJump: (TasteFlag) -> Unit, onDismiss: () -> Unit) {
    val p by remember { AppGraph.prefs.flow }.collectAsState(AppGraph.prefs.snapshot)
    val flags = remember(text, p?.bannedWords) { aiTaste(text, bannedList(p?.bannedWords ?: AppPrefs.DEFAULT_BANNED)) }
    LingSheet(onDismiss, tr(R.string.editor_ai_026)) { TasteList(bookId, flags, onJump) }
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun TasteList(bookId: Long, flags: List<TasteFlag>, onJump: (TasteFlag) -> Unit) {
    val c = Ling.colors
    val scope = rememberCoroutineScope()
    val sugg = remember { mutableStateMapOf<Int, String>() }
    Column(Modifier.padding(horizontal = 16.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(if (flags.isEmpty()) tr(R.string.style_025) else tr(R.string.style_026, flags.size), style = Ling.type.caption, color = c.onSurfaceVariant, modifier = Modifier.padding(horizontal = 8.dp))
        flags.forEach { f ->
            LingCard(Modifier.fillMaxWidth(), color = c.surfaceContainer.copy(alpha = .7f)) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) { f.reasons.take(4).forEach { Pill(it, PillKind.Amber) } }
                    Text(f.sentence, style = Ling.type.body.copy(lineHeight = 22.sp), color = c.onSurface)
                    if (sugg[f.start] == "…") com.lingwrite.app.ui.ai.AiThinking(tr(R.string.check_064)) else sugg[f.start]?.let { Text(tr(R.string.style_027, it), style = Ling.type.caption.copy(lineHeight = 18.sp), color = c.primary, modifier = Modifier.clip(RoundedCornerShape(8.dp)).background(c.primaryContainer.copy(alpha = .4f)).padding(8.dp)) }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        LingButton(tr(R.string.check_065), height = 34.dp) { onJump(f) }
                        LingButton(if (sugg[f.start] == "…") tr(R.string.ai_components_003) else tr(R.string.style_028), BtnKind.Text, height = 34.dp, enabled = sugg[f.start] != "…") {
                            sugg[f.start] = "…"
                            scope.launch {
                                val pr = AppGraph.prefs.flow.first()
                                sugg[f.start] = AppGraph.ai.complete("aitaste", com.lingwrite.app.data.ai.Prompts.BASE + "\n" + PromptHooks.style(bookId, pr),
                                    trZh(R.string.style_029, f.reasons.joinToString(tr(R.string.data_043)), f.sentence), bookId, null, 200, Slot.Writing)
                                    .getOrElse { tr(R.string.style_030, it.message) }.trim()
                            }
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(16.dp))
    }
}
