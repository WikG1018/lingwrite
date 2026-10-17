// SPDX-FileCopyrightText: 2026 WikG and LingWrite contributors
// SPDX-License-Identifier: AGPL-3.0-only
package com.lingwrite.app.ui.screens

import com.lingwrite.app.i18n.tr
import com.lingwrite.app.i18n.trText

import com.lingwrite.app.R

import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.HazeStyle
import dev.chrisbanes.haze.HazeTint
import dev.chrisbanes.haze.haze
import dev.chrisbanes.haze.hazeChild
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.*
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.input.key.*
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.*
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextIndent
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.lingwrite.app.data.FakeData
import com.lingwrite.app.data.AiMark
import com.lingwrite.app.data.WriteMode
import com.lingwrite.app.ui.ai.AiMainButton
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.input.*
import androidx.compose.ui.text.style.LineBreak
import com.lingwrite.app.ui.components.*
import com.lingwrite.app.ui.theme.Ling
import com.lingwrite.app.ui.theme.Motion
import com.lingwrite.app.ui.theme.Radius

data class EditorActions(
    val onBack: () -> Unit = {}, val onUndo: () -> Unit = {}, val onLore: () -> Unit = {}, val onMore: () -> Unit = {},
    val onQuote: () -> Unit = {}, val onForeshadow: () -> Unit = {}, val onCharacters: () -> Unit = {}, val onCheck: () -> Unit = {},
    val onAi: () -> Unit = {}, val onNotes: () -> Unit = {},
    val onRedo: () -> Unit = {}, val onSave: () -> Unit = {},
)

/** 截图 / 预览用的示例正文。 */
fun demoManuscript(): Pair<TextFieldValue, List<AiMark>> {
    val text = FakeData.manuscript.joinToString("\n") { it.replace("{", "").replace("}", "").removePrefix("[ai]") }
    val s = text.indexOf(tr(R.string.editor_048)); val e = text.indexOf(tr(R.string.editor_049)) + 2
    return TextFieldValue(text, TextRange(text.length)) to listOf(AiMark(s, e, "gen"))
}

@Composable
fun EditorScreen(
    title: String = tr(R.string.editor_050),
    subtitle: String = tr(R.string.editor_051),
    value: TextFieldValue = remember { demoManuscript().first },
    onValueChange: (TextFieldValue) -> Unit = {},
    marks: List<AiMark> = remember { demoManuscript().second },
    names: List<String> = listOf(tr(R.string.editor_052), tr(R.string.editor_053)),
    todayDelta: String = tr(R.string.editor_054),
    canUndo: Boolean = true,
    canRedo: Boolean = false,
    actions: EditorActions = EditorActions(),
    readOnly: Boolean = false,
    mode: WriteMode = WriteMode.Generate,
    notesUnread: Int = 0,
    emptyHint: String? = null,
    badge: (@Composable () -> Unit)? = null,
    beats: List<com.lingwrite.app.data.Beat> = emptyList(),
    onBeatToggle: (Int) -> Unit = {},
    proofRanges: List<IntRange> = emptyList(),
    noticeBar: (@Composable () -> Unit)? = null,
    proofBar: (@Composable () -> Unit)? = null,
    proofBubble: (@Composable () -> Unit)? = null,
    directory: (@Composable () -> Unit)? = null,
    tools: (@Composable (Int) -> Unit)? = null,
    bottomPanel: @Composable ColumnScope.() -> Unit = { SuggestionCard(FakeData.suggestion, tr(R.string.editor_055)); ToolChips(0) {} },
) {
    val editorHaze = remember { HazeState() }
    val c = Ling.colors
    val focus = LocalFocusManager.current
    var toolsOpen by rememberSaveable { mutableStateOf(false) }
    var toolTab by rememberSaveable { mutableIntStateOf(0) }
    val responsive = directory != null && tools != null
    val tablet = androidx.compose.ui.platform.LocalConfiguration.current.screenWidthDp >= 720 && responsive
    val a = actions.copy(onAi = { toolTab = 3; toolsOpen = true; actions.onAi() })
    WritingWorkspace(toolsOpen && responsive, toolTab == 3, responsive,
        Modifier.fillMaxSize().background(c.paper).imePadding().onPreviewKeyEvent { e ->
            if (e.type != KeyEventType.KeyDown || value.composition != null) false
            else if (e.key == Key.Tab && !e.isCtrlPressed && !e.isAltPressed) {
                focus.moveFocus(if (e.isShiftPressed) FocusDirection.Previous else FocusDirection.Next); true
            } else if (!e.isCtrlPressed) false else when (e.key) {
                Key.Z -> { if (e.isShiftPressed) actions.onRedo() else actions.onUndo(); true }
                Key.Y -> { actions.onRedo(); true }
                Key.S -> { actions.onSave(); true }
                else -> false
            }
        }, directory = { directory?.invoke() }, manuscript = {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val compactHeader = maxWidth < 600.dp
        Column(Modifier.fillMaxSize()) {
            Box(Modifier.statusBarsPadding()) {
            if (compactHeader) {
                // 右侧图标行叠在标题栏上，给它留出位置，长标题（如英文）才不会压到图标下面
                LingTopBar(title, subtitle, actions.onBack, titleStyle = Ling.type.subhead) { Spacer(Modifier.width(if (tablet) 232.dp else 144.dp)) }
                Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.End) {
                if (tablet) LingButton(if (toolsOpen) tr(R.string.editor_056) else tr(R.string.editor_057), BtnKind.Text, height = 36.dp, modifier = Modifier.testTag("toggle-writing-tools")) { toolsOpen = !toolsOpen }
                badge?.invoke()
                LingIconButton(LingIcons.Undo, tr(R.string.check_042), tint = if (canUndo) c.onSurface else c.onSurface.copy(alpha = 0.35f), onClick = actions.onUndo)
                LingIconButton(LingIcons.Redo, tr(R.string.editor_058), tint = if (canRedo) c.onSurface else c.onSurface.copy(alpha = 0.35f), onClick = actions.onRedo)
                LingIconButton(LingIcons.More, tr(R.string.editor_059), onClick = actions.onMore)
                }
            } else {
            LingTopBar(title, subtitle, actions.onBack, titleStyle = Ling.type.subhead) {
                if (tablet) LingButton(if (toolsOpen) tr(R.string.editor_056) else tr(R.string.editor_057), BtnKind.Text, height = 36.dp, modifier = Modifier.testTag("toggle-writing-tools")) { toolsOpen = !toolsOpen }
                badge?.invoke()
                LingIconButton(LingIcons.Undo, tr(R.string.check_042), tint = if (canUndo) c.onSurface else c.onSurface.copy(alpha = 0.35f), onClick = actions.onUndo)
                LingIconButton(LingIcons.Redo, tr(R.string.editor_058), tint = if (canRedo) c.onSurface else c.onSurface.copy(alpha = 0.35f), onClick = actions.onRedo)
                LingIconButton(LingIcons.More, tr(R.string.editor_059), onClick = actions.onMore)
            }
            }
            }
            Column(Modifier.weight(1f).fillMaxWidth()) {
                if (noticeBar != null) Column(Modifier.fillMaxWidth().heightIn(max = 160.dp).verticalScroll(rememberScrollState())) { noticeBar() }
                proofBar?.invoke()
                if (beats.isNotEmpty()) BeatsChecklist(beats, onBeatToggle)
                Box(Modifier.weight(1f).haze(editorHaze)) {
                ManuscriptField(value, onValueChange, marks, names, readOnly, Modifier.align(Alignment.TopCenter).widthIn(max = 800.dp).fillMaxSize(), proofRanges)
                if (emptyHint != null && value.text.isBlank() && !readOnly) {
                    Text(
                        emptyHint, style = Ling.type.body.copy(lineHeight = 30.sp), color = c.onSurfaceVariant,
                        modifier = Modifier.align(Alignment.TopStart).padding(start = 34.dp, end = 24.dp, top = 14.dp),
                    )
                }
                // 校对气泡浮在正文上（ui-spec §7）
                if (proofBubble != null) Box(Modifier.align(Alignment.TopCenter).padding(top = 210.dp)) { proofBubble() }
                }
            }
        }
        Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth()) {
            BottomToolbar(todayDelta, a, mode, notesUnread, editorHaze)
        }
    }
    }, tools = {
        Column(Modifier.fillMaxSize().background(c.surface)) {
            Row(Modifier.fillMaxWidth().statusBarsPadding().horizontalScroll(rememberScrollState()).padding(8.dp)) {
                listOf(tr(R.string.outline_050), tr(R.string.lore_007), tr(R.string.check_024), "AI").forEachIndexed { i, label ->
                    LingButton(label, if (toolTab == i) BtnKind.Tonal else BtnKind.Text, height = 36.dp) { toolTab = i }
                }
            }
            Box(Modifier.weight(1f)) { tools?.invoke(toolTab) }
        }
    }, ai = { Column(Modifier.fillMaxWidth()) { if (tablet) Text(tr(R.string.editor_060), style = Ling.type.caption, color = c.onSurfaceVariant, modifier = Modifier.padding(12.dp)); bottomPanel() } })
}

/** 正文输入区：首行缩进两字、AI 段落底色 + 虚线、人物名下划线。 */
@Composable
fun ManuscriptField(value: TextFieldValue, onValueChange: (TextFieldValue) -> Unit, marks: List<AiMark>, names: List<String>, readOnly: Boolean, modifier: Modifier, proofRanges: List<IntRange> = emptyList()) {
    val c = Ling.colors
    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
    val transform = remember(marks, names, c) {
        VisualTransformation { t ->
            val ann = buildAnnotatedString {
                append(t.text)
                marks.forEach { m ->
                    val s = m.s.coerceIn(0, t.length); val e = m.e.coerceIn(0, t.length)
                    if (e > s) addStyle(SpanStyle(background = c.amber.copy(alpha = if (m.k == "gen") 0.16f else 0.08f)), s, e)
                }
                names.filter { it.isNotBlank() }.forEach { n ->
                    var i = t.text.indexOf(n)
                    while (i >= 0) { addStyle(SpanStyle(color = c.primary, textDecoration = TextDecoration.Underline), i, i + n.length); i = t.text.indexOf(n, i + n.length) }
                }
            }
            TransformedText(ann, OffsetMapping.Identity)
        }
    }
    val scroll = rememberScrollState()
    BasicTextField(
        value, onValueChange, readOnly = readOnly,
        textStyle = Ling.type.manuscript.copy(color = c.paperInk, textIndent = TextIndent(firstLine = 2.em), lineBreak = LineBreak.Paragraph),
        cursorBrush = SolidColor(c.primary), visualTransformation = transform,
        onTextLayout = { layout = it },
        modifier = modifier.testTag("manuscript-input").fillMaxWidth().verticalScroll(scroll).padding(start = 24.dp, end = 24.dp, top = 6.dp, bottom = 280.dp)
            .drawBehind {
                val l = layout ?: return@drawBehind
                val len = value.text.length
                marks.filter { it.k == "gen" }.forEach { m ->
                    val s = m.s.coerceIn(0, len); val e = m.e.coerceIn(0, len)
                    if (e <= s) return@forEach
                    val ls = l.getLineForOffset(s); val le = l.getLineForOffset((e - 1).coerceAtLeast(s))
                    for (line in ls..le) {
                        val x0 = if (line == ls) l.getHorizontalPosition(s, true) else l.getLineLeft(line)
                        val x1 = if (line == le) l.getHorizontalPosition(e, true) else l.getLineRight(line)
                        val y = l.getLineBottom(line) - (31.sp.toPx() - 17.sp.toPx()) / 2 + 2.dp.toPx()
                        drawLine(c.amber, Offset(x0, y), Offset(x1, y), 1.5.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 3.dp.toPx())))
                    }
                }
                // A9 校对：1.5dp 红色波浪线（正弦 path，振幅 1.5dp、周期 6dp）
                proofRanges.forEach { range ->
                    val s = range.first.coerceIn(0, len); val e = (range.last + 1).coerceIn(0, len)
                    if (e <= s) return@forEach
                    val ls = l.getLineForOffset(s); val le = l.getLineForOffset((e - 1).coerceAtLeast(s))
                    for (line in ls..le) {
                        val x0 = if (line == ls) l.getHorizontalPosition(s, true) else l.getLineLeft(line)
                        val x1 = if (line == le) l.getHorizontalPosition(e, true) else l.getLineRight(line)
                        val yBase = l.getLineBottom(line) - (31.sp.toPx() - 17.sp.toPx()) / 2 + 4.dp.toPx()
                        val path = androidx.compose.ui.graphics.Path().apply {
                            val amp = 1.5.dp.toPx(); val period = 6.dp.toPx()
                            moveTo(x0, yBase)
                            var x = x0
                            while (x < x1) {
                                val nx = (x + period / 2).coerceAtMost(x1)
                                val up = (((x - x0) / period).toInt() % 2 == 0)
                                quadraticBezierTo((x + nx) / 2, yBase + (if (up) -amp else amp), nx, yBase)
                                x = nx
                            }
                        }
                        drawPath(path, c.error, style = androidx.compose.ui.graphics.drawscope.Stroke(width = 1.5.dp.toPx()))
                    }
                }
            },
    )
}

@Composable
fun SuggestionCard(
    text: String, header: String, streaming: Boolean = false, onClose: () -> Unit = {},
    onAnother: () -> Unit = {}, onEdit: () -> Unit = {}, onAccept: () -> Unit = {},
    acceptLabel: String = tr(R.string.ai_components_007), editLabel: String = tr(R.string.editor_ai_013),
) {
    val c = Ling.colors
    com.lingwrite.app.ui.ai.ToneCard(
        com.lingwrite.app.ui.ai.CardTone.Amber, header, LingIcons.SparkleFilled,
        Modifier.padding(horizontal = 16.dp).fillMaxWidth(),
        trailing = {
            Box(Modifier.size(32.dp).clip(Radius.pill).clickable(onClick = onClose), contentAlignment = Alignment.Center) {
                Icon(LingIcons.Close, tr(R.string.editor_ai_048), Modifier.size(18.dp), tint = c.onSurfaceVariant)
            }
        },
        contentPadding = PaddingValues(start = 14.dp, end = 8.dp, top = 8.dp, bottom = 12.dp),
    ) {
        Row(Modifier.padding(end = 6.dp, bottom = 10.dp), verticalAlignment = Alignment.Top) {
            Text(text, style = Ling.type.bodyLarge.copy(lineHeight = 26.sp), color = c.onSurface, modifier = Modifier.weight(1f, fill = false))
            if (streaming) com.lingwrite.app.ui.ai.AiStreamCursor(Modifier.padding(top = 4.dp, start = 2.dp))
        }
        Row(Modifier.fillMaxWidth().padding(end = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
            val st = Ling.type.label.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold)
            LingButton(tr(R.string.editor_061), BtnKind.Text, height = 34.dp, textStyle = st, enabled = !streaming, onClick = onAnother)
            LingButton(editLabel, BtnKind.Outlined, height = 34.dp, textStyle = st, modifier = Modifier.widthIn(min = 64.dp), enabled = !streaming, onClick = onEdit)
            LingButton(acceptLabel, BtnKind.Filled, height = 34.dp, textStyle = st, enabled = !streaming, onClick = onAccept)
        }
    }
}

/** 工具 id（中文原文），显示时经 trText 翻译。 */
val AiTools = listOf("续写", "改写", "扩写", "润色", "去 AI 腔", "对话")

/** 工具胶囊图标（ui-spec §2：每个胶囊前 16dp 图标）。 */
fun toolIcon(label: String): androidx.compose.ui.graphics.vector.ImageVector = when {
    label.startsWith("续写") || label.startsWith("起草") -> LingIcons.Sparkle
    label.startsWith("改写") || label.startsWith("润色") -> LingIcons.EditNote
    label.startsWith("扩写") || label.startsWith("描写") -> LingIcons.Article
    label.startsWith("缩写") -> LingIcons.SwapHoriz
    label.contains("AI 腔") -> LingIcons.VisibilityOff
    label.startsWith("对话") -> LingIcons.Quote
    label.startsWith("给方向") -> LingIcons.Idea
    label.startsWith("随手问") -> LingIcons.Help
    label.startsWith("卡文") -> LingIcons.Bolt
    label.startsWith("校对") -> LingIcons.Spellcheck
    label.contains("批注") -> LingIcons.StickyNote
    else -> LingIcons.Sparkle
}

@Composable
fun ToolChips(selected: Int, tools: List<String> = AiTools, onSelect: (Int) -> Unit) {
    val c = Ling.colors
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        tools.forEachIndexed { i, t ->
            val on = i == selected
            val bg by androidx.compose.animation.animateColorAsState(if (on) c.primaryContainer else c.surfaceContainer, tween(Motion.Medium), label = "chip")
            val fg by androidx.compose.animation.animateColorAsState(if (on) c.onPrimaryContainer else c.onSurfaceVariant, tween(Motion.Medium), label = "chipFg")
            val src = remember { MutableInteractionSource() }
            Box(
                Modifier.height(32.dp).pressScale(src, 0.94f).clip(Radius.pill).background(bg)
                    .clickable(src, ripple()) { onSelect(i) }.padding(start = 10.dp, end = 13.dp),
                contentAlignment = Alignment.Center,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Icon(toolIcon(t), null, Modifier.size(16.dp), tint = fg)
                    Text(trText(t), style = Ling.type.label, color = fg)
                }
            }
        }
    }
}

@Composable
private fun BottomToolbar(today: String, a: EditorActions, mode: WriteMode, notesUnread: Int, haze: HazeState) {
    val c = Ling.colors
    // 真毛玻璃：Android 12+ 用 Haze 模糊正文；更低版本 / 截图环境回落到半透明表面
    val blur = android.os.Build.VERSION.SDK_INT >= 31
    val glass = if (blur) Modifier.hazeChild(haze, HazeStyle(
        backgroundColor = c.surface, tints = listOf(HazeTint(c.surface.copy(alpha = if (c.isDark) 0.70f else 0.74f))),
        blurRadius = 20.dp, noiseFactor = 0.03f,
        fallbackTint = HazeTint(c.surface.copy(alpha = if (c.isDark) 0.92f else 0.96f)),
    )) else Modifier.background(c.surface.copy(alpha = if (c.isDark) 0.92f else 0.96f))
    Column(Modifier.fillMaxWidth().then(glass).navigationBarsPadding()) {
        Box(Modifier.fillMaxWidth().height(1.dp).background(c.outlineVariant.copy(alpha = 0.7f)))
        Row(Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Row(Modifier.weight(1f).horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically) {
                listOf(Triple(LingIcons.Quote, tr(R.string.editor_062), a.onQuote), Triple(LingIcons.BookmarkAdd, tr(R.string.check_006), a.onForeshadow), Triple(LingIcons.Badge, tr(R.string.editor_063), a.onCharacters), Triple(LingIcons.FactCheck, tr(R.string.check_076), a.onCheck)).forEach { (ic, d, f) ->
                    Box(Modifier.size(44.dp, 48.dp).clip(Radius.pill).clickable(onClick = f), contentAlignment = Alignment.Center) { Icon(ic, d, Modifier.size(24.dp), tint = c.onSurface) }
                }
                // 本章便签（MD3）：替代原“设定”入口，带未读角标
                Box {
                    Box(Modifier.size(44.dp, 48.dp).clip(Radius.pill).clickable(onClick = a.onNotes), contentAlignment = Alignment.Center) {
                        Icon(LingIcons.StickyNote, tr(R.string.notes_drawer_005), Modifier.size(24.dp), tint = c.onSurface)
                    }
                    if (notesUnread > 0) Box(Modifier.align(Alignment.TopEnd).padding(top = 4.dp, end = 4.dp).size(8.dp).clip(Radius.pill).background(c.amber))
                }
            }
            Text(today, style = Ling.type.caption, color = c.onSurfaceVariant, modifier = Modifier.padding(end = 8.dp))
            AiMainButton(mode, Modifier.padding(end = 4.dp), onClick = a.onAi)
        }
    }
}


/** 第 ⑥ 步的节拍清单（ui-spec §2）：正文上方，可折叠；勾完全部后收起。 */
@Composable
fun BeatsChecklist(beats: List<com.lingwrite.app.data.Beat>, onToggle: (Int) -> Unit) {
    val c = Ling.colors
    var collapsed by androidx.compose.runtime.remember(beats) { androidx.compose.runtime.mutableStateOf(beats.isNotEmpty() && beats.all { it.done }) }
    if (collapsed) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 2.dp)
                .clickable { collapsed = false },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(tr(R.string.editor_064), style = Ling.type.caption, color = c.primary)
            Spacer(Modifier.weight(1f))
            Text(tr(R.string.editor_065), style = Ling.type.caption, color = c.onSurfaceVariant)
        }
        return
    }
    LingCard(Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(tr(R.string.editor_066), style = Ling.type.caption.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold), color = c.primary, modifier = Modifier.weight(1f))
                Text("${beats.count { it.done }}/${beats.size}", style = Ling.type.caption, color = c.onSurfaceVariant)
                LingButton(tr(R.string.draft_pane_002), BtnKind.Text, height = 30.dp) { collapsed = true }
            }
            beats.forEachIndexed { i, b ->
                Row(Modifier.fillMaxWidth().heightIn(min = 40.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier
                            .size(20.dp)
                            .clip(androidx.compose.foundation.shape.RoundedCornerShape(6.dp))
                            .background(if (b.done) c.primary else c.surfaceContainer)
                            .clickable(role = Role.Checkbox) { onToggle(i) },
                        contentAlignment = Alignment.Center,
                    ) {
                        if (b.done) Icon(LingIcons.Check, tr(R.string.notes_drawer_009), tint = c.onPrimary, modifier = Modifier.size(14.dp))
                    }
                    Spacer(Modifier.width(10.dp))
                    Text(
                        b.text,
                        style = Ling.type.bodyLarge.copy(lineHeight = 21.sp),
                        color = if (b.done) c.onSurfaceVariant else c.onSurface,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}


/** A9 校对条（ui-spec §7）：顶部“发现 N 处 · 只改错字和标点”，右侧逐条看 / 全部接受 / 退出。 */
@Composable
fun ProofBar(count: Int, busy: Boolean, error: String?, pick: Int, onPick: (Int) -> Unit, onAcceptAll: () -> Unit, onAcceptOne: (Int) -> Unit, onExit: () -> Unit) {
    val c = Ling.colors
    LingCard(Modifier.padding(horizontal = 16.dp, vertical = 6.dp).fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(LingIcons.Spellcheck, null, tint = c.error, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(
                    if (busy) tr(R.string.editor_067) else tr(R.string.editor_068, count),
                    style = Ling.type.label, color = c.onSurface, modifier = Modifier.weight(1f),
                )
                LingButton(tr(R.string.editor_069), BtnKind.Text, height = 32.dp, enabled = !busy && count > 0) { onPick(if (pick < 0) 0 else (pick + 1) % count) }
                LingButton(tr(R.string.editor_070), BtnKind.Tonal, height = 32.dp, enabled = !busy && count > 0) { onAcceptAll() }
                LingButton(tr(R.string.editor_071), BtnKind.Text, height = 32.dp) { onExit() }
            }
            if (error != null) Text(tr(R.string.editor_072, error), style = Ling.type.caption, color = c.error)
        }
    }
}

/** 校对类别 → 中文标签（A9）。 */
fun proofTypeLabel(type: String): String = when (type) {
    "typo" -> tr(R.string.editor_073); "de" -> tr(R.string.editor_074); "punct" -> tr(R.string.editor_075); "grammar" -> tr(R.string.editor_076); else -> type
}

/**
 * 校对气泡（A9，ui-spec §7）：宽 250dp、`surface` 底、14dp 圆角、阴影 0 4 18 / 16%。
 * 内容依次是类别 `Pill(Error)`、“原 → 改”（删除线 + 加粗）、“忽略 / 接受”。
 */
@Composable
fun ProofBubble(issue: com.lingwrite.app.data.ProofIssue, onIgnore: () -> Unit, onAccept: () -> Unit, modifier: Modifier = Modifier) {
    val c = Ling.colors
    val shadowed = if (!c.isDark) modifier.shadow(18.dp, Radius.card, ambientColor = c.cardShadow, spotColor = c.cardShadow) else modifier
    Column(shadowed.width(250.dp).clip(Radius.card).background(c.surface).padding(14.dp)) {
        Pill(proofTypeLabel(issue.type), PillKind.Error)
        Column(Modifier.padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(issue.quote, style = Ling.type.body.copy(textDecoration = TextDecoration.LineThrough), color = c.onSurfaceVariant)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("↓ ", style = Ling.type.body, color = c.onSurfaceVariant)
                Text(issue.fix, style = Ling.type.body.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.Bold), color = c.onSurface)
            }
        }
        if (issue.why.isNotBlank()) Text(issue.why, style = Ling.type.caption, color = c.onSurfaceVariant, modifier = Modifier.padding(top = 2.dp))
        Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Spacer(Modifier.weight(1f))
            LingButton(tr(R.string.check_066), BtnKind.Text, height = 32.dp, onClick = onIgnore)
            LingButton(tr(R.string.editor_077), BtnKind.Tonal, height = 32.dp, onClick = onAccept)
        }
    }
}
