// SPDX-FileCopyrightText: 2026 WikG and LingWrite contributors
// SPDX-License-Identifier: AGPL-3.0-only
package com.lingwrite.app.ui.routes

import com.lingwrite.app.i18n.tr
import com.lingwrite.app.i18n.trZh

import com.lingwrite.app.R

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavHostController
import com.lingwrite.app.data.ChapterLabel
import com.lingwrite.app.AppGraph
import com.lingwrite.app.data.*
import com.lingwrite.app.data.db.ChapterEntity
import com.lingwrite.app.data.db.Hit
import com.lingwrite.app.data.db.ChapterNoteEntity
import com.lingwrite.app.data.db.ChapterVersionEntity
import com.lingwrite.app.ui.Routes
import com.lingwrite.app.ui.components.*
import com.lingwrite.app.ui.screens.EditorActions
import com.lingwrite.app.ui.screens.EditorScreen
import com.lingwrite.app.ui.theme.Ling
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch

/** 编辑器状态，供 AI 工具条等模块读写正文。 */
class EditorState(chapter: ChapterEntity) {
    var chapter by mutableStateOf(chapter)
    var value by mutableStateOf(TextFieldValue(chapter.content, TextRange(chapter.content.length)))
    var marks by mutableStateOf(parseMarks(chapter.aiMarks))
    private val undo = ArrayDeque<Pair<TextFieldValue, List<AiMark>>>()
    private val redo = ArrayDeque<Pair<TextFieldValue, List<AiMark>>>()
    private var lastPush = 0L
    val canUndo get() = undo.isNotEmpty()
    val canRedo get() = redo.isNotEmpty()

    fun edit(v: TextFieldValue) {
        if (v.text != value.text) {
            redo.clear()
            val now = System.currentTimeMillis()
            if (now - lastPush > 1500 || undo.isEmpty()) { undo.addLast(value to marks); if (undo.size > 100) undo.removeFirst(); lastPush = now }
            marks = MarkTracker.adjust(value.text, v.text, marks)
        }
        value = v
    }

    /** 程序化替换 [start,end) 为 [text]；[kind] 非空时把新文本标为 AI 段落。 */
    fun replace(start: Int, end: Int, text: String, kind: String? = null) {
        require(start in 0..value.text.length && end in start..value.text.length)
        redo.clear()
        undo.addLast(value to marks); lastPush = System.currentTimeMillis()
        val old = value.text
        val nt = old.substring(0, start) + text + old.substring(end)
        var m = MarkTracker.adjust(old, nt, marks).filterNot { it.s >= start && it.e <= start + text.length && kind != null }
        if (kind != null && text.isNotEmpty()) m = (m + AiMark(start, start + text.length, kind)).sortedBy { it.s }
        marks = m
        value = TextFieldValue(nt, TextRange(start + text.length))
    }

    fun undo() { undo.removeLastOrNull()?.let { (v, m) -> redo.addLast(value to marks); value = v; marks = m; lastPush = 0 } }
    fun redo() { redo.removeLastOrNull()?.let { (v, m) -> undo.addLast(value to marks); value = v; marks = m; lastPush = 0 } }
    fun reset(content: String) { value = TextFieldValue(content, TextRange(content.length)); marks = emptyList(); undo.clear(); redo.clear() }
}

/** 编辑器扩展点（后续模块注册）。 */
object EditorHooks {
    var names: (Long) -> Flow<List<String>> = { flowOf(emptyList()) }
    /** 底部 AI 面板 */
    var aiPanel: @Composable ColumnScope.(EditorState, aiOpen: MutableState<Boolean>) -> Unit = { _, _ -> }
    /** 更多菜单里的附加项 */
    var moreItems: @Composable ColumnScope.(EditorState, close: () -> Unit, nav: NavHostController) -> Unit = { _, _, _ -> }
    var onForeshadow: (EditorState, NavHostController) -> Unit = { _, _ -> }
    var onCheck: (EditorState, NavHostController) -> Unit = { _, _ -> }
    var onQuote: (EditorState, NavHostController) -> Unit = { _, _ -> }
    /** 章节被标记完成时（自动一致性检查等） */
    var onChapterDone: (Long) -> Unit = {}
    /** 「换个模型」：编辑器路由注册，跳到模型设置 */
    var onOpenModels: (() -> Unit)? = null
    var overlays: @Composable (EditorState, NavHostController) -> Unit = { _, _ -> }
}

@Composable
fun EditorRoute(chapterId: Long, nav: NavHostController) {
    val repo = AppGraph.books
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current
    val ctx = LocalContext.current
    var state by remember { mutableStateOf<EditorState?>(null) }
    LaunchedEffect(chapterId) {
        val loaded = repo.editorChapter(chapterId)
        if (loaded == null) nav.popBackStack() else state = EditorState(loaded)
    }
    val st = state ?: return
    // 检查结果「去原文」的一次性定位；不改变正文，只移动光标。
    LaunchedEffect(chapterId, st) {
        EditorJump.consume(chapterId)?.let { at ->
            val len = st.value.text.length
            st.value = st.value.copy(selection = TextRange(at.coerceIn(0, len)))
        }
    }
    val ch = st.chapter
    val live by remember(chapterId) { repo.chapter(chapterId) }.collectAsState(ch)
    // Metadata may change while this editor remains open; keep anchors on the current chapter number.
    LaunchedEffect(live) {
        val current = live ?: return@LaunchedEffect
        if (current.deletedAt != 0L) nav.popBackStack() else st.chapter = current
    }
    val today by remember { repo.todayWords() }.collectAsState(0)
    val names by remember(ch.bookId) { EditorHooks.names(ch.bookId) }.collectAsState(emptyList())
    var saving by remember { mutableStateOf(false) }
    var saveError by remember(chapterId) { mutableStateOf(repo.saveFailure(chapterId)) }
    var more by remember { mutableStateOf(false) }
    var history by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf(false) }
    var modeSheet by remember { mutableStateOf(false) }
    var notesOpen by remember { mutableStateOf(false) }
    var quickRef by remember { mutableStateOf(false) }
    var ttsOpen by remember { mutableStateOf(false) }
    val aiOpen = remember { mutableStateOf(false) }
    val prefs by remember { AppGraph.prefs.flow }.collectAsState(AppGraph.prefs.snapshot)
    val s = remember(chapterId) { sessionFor(chapterId) }
    // 本书模式（MD2）：作品设置优先，否则跟随全局
    var mode by remember(ch.bookId) { mutableStateOf(WriteMode.Assist) }
    LaunchedEffect(ch.bookId) {
        mode = com.lingwrite.app.data.modeFor(ch.bookId)
        s.mode = mode
    }
    LaunchedEffect(mode) { s.mode = mode }
    LaunchedEffect(nav) { EditorHooks.onOpenModels = { nav.navigate(Routes.Models) } }
    val notesUnread by remember(chapterId) { AppGraph.db.notes().unreadCount(chapterId) }.collectAsState(0)
    var focus by rememberSaveable(chapterId) { mutableStateOf(false) }
    var exitFocusConfirm by remember { mutableStateOf(false) }
    var focusStartWords by rememberSaveable(chapterId) { mutableIntStateOf(0) }
    var focusStartAt by rememberSaveable(chapterId) { mutableLongStateOf(0L) }
    var focusTick by remember { mutableLongStateOf(0L) }
    // C5 节拍清单（链路第 ⑥ 步）：正文上方的可勾选清单
    var beats by remember(chapterId) { mutableStateOf(emptyList<com.lingwrite.app.data.Beat>()) }
    LaunchedEffect(chapterId) {
        val p = AppGraph.db.plan().chapterOnce(chapterId)
        if (p != null) beats = parseBeats(p.beats)
    }
    // A9 校对（ai-features §9）：结果只在内存里，接受后不加 AI 段落标记
    val proof = remember { mutableStateListOf<com.lingwrite.app.data.ProofIssue>() }
    var proofBusy by remember { mutableStateOf(false) }
    var proofErr by remember { mutableStateOf<String?>(null) }
    var fixErr by remember { mutableStateOf<String?>(null) }
    var proofPick by remember { mutableStateOf(-1) }
    var stuck by remember { mutableStateOf(false) }
    val proofRanges = proof.mapNotNull { issue ->
        val at = com.lingwrite.app.data.Proof.locate(st.value.text, issue.quote)
        if (at >= 0) at until (at + issue.quote.length) else null
    }
    fun acceptProof(i: Int) {
        val issue = proof.getOrNull(i) ?: return
        // 引文必须唯一命中，否则宁可让作者手动改，也不能改错同名句。
        val at = com.lingwrite.app.data.Proof.locateUnique(st.value.text, issue.quote)
        if (at < 0) { proofErr = tr(R.string.editor_78); return }
        val kind = if (com.lingwrite.app.data.Proof.needsAiMark(issue)) "edited" else null
        st.replace(at, at + issue.quote.length, issue.fix, kind)
        proof.removeAt(i)
        proofPick = -1
    }
    val onBeatToggle: (Int) -> Unit = { i ->
        scope.launch {
            val plan = AppGraph.db.plan().chapterOnce(chapterId) ?: com.lingwrite.app.data.db.ChapterPlanEntity(chapterId, ch.bookId)
            beats = beats.mapIndexed { k, b -> if (k == i) b.copy(done = !b.done) else b }
            AppGraph.db.plan().upsertChapter(plan.copy(beats = encodeBeats(beats)))
        }
    }
    // A9 校对：工具条胶囊「校对本章」和 ⋮ 菜单共用同一条入口
    fun runProof() {
        proofBusy = true; proofErr = null
        scope.launch {
            runCatching { com.lingwrite.app.data.Proof.run(ch.bookId, chapterId, st.value.text) }
                .onSuccess { proof.clear(); proof.addAll(it) }
                .onFailure { proofErr = it.message }
            proofBusy = false
        }
    }
    // A5 卡文急救：工具条胶囊（辅助模式常驻）
    LaunchedEffect(s.wantProof) { if (s.wantProof) { s.wantProof = false; runProof() } }
    LaunchedEffect(s.wantStuck) { if (s.wantStuck) { s.wantStuck = false; stuck = true } }
    // A5：光标停留 3 分钟没有新内容 → 轻提示一次（prefs.stuckHint，每章一次）
    var stuckHinted by remember(chapterId) { mutableStateOf(false) }
    var stuckHintClosed by remember(chapterId) { mutableStateOf(false) }
    val stuckHintOn = prefs?.stuckHint ?: true
    LaunchedEffect(st.value.text, stuckHintOn) {
        if (stuckHintOn && !stuckHinted && st.value.text.isNotBlank()) {
            delay(3 * 60_000)
            stuckHinted = true
        }
    }
    // 切到辅助模式时，未采纳的候选存进便签（modes.md §4）
    fun switchMode(m: WriteMode, applyAll: Boolean) {
        scope.launch {
            if (m == WriteMode.Assist && (s.multi && s.cands.isNotEmpty())) {
                s.cands.forEach { cand -> if (cand.display.isNotBlank()) AppGraph.db.notes().insert(ChapterNoteEntity(chapterId = chapterId, kind = "direction", text = cand.display)) }
                s.clear()
            }
            mode = m
            AppGraph.db.books().get(ch.bookId)?.let { b ->
                AppGraph.books.updateBook(b.copy(writeMode = if (applyAll) null else m.id))
            }
            if (applyAll) AppGraph.prefs.update { it.copy(writeMode = m.id) }
        }
    }

    // 自动保存：停止输入 1.2 秒后写库；离开页面时兜底保存
    LaunchedEffect(st.value.text, st.marks) {
        saving = true
        val revision = repo.captureRevision()
        val text = st.value.text
        val marks = encodeMarks(st.marks)
        delay(1200)
        try { repo.saveContent(chapterId, text, marks, revision = revision); saveError = null; saving = false }
        catch (e: kotlinx.coroutines.CancellationException) { throw e }
        catch (e: Exception) { saveError = tr(R.string.editor_001, e.message); saving = false }
    }
    val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
    DisposableEffect(chapterId, lifecycle) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_STOP) repo.queueSave(chapterId, st.value.text, encodeMarks(st.marks))
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    DisposableEffect(chapterId) {
        onDispose {
            repo.queueSave(chapterId, st.value.text, encodeMarks(st.marks))
            // 离开编辑器时清空本章 AI 会话，避免流式任务残留、重进卡「生成中」和内存累积。
            com.lingwrite.app.ui.routes.clearAiSession(chapterId)
        }
    }
    val words = Words.count(st.value.text)
    val p = prefs
    val focusTarget = p?.focusTarget?.coerceAtLeast(1) ?: 2000
    val focusWords = (words - focusStartWords).coerceAtLeast(0)
    val focusPercent = (focusWords.toFloat() / focusTarget).coerceIn(0f, 1f)
    val focusElapsed = ((focusTick - focusStartAt).coerceAtLeast(0) / 1000).let { sec -> "%02d:%02d".format(sec / 60, sec % 60) }
    LaunchedEffect(focus) {
        if (focus) {
            focusStartWords = Words.count(st.value.text)
            focusStartAt = System.currentTimeMillis()
            focusTick = focusStartAt
            while (true) { delay(1000); focusTick = System.currentTimeMillis() }
        }
    }
    val focusView = androidx.compose.ui.platform.LocalView.current
    DisposableEffect(focus, focusView) {
        focusView.keepScreenOn = focus
        onDispose { focusView.keepScreenOn = false }
    }
    val imeVisible = WindowInsets.ime.getBottom(androidx.compose.ui.platform.LocalDensity.current) > 0
    EditorScreen(
        noticeBar = {
    // 首次进入编辑器的一次性提示（modes.md §3.5）
    if (!imeVisible && p != null && !p.modeTipShown && st.value.text.isBlank()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp).clip(RoundedCornerShape(12.dp)).background(Ling.colors.surfaceContainer).padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(if (mode == WriteMode.Assist) ModeCopy.ASSIST_TIP else ModeCopy.GENERATE_TIP, style = Ling.type.caption, color = Ling.colors.onSurfaceVariant, modifier = Modifier.weight(1f))
            LingButton(tr(R.string.editor_002), BtnKind.Text, height = 32.dp) { scope.launch { AppGraph.prefs.update { it.copy(modeTipShown = true) } } }
        }
    }

    // A5：3 分钟没有新内容时的轻提示（只弹一次，可在设置里关闭）
    if (!imeVisible && stuckHintOn && stuckHinted && !stuckHintClosed && !stuck) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp).clip(RoundedCornerShape(12.dp)).background(Ling.colors.surfaceContainer).padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(tr(R.string.editor_003), style = Ling.type.caption, color = Ling.colors.onSurfaceVariant, modifier = Modifier.weight(1f))
            LingButton(tr(R.string.editor_004), BtnKind.Tonal, height = 32.dp) { stuckHintClosed = true; stuck = true }
            LingButton(tr(R.string.editor_005), BtnKind.Text, height = 32.dp) { stuckHintClosed = true }
        }
    }

    // A6：一致性问题「去原文应用」——作者在这里确认后才写正文，并标 edited
    val pf = com.lingwrite.app.data.IssueFixes.PendingFix
    if (pf.active && pf.chapterId == chapterId) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp).clip(RoundedCornerShape(12.dp)).background(Ling.colors.amber.copy(alpha = 0.14f)).padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(tr(R.string.editor_006), style = Ling.type.caption.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold), color = Ling.colors.onSurface)
                Text(tr(R.string.editor_007, pf.before.take(50)), style = Ling.type.micro.copy(lineHeight = 16.sp), color = Ling.colors.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(tr(R.string.editor_008, pf.after.take(50)), style = Ling.type.micro.copy(lineHeight = 16.sp), color = Ling.colors.primary, maxLines = 2, overflow = TextOverflow.Ellipsis)
                fixErr?.let { Text(it, style = Ling.type.micro.copy(lineHeight = 16.sp), color = Ling.colors.error, maxLines = 2, overflow = TextOverflow.Ellipsis) }
            }
            LingButton(tr(R.string.about_001), BtnKind.Tonal, height = 32.dp) {
                scope.launch {
                    // 原文必须唯一命中；找不到或出现多次时保留确认条并提示，绝不猜位置。
                    val at = com.lingwrite.app.data.Proof.locateUnique(st.value.text, pf.before)
                    if (at < 0) {
                        fixErr = tr(R.string.editor_78)
                    } else {
                        repo.saveContent(chapterId, st.value.text, encodeMarks(st.marks))
                        repo.snapshot(chapterId, "ai", tr(R.string.editor_009))
                        st.replace(at, at + pf.before.length, pf.after, "edited")
                        pf.clear(); fixErr = null
                    }
                }
            }
            LingButton(tr(R.string.check_066), BtnKind.Text, height = 32.dp) { pf.clear() }
        }
    }

        },
        title = ChapterLabel.full(ch.number, live?.title ?: ch.title),
        subtitle = saveError ?: if (saving) tr(R.string.editor_010, Words.grouped(words)) else tr(R.string.editor_011, Words.grouped(words)),
        value = st.value, onValueChange = st::edit, marks = st.marks, names = names,
        todayDelta = tr(R.string.editor_012, Words.grouped(today)), canUndo = st.canUndo, canRedo = st.canRedo,
        mode = mode, notesUnread = notesUnread,
        beats = beats, onBeatToggle = onBeatToggle,
        proofRanges = proofRanges,
        proofBar = {
            if (proofBusy || proof.isNotEmpty() || proofErr != null) com.lingwrite.app.ui.screens.ProofBar(
                count = proof.size,
                busy = proofBusy,
                error = proofErr,
                pick = proofPick,
                onPick = { proofPick = it },
                onAcceptAll = {
                    while (proof.isNotEmpty()) acceptProof(0)
                },
                onAcceptOne = { acceptProof(it) },
                onExit = { proof.clear(); proofPick = -1; proofErr = null },
            )
        },
        proofBubble = {
            proof.getOrNull(proofPick)?.let { issue ->
                com.lingwrite.app.ui.screens.ProofBubble(
                    issue = issue,
                    onIgnore = { proofPick = (proofPick + 1) % proof.size.coerceAtLeast(1) },
                    onAccept = { acceptProof(proofPick) },
                )
            }
        },
        emptyHint = if (mode == WriteMode.Assist) ModeCopy.ASSIST_EMPTY else ModeCopy.GENERATE_EMPTY,
        badge = { com.lingwrite.app.ui.ai.ModeBadge(mode) { modeSheet = true } },
        actions = EditorActions(
            onBack = { nav.popBackStack() }, onUndo = st::undo, onRedo = st::redo, onMore = { more = true },
            onLore = { nav.navigate(Routes.lore(ch.bookId, 0)) },
            onCharacters = { nav.navigate(Routes.lore(ch.bookId, -1)) },
            onQuote = { EditorHooks.onQuote(st, nav) }, onForeshadow = { EditorHooks.onForeshadow(st, nav) },
            onCheck = { EditorHooks.onCheck(st, nav) }, onAi = { aiOpen.value = !aiOpen.value },
            onNotes = { notesOpen = true },
            onSave = { repo.queueSave(chapterId, st.value.text, encodeMarks(st.marks)) },
            onFocus = { focus = true },
            onQuickRef = { quickRef = true },
        ),
        focusMode = focus,
        onExitFocus = { if (focusWords >= focusTarget) focus = false else exitFocusConfirm = true },
        focusWords = focusWords,
        focusTarget = focusTarget,
        focusElapsed = focusElapsed,
        focusPercent = focusPercent,
        typewriter = p?.focusTypewriter ?: true,
        directory = { EditorDirectory(st, nav) },
        tools = { tab -> EditorInspector(st, nav, tab) },
        bottomPanel = { EditorHooks.aiPanel(this, st, aiOpen) },
    )
    if (exitFocusConfirm) LingConfirm(
        tr(R.string.focus_005), "", confirm = tr(R.string.focus_007), onDismiss = { exitFocusConfirm = false },
        onConfirm = { exitFocusConfirm = false; focus = false },
        extra = { Text(tr(R.string.focus_006), style = Ling.type.caption, color = Ling.colors.onSurfaceVariant) },
    )
    if (ttsOpen) TtsSheet(st.value.text, st.value.selection.start, p?.ttsSpeed ?: 1f, onDismiss = { ttsOpen = false })
    if (quickRef) EditorQuickRef(
        bookId = ch.bookId, chapterId = chapterId, chapterNumber = ch.number, text = st.value.text,
        onDismiss = { quickRef = false },
        onOpenHit = { hit ->
            quickRef = false
            if (hit.chapterId == chapterId) {
                st.value = st.value.copy(selection = TextRange(hit.start.coerceIn(0, st.value.text.length)))
            } else {
                scope.launch {
                    repo.saveContent(chapterId, st.value.text, encodeMarks(st.marks))
                    EditorJump.put(hit.chapterId, hit.start)
                    nav.navigate(Routes.editor(hit.chapterId))
                }
            }
        },
    )
    EditorHooks.overlays(st, nav)
    NotesDrawer(chapterId, notesOpen, onClose = { notesOpen = false })
    if (stuck) StuckSheet(
        chapterId = chapterId, bookId = ch.bookId, isGenerate = mode == com.lingwrite.app.data.WriteMode.Generate,
        onDismiss = { stuck = false },
        onDraftAWay = { w ->
            val aiSession = com.lingwrite.app.ui.routes.sessionFor(chapterId)
            aiSession.ask = false
            // 生成模式：按方向起草一段，走草稿区
            scope.launch {
                aiSession.draftOpen = true
                aiSession.draftStreaming = 0
                val prompt = trZh(R.string.editor_013, w.title, w.how)
                AppGraph.ai.stream("draft", com.lingwrite.app.data.ai.Slot.Writing,
                    com.lingwrite.app.data.ai.Prompts.BASE, listOf(com.lingwrite.app.data.ai.ChatMessage("user", prompt)), 1200, ch.bookId, chapterId, scope = tr(R.string.editor_014))
                    .collect { e ->
                        val t = (e as? com.lingwrite.app.data.ai.AiStream.Partial)?.text
                            ?: (e as? com.lingwrite.app.data.ai.AiStream.Finished)?.text
                        if (t != null) {
                            if (aiSession.draftSegs.isEmpty()) aiSession.draftSegs += t else aiSession.draftSegs[0] = t
                        }
                    }
                aiSession.draftStreaming = -1
            }
        },
    )

    if (modeSheet) {
        var applyAll by remember { mutableStateOf(false) }
        LingSheet({ modeSheet = false }, tr(R.string.editor_015)) {
            Text(tr(R.string.editor_016), style = Ling.type.caption, color = Ling.colors.onSurfaceVariant, modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp))
            SheetAction(LingIcons.Draw, tr(R.string.editor_017, ModeCopy.ASSIST_ONE_LINER), sub = ModeCopy.ASSIST_RESULT) { switchMode(WriteMode.Assist, applyAll); modeSheet = false }
            SheetAction(LingIcons.SparkleFilled, tr(R.string.editor_018, ModeCopy.GENERATE_ONE_LINER), sub = ModeCopy.GENERATE_RESULT) { switchMode(WriteMode.Generate, applyAll); modeSheet = false }
            Row(Modifier.padding(horizontal = 24.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                androidx.compose.material3.Checkbox(applyAll, { applyAll = it })
                Text(tr(R.string.editor_019), style = Ling.type.body, color = Ling.colors.onSurface)
            }
        }
    }

    fun copyChapter(withTitle: Boolean, withBook: Boolean) {
        scope.launch {
            val book = if (withBook) AppGraph.db.books().get(ch.bookId)?.title.orEmpty() else ""
            val text = buildString {
                if (book.isNotBlank()) append(book).append("\n\n")
                if (withTitle) append(ChapterLabel.full(ch.number, live?.title ?: ch.title)).append("\n\n")
                append(st.value.text)
            }
            clipboard.setText(AnnotatedString(text))
            android.widget.Toast.makeText(ctx, tr(R.string.editor_87), android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    if (more) LingSheet({ more = false }) {
        SheetAction(LingIcons.Copy, tr(R.string.editor_81), sub = tr(R.string.editor_82)) { copyChapter(false, false); more = false }
        SheetAction(LingIcons.Copy, tr(R.string.editor_83), sub = tr(R.string.editor_84)) { copyChapter(true, false); more = false }
        SheetAction(LingIcons.Copy, tr(R.string.editor_85), sub = tr(R.string.editor_86)) { copyChapter(true, true); more = false }
        SheetAction(LingIcons.Help, tr(R.string.editor_020), sub = tr(R.string.editor_021)) {
            scope.launch {
                AppGraph.prefs.update { it.copy(authorGuide = true, authorGuideStep = 5, authorGuideInviteRevision = AUTHOR_GUIDE_REVISION) }
                AuthorGuideUi.openEpoch++
            }
            more = false
        }
        SheetAction(LingIcons.History, tr(R.string.editor_022), sub = tr(R.string.editor_023, BookRepository.KEEP_AUTO)) { history = true; more = false }
        SheetAction(LingIcons.BookmarkAdd, tr(R.string.editor_024)) { scope.launch { repo.saveContent(chapterId, st.value.text, encodeMarks(st.marks)); repo.snapshot(chapterId) }; more = false }
        SheetAction(LingIcons.EditOutline, tr(R.string.editor_025)) { renaming = true; more = false }
        SheetAction(LingIcons.Book, tr(R.string.ask_panel_005), sub = tr(R.string.editor_026)) { nav.navigate(Routes.lore(ch.bookId, 0)); more = false }
        val done = live?.state == "done"
        SheetAction(LingIcons.Check, if (done) tr(R.string.editor_027) else tr(R.string.editor_028), sub = if (done) null else tr(R.string.editor_029)) {
            scope.launch {
                repo.saveContent(chapterId, st.value.text, encodeMarks(st.marks))
                repo.setChapterState(chapterId, if (done) "draft" else "done")
                if (!done) EditorHooks.onChapterDone(chapterId)
            }
            more = false
        }
        SheetAction(LingIcons.Add, tr(R.string.editor_030), sub = tr(R.string.editor_031)) { scope.launch { val n = repo.nextChapter(ch.bookId, afterChapterId = ch.id); nav.navigate(Routes.editor(n)) { popUpTo(Routes.Editor) { inclusive = true } } }; more = false }
        SheetAction(LingIcons.FactCheck, tr(R.string.editor_ai_046), sub = tr(R.string.editor_032)) {
            more = false; runProof()
        }
        SheetAction(LingIcons.FactCheck, tr(R.string.publish_001), sub = tr(R.string.publish_015)) {
            more = false; nav.navigate(Routes.publishCheck(ch.bookId, chapterId))
        }
        SheetAction(LingIcons.Article, tr(R.string.tts_001), sub = tr(R.string.tts_008)) {
            more = false; ttsOpen = true
        }
        SheetAction(LingIcons.Help, tr(R.string.editor_ai_045), sub = tr(R.string.editor_033)) { more = false; stuck = true }
        // A16 读者试读：模拟反馈，仅供参考（ai-features §16）
        SheetAction(LingIcons.FactCheck, tr(R.string.editor_034), sub = tr(R.string.editor_035)) { more = false; nav.navigate(com.lingwrite.app.ui.Routes.reader(ch.bookId, ch.number)) }
        EditorHooks.moreItems(this, st, { more = false }, nav)
    }
    if (renaming) {
        var t by remember { mutableStateOf(live?.title ?: ch.title) }
        LingConfirm(tr(R.string.editor_036), "", onDismiss = { renaming = false }, onConfirm = { scope.launch { repo.renameChapter(chapterId, t) }; renaming = false }, extra = {
            LingTextField(t, { t = it }, Modifier.padding(top = 4.dp))
        })
    }
    if (history) HistorySheet(chapterId, st.value.text, onDismiss = { history = false }) { v ->
        scope.launch {
            repo.saveContent(chapterId, st.value.text, encodeMarks(st.marks))
            repo.rollback(chapterId, v.id)
            st.reset(v.content)
            history = false
        }
    }
}

private fun kindLabel(k: String) = when (k) { "manual" -> tr(R.string.editor_037); "rollback" -> tr(R.string.editor_038); "ai" -> tr(R.string.editor_ai_032); else -> tr(R.string.editor_039) }

@Composable
fun HistorySheet(chapterId: Long, current: String, onDismiss: () -> Unit, onRollback: (ChapterVersionEntity) -> Unit) {
    val c = Ling.colors
    val versions by remember(chapterId) { AppGraph.books.versions(chapterId) }.collectAsState(emptyList())
    var open by remember { mutableStateOf<ChapterVersionEntity?>(null) }
    LingSheet(onDismiss, tr(R.string.editor_022), layout = SheetLayout.Viewport) {
        if (versions.isEmpty()) Text(tr(R.string.editor_040), style = Ling.type.body, color = c.onSurfaceVariant, modifier = Modifier.padding(24.dp))
        LazyColumn(Modifier.heightIn(max = 520.dp)) {
            items(versions, key = { it.id }) { v ->
                val delta = Words.count(current) - v.wordCount
                Row(Modifier.fillMaxWidth().rowClick { open = v }.padding(horizontal = 24.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(TimeFmt.clock(v.createdAt), style = Ling.type.bodyLarge, color = c.onSurface)
                            Spacer(Modifier.width(8.dp))
                            Pill(kindLabel(v.kind), if (v.kind == "manual") PillKind.Primary else PillKind.Neutral)
                        }
                        Text(v.content.trim().take(40).replace("\n", " "), style = Ling.type.caption, color = c.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        Text(tr(R.string.editor_041, Words.grouped(v.wordCount)), style = Ling.type.caption, color = c.onSurfaceVariant)
                        if (delta != 0) Text(if (delta > 0) tr(R.string.editor_042, delta) else tr(R.string.editor_043, -delta), style = Ling.type.micro, color = if (delta > 0) c.good else c.tertiary)
                    }
                }
            }
        }
    }
    open?.let { v -> VersionCompare(v, current, onDismiss = { open = null }) { onRollback(v); open = null } }
}

@Composable
private fun VersionCompare(v: ChapterVersionEntity, current: String, onDismiss: () -> Unit, onRollback: () -> Unit) {
    val c = Ling.colors
    val diff = remember(v.id, current) { ParaDiff.diff(v.content, current) }
    LingSheet(onDismiss, "${TimeFmt.clock(v.createdAt)} · ${kindLabel(v.kind)}", layout = SheetLayout.Viewport) {
        Row(Modifier.padding(horizontal = 24.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Legend(c.errorContainer, tr(R.string.editor_044)); Legend(c.goodContainer, tr(R.string.editor_045))
        }
        Column(Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            diff.forEach { l ->
                val bg = when (l.op) { ParaDiff.Op.Del -> c.errorContainer; ParaDiff.Op.Add -> c.goodContainer; else -> c.surface }
                Text(l.text, style = Ling.type.body.copy(textDecoration = if (l.op == ParaDiff.Op.Del) TextDecoration.LineThrough else null),
                    color = if (l.op == ParaDiff.Op.Same) c.onSurfaceVariant else c.onSurface,
                    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(bg).padding(horizontal = 10.dp, vertical = 6.dp))
            }
        }
        LingButton(tr(R.string.editor_046), modifier = Modifier.padding(horizontal = 20.dp).fillMaxWidth(), height = 50.dp, onClick = onRollback)
        Text(tr(R.string.editor_047), style = Ling.type.caption, color = c.onSurfaceVariant, modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp))
    }
}

@Composable
private fun Legend(color: androidx.compose.ui.graphics.Color, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(10.dp).clip(RoundedCornerShape(3.dp)).background(color))
        Spacer(Modifier.width(6.dp)); Text(text, style = Ling.type.caption, color = Ling.colors.onSurfaceVariant)
    }
}
