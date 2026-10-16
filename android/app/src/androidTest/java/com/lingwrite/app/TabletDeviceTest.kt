// SPDX-FileCopyrightText: 2026 WikG and LingWrite contributors
// SPDX-License-Identifier: AGPL-3.0-only
package com.lingwrite.app

import androidx.activity.compose.setContent
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.navigation.compose.rememberNavController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.NavType
import androidx.navigation.navArgument
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.lingwrite.app.data.*
import com.lingwrite.app.data.ai.*
import com.lingwrite.app.data.db.*
import com.lingwrite.app.ui.routes.*
import com.lingwrite.app.ui.screens.*
import com.lingwrite.app.ui.theme.LingTheme
import com.lingwrite.app.ui.LingWriteApp
import com.lingwrite.app.ui.Routes
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.flow
import org.junit.*
import org.junit.Assert.*
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

/** Dedicated emulator only. No remote provider: stream is held at a deterministic gate. */
@OptIn(ExperimentalTestApi::class)
class TabletDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private var b = 0L; private var ch = 0L
    private fun render(content: @Composable () -> Unit) {
        InstrumentationRegistry.getInstrumentation().runOnMainSync { compose.activity.setContent(content = content) }
    }
    private fun shell(command: String) {
        val fd = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command)
        android.os.ParcelFileDescriptor.AutoCloseInputStream(fd).use { it.readBytes() }
    }
    private fun resize(size: String) {
        shell("wm size $size")
        val width = size.substringBefore('x').toInt()
        compose.waitUntil(10000) { compose.activity.resources.configuration.screenWidthDp in (width - 40)..(width + 40) }
        compose.waitForIdle()
        android.os.SystemClock.sleep(150) // Allow the resized Surface to present, after Compose is idle.
    }
    private fun shot(name: String) {
        compose.waitForIdle()
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        android.os.SystemClock.sleep(250) // Present the final Surface, not an earlier navigation frame.
        val dir = File(compose.activity.getExternalFilesDir(null), "tablet-evidence").apply { mkdirs() }
        val bitmap = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        File(dir, "$name.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }; bitmap.recycle()
    }
    @Before fun setup() {
        shell("wm density 160"); shell("settings put system font_scale 1.0")
        shell("settings put global window_animation_scale 0"); shell("settings put global transition_animation_scale 0"); shell("settings put global animator_duration_scale 0")
        runBlocking {
            AppGraph.init(ApplicationProvider.getApplicationContext(), inMemory = true); Modules.install()
            AppGraph.prefs.update { AppPrefs(onboarded = true, authorGuideInviteRevision = AUTHOR_GUIDE_REVISION, autoCheck = false, autoUpdateCheck = false, stuckHint = false, notifyAll = false, notifyAsked = true) }
            b = AppGraph.books.createBook("T_雾港灯谱"); ch = AppGraph.db.chapters().chaptersOnce(b).single().id
            sessionFor(ch).clear()
            AppGraph.books.renameChapter(ch, "T_铜灯"); AppGraph.books.saveContent(ch, "平板合成稿。作者修订。")
            AppGraph.books.nextChapter(b)
            AppGraph.db.plan().upsertChapter(ChapterPlanEntity(ch, b, event = "寻找铜灯", note = "KEEP_NOTE"))
            AppGraph.db.lore().insertCard(LoreCardEntity(bookId = b, type = "character", name = "T_罗澄", fields = encodeFields(listOf(Field("身份", "守灯人")))))
            KeyVault.override = ({ s: String -> s } to { s: String -> s })
            AppGraph.db.ai().insertProvider(ProviderEntity(presetId = "custom", name = "tablet-fake", protocol = "openai", baseUrl = "http://invalid.local", auth = "bearer", models = "mock", keyCipher = "synthetic"))
        }
        resize("1400x900")
    }
    @After fun cleanup() {
        FakeLlmRegistry.provider = null; KeyVault.override = null
        shell("wm size 320x640"); shell("wm density reset"); shell("settings put system font_scale 1.0")
    }
    @Test fun resizingKeepsDraftSelectionUndoAndActualAiCoroutine() {
        val st = EditorState(runBlocking { AppGraph.db.chapters().get(ch)!! })
        val open = mutableStateOf(true); val requests = AtomicInteger(0); val gate = CompletableDeferred<Unit>()
        FakeLlmRegistry.provider = object : LlmProvider {
            override fun stream(req: ChatRequest) = flow {
                requests.incrementAndGet(); emit(LlmEvent.Delta("T_STREAM")); gate.await(); emit(LlmEvent.Delta("_DONE")); emit(LlmEvent.Done(10, 10))
            }
        }
        val initialActivity = compose.activity
        render { LingTheme(false) {
            val nav = rememberNavController()
            EditorScreen(value = st.value, onValueChange = st::edit, marks = st.marks, canUndo = st.canUndo, canRedo = st.canRedo,
                actions = EditorActions(onUndo = st::undo, onRedo = st::redo, onAi = { open.value = true }),
                directory = { EditorDirectory(st, nav) }, tools = { EditorInspector(st, nav, it) }, bottomPanel = { EditorHooks.aiPanel(this, st, open) })
        } }
        compose.onNodeWithTag("manuscript-input").performTextReplacement("作者未保存的修订。")
        compose.onNodeWithTag("manuscript-input").performKeyInput { keyDown(Key.CtrlLeft); pressKey(Key.Z); keyUp(Key.CtrlLeft) }
        assertEquals("平板合成稿。作者修订。", st.value.text)
        compose.onNodeWithTag("manuscript-input").performKeyInput { keyDown(Key.CtrlLeft); pressKey(Key.Y); keyUp(Key.CtrlLeft) }
        assertEquals("作者未保存的修订。", st.value.text)
        compose.onNodeWithTag("toggle-writing-tools").performClick()
        compose.onNodeWithText("AI", useUnmergedTree = true).performClick()
        compose.onNodeWithText("给方向", useUnmergedTree = true).performClick()
        val session = sessionFor(ch)
        compose.waitUntil(10000) { session.text == "T_STREAM" }
        compose.runOnIdle { st.value = st.value.copy(selection = TextRange(2, 5), composition = TextRange(2, 5)) }
        shot("landscape-three-panes")
        listOf("800x1100", "720x900", "480x900", "1400x900").forEach { size ->
            resize(size)
            assertSame(initialActivity, compose.activity)
            assertEquals("作者未保存的修订。", st.value.text)
            assertEquals(TextRange(2, 5), st.value.selection)
            assertEquals(TextRange(2, 5), st.value.composition)
            assertSame(session, sessionFor(ch)); assertTrue(session.job!!.isActive); assertEquals(1, requests.get())
            shot("resize-$size")
        }
        gate.complete(Unit)
        compose.waitUntil(10000) { session.text == "T_STREAM_DONE" && !session.streaming }
        assertEquals(1, requests.get())
        compose.runOnIdle { st.value = st.value.copy(composition = null) }
        compose.onNodeWithTag("manuscript-input").performKeyInput { pressKey(Key.Tab) }
        assertEquals("作者未保存的修订。", st.value.text)
    }
    @Test fun actualEditorPlanDraftAndSaveSurvivePortraitSplitAndFontChange() {
        render { LingTheme(false) { EditorRoute(ch, rememberNavController()) } }
        compose.waitUntil(10000) { compose.onAllNodesWithTag("manuscript-input").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("manuscript-input").performTextReplacement("T_横竖屏真实路由保存")
        compose.onNodeWithTag("toggle-writing-tools").performClick()
        compose.waitUntil(10000) { compose.onAllNodes(hasSetTextAction()).fetchSemanticsNodes().size >= 5 }
        compose.onAllNodes(hasSetTextAction())[1].performTextReplacement("T_未保存章纲")
        resize("800x1100"); shell("settings put system font_scale 1.5"); compose.waitForIdle()
        compose.waitUntil(10000) { compose.activity.resources.configuration.fontScale == 1.5f }
        compose.waitForIdle()
        resize("480x900"); resize("1400x900")
        compose.onNodeWithText("T_未保存章纲", useUnmergedTree = true).assertExists()
        compose.onNodeWithText("保存").performScrollTo().assertIsDisplayed().performSemanticsAction(SemanticsActions.OnClick) { it() }
        compose.waitUntil(10000) { runBlocking { AppGraph.db.plan().chapterOnce(ch)?.event == "T_未保存章纲" } }
        assertEquals("KEEP_NOTE", runBlocking { AppGraph.db.plan().chapterOnce(ch)!!.note })
        compose.waitUntil(10000) { runBlocking { AppGraph.db.chapters().get(ch)?.content == "T_横竖屏真实路由保存" } }
        shot("large-font-real-editor-plan")
    }
    @Test fun loreAndForeshadowUseRealListDetailOnTablet() {
        var screen by mutableIntStateOf(0)
        render { LingTheme(false) { val nav = rememberNavController(); if (screen == 0) LoreWorkspace(b, nav) else ForeshadowWorkspace(b, nav, ch, 1) } }
        compose.waitUntil(10000) { compose.onAllNodesWithText("T_罗澄").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("T_罗澄").performScrollTo().performClick()
        compose.onNodeWithText("作品目录").assertDoesNotExist()
        shot("lore-list-detail")
        resize("480x900"); compose.onNodeWithText("守灯人", useUnmergedTree = true).assertExists(); resize("1400x900")
        compose.runOnIdle { screen = 1 }
        compose.onNodeWithText("登记伏笔", useUnmergedTree = true).performScrollTo().performClick()
        compose.onAllNodes(hasSetTextAction())[0].performTextReplacement("T_铜灯缺口")
        resize("800x1100"); resize("480x900"); resize("1400x900")
        compose.onNodeWithText("T_铜灯缺口", useUnmergedTree = true).assertExists()
        shot("foreshadow-list-detail")
    }
    @Test fun tabletGuideOpensActualLoreEntryAndManuscriptKeepsScroll() {
        runBlocking { AppGraph.prefs.update { it.copy(authorGuide = true, authorGuideStep = 1) } }
        render { LingTheme(false) { LingWriteApp() } }
        compose.waitUntil(10000) { compose.onAllNodesWithText("打开实际入口").fetchSemanticsNodes().isNotEmpty() }
        resize("800x1100"); shell("settings put system font_scale 1.5"); compose.waitForIdle()
        compose.waitUntil(10000) { compose.onAllNodes(hasText("打开实际入口") and isEnabled() and hasClickAction()).fetchSemanticsNodes().isNotEmpty() }
        compose.waitUntil(10000) { compose.activity.resources.configuration.fontScale == 1.5f }
        compose.waitForIdle()
        compose.onNodeWithText("打开实际入口").performScrollTo().assertIsDisplayed().performSemanticsAction(SemanticsActions.OnClick) { it() }
        compose.waitUntil(10000) { compose.onAllNodesWithText("设定库").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("继续指导").assertIsDisplayed()
        shot("portrait-guide-real-lore-entry")
        runBlocking { AppGraph.prefs.update { it.copy(authorGuide = false) }; AppGraph.books.saveContent(ch, (1..150).joinToString("\n") { "第 $it 段：雾港的灯仍在亮。作者滚动定位。" }) }
        render { LingTheme(false) { EditorRoute(ch, rememberNavController()) } }
        compose.waitUntil(10000) { compose.onAllNodesWithTag("manuscript-input").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("manuscript-input").performTouchInput { swipeUp() }
        fun offset() = compose.onNodeWithTag("manuscript-input").fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value()
        assertTrue(offset() > 0)
        listOf("480x900", "1400x900", "800x1100").forEach { resize(it); assertTrue("scroll reset after $it", offset() > 0) }
        shot("portrait-preserved-manuscript-scroll")
    }
    @Test fun outlineListDetailRetainsDraftAndOnlyUpdatesSynopsis() {
        runBlocking { AppGraph.db.plan().upsertBook(BookPlanEntity(b, idea = "KEEP_IDEA", synopsis = "T_原总纲")) }
        render { LingTheme(false) { val nav = rememberNavController(); CompositionLocalProvider(LocalNav provides nav) { OutlineTab(b, nav) } } }
        compose.waitUntil(10000) { compose.onAllNodesWithText("T_原总纲").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("T_原总纲").performScrollTo().performClick()
        compose.onNode(hasSetTextAction()).performTextReplacement("T_未保存的平板总纲")
        resize("800x1100"); resize("480x900"); resize("1400x900")
        compose.onNodeWithText("T_未保存的平板总纲", useUnmergedTree = true).assertExists()
        shot("outline-list-detail")
        runBlocking { AppGraph.db.plan().upsertBook(AppGraph.db.plan().bookOnce(b)!!.copy(idea = "NEW_IDEA")) }
        compose.onNodeWithText("保存", useUnmergedTree = true).performClick()
        compose.waitUntil(10000) { runBlocking { AppGraph.db.plan().bookOnce(b)?.synopsis == "T_未保存的平板总纲" } }
        assertEquals("NEW_IDEA", runBlocking { AppGraph.db.plan().bookOnce(b)!!.idea })
    }
    @Test fun directoryOrdersChaptersAndContinuesInsideCurrentVolume() {
        val original = runBlocking { AppGraph.db.chapters().get(ch)!! }
        runBlocking {
            val later = AppGraph.books.addVolume(b, "T_后卷")
            AppGraph.books.addChapterTo(b, later, "T_后卷首章")
        }
        render { LingTheme(false) {
            val nav = rememberNavController()
            CompositionLocalProvider(LocalNav provides nav) {
                NavHost(nav, startDestination = Routes.editor(ch)) {
                    composable(Routes.Editor, listOf(navArgument("chapterId") { type = NavType.LongType })) { e -> EditorRoute(e.arguments!!.getLong("chapterId"), nav) }
                }
            }
        } }
        compose.waitUntil(10000) { compose.onAllNodesWithText("写下一章").fetchSemanticsNodes().isNotEmpty() }
        val first = compose.onNode(hasText(ChapterLabel.full(1, "T_铜灯")) and hasAnyAncestor(hasTestTag("writing-directory"))).fetchSemanticsNode()
        val second = compose.onNode(hasText(ChapterLabel.full(2, "")) and hasAnyAncestor(hasTestTag("writing-directory"))).fetchSemanticsNode()
        assertTrue(first.boundsInRoot.top < second.boundsInRoot.top)
        compose.onNodeWithText("写下一章").performClick()
        compose.waitUntil(10000) { runBlocking { AppGraph.db.chapters().chaptersOnce(b).size == 4 } }
        val all = runBlocking { AppGraph.db.chapters().chaptersOnce(b) }
        val created = all.maxBy { it.id }
        assertEquals(original.volumeId, created.volumeId); assertEquals(3, created.number)
        assertEquals(4, all.single { it.title == "T_后卷首章" }.number)
        runBlocking { AppGraph.books.moveChapter(created.id, -1) }
        compose.waitUntil(10000) {
            compose.onAllNodes(hasText(ChapterLabel.full(2, "")) and hasAnyAncestor(hasTestTag("writing-manuscript"))).fetchSemanticsNodes().isNotEmpty()
        }
        compose.waitForIdle(); shot("real-directory-new-chapter-current-volume")
    }
}
