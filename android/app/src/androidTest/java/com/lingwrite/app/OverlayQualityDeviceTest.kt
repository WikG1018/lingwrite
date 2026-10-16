// SPDX-FileCopyrightText: 2026 WikG and LingWrite contributors
// SPDX-License-Identifier: AGPL-3.0-only
package com.lingwrite.app

import android.accessibilityservice.AccessibilityServiceInfo
import android.graphics.Rect
import android.view.accessibility.AccessibilityWindowInfo
import androidx.activity.compose.setContent
import androidx.compose.runtime.*
import androidx.compose.foundation.layout.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.navigation.compose.rememberNavController
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.lingwrite.app.data.*
import com.lingwrite.app.data.db.*
import com.lingwrite.app.ui.LingWriteApp
import com.lingwrite.app.ui.Routes
import com.lingwrite.app.ui.components.*
import com.lingwrite.app.ui.routes.*
import com.lingwrite.app.ui.theme.LingTheme
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import org.junit.*
import org.junit.Assert.*
import java.io.File

class OverlayQualityDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private var book = 0L; private var chapter = 0L
    private fun shell(cmd: String) {
        val fd=InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(cmd)
        android.os.ParcelFileDescriptor.AutoCloseInputStream(fd).use { it.readBytes() }
    }
    private fun render(content: @Composable () -> Unit) {
        InstrumentationRegistry.getInstrumentation().runOnMainSync { compose.activity.setContent { LingTheme(false) { content() } } }
    }
    private fun photo(name: String) {
        compose.waitForIdle()
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        android.os.SystemClock.sleep(250)
        val dir=File(compose.activity.getExternalFilesDir(null),"overlay-quality").apply { mkdirs() }
        val bitmap=InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        File(dir,"$name.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it) };bitmap.recycle()
    }
    private fun back() { shell("input keyevent 4");compose.waitForIdle() }
    @Before fun setup() {
        shell("wm size 320x640");shell("wm density 160");shell("settings put system font_scale 1.5")
        shell("settings put global animator_duration_scale 1");shell("settings put global window_animation_scale 1");shell("settings put global transition_animation_scale 1")
        runBlocking {
            AppGraph.init(ApplicationProvider.getApplicationContext(),inMemory=true);Modules.install()
            AppGraph.prefs.update { AppPrefs(onboarded=true,authorGuideInviteRevision=AUTHOR_GUIDE_REVISION,autoCheck=false,autoUpdateCheck=false,stuckHint=false,notifyAsked=true,notifyAll=false) }
            book=AppGraph.books.createBook("Q_作者返修");chapter=AppGraph.db.chapters().chaptersOnce(book).single().id
            AppGraph.books.renameChapter(chapter,"Q_开场")
            AppGraph.db.plan().upsertChapter(ChapterPlanEntity(chapter,book,event="旧核心事件",note="KEEP_NOTE"))
        }
    }
    @Test fun newBookCanBeCreatedWithKeyboardOpenAtLargeFont() {
        var created=0L
        render { ShelfRoute(onOpenBook={created=it},onOpenChapter={},onSearch={},onWizard={}) }
        compose.onNodeWithText("新建作品").performClick()
        compose.waitUntil(10000) { compose.onAllNodes(hasSetTextAction()).fetchSemanticsNodes().isNotEmpty() }
        compose.onNode(hasSetTextAction()).performTextInput("Q_键盘建书")
        compose.onNodeWithText("创建").performScrollTo().assertIsDisplayed().performClick()
        compose.waitUntil(10000) { created>0 }
        assertEquals("Q_键盘建书",runBlocking { AppGraph.db.books().get(created)!!.title })
        photo("new-book-after-keyboard-create")
    }
    @Test fun emptyShelfSampleEntryIsVisibleAndCreatesCompleteSample() {
        runBlocking { AppGraph.books.deleteBook(book) }
        render { ShelfRoute(onOpenBook={},onOpenChapter={},onSearch={},onWizard={}) }
        compose.onAllNodesWithText("新建作品").assertCountEquals(1)
        compose.onNodeWithText("先看看示例作品").performScrollTo().assertIsDisplayed()
        photo("empty-shelf-unobstructed-sample-entry")
        compose.onNodeWithText("先看看示例作品").performClick()
        compose.waitUntil(10000) { runBlocking { AppGraph.db.books().all().isNotEmpty() } }
        val opened=runBlocking { AppGraph.db.books().all().single().id }
        assertEquals(8,runBlocking { AppGraph.db.chapters().chaptersOnce(opened).size })
        assertTrue(runBlocking { AppGraph.db.chapters().chaptersOnce(opened).sumOf { it.wordCount } }>0)
    }
    @Test fun menuLastActionAndAllFourDismissPathsRemainUsable() {
        var open by mutableStateOf(false)
        var chosen by mutableIntStateOf(0)
        render {
            LingButton("打开菜单") { open=true }
            if(open) LingSheet({open=false},"长菜单") {
                repeat(20) { i -> SheetAction(LingIcons.EditOutline,"选项 ${i+1}") { chosen=i+1;open=false } }
            }
        }
        compose.onNodeWithText("打开菜单").performClick()
        compose.onNodeWithText("选项 20").performScrollTo().assertIsDisplayed().performClick()
        compose.waitUntil(10000) { !open };assertEquals(20,chosen)
        compose.waitUntil(10000) { compose.onAllNodesWithText("长菜单").fetchSemanticsNodes().isEmpty() }
        compose.onNodeWithText("打开菜单").performClick()
        compose.onNodeWithText("长菜单").assertIsDisplayed();back()
        compose.waitUntil(10000) { !open }
        compose.onNodeWithText("打开菜单").performClick()
        compose.onNode(isRoot() and hasAnyDescendant(hasText("长菜单")),useUnmergedTree=true).performTouchInput { click(androidx.compose.ui.geometry.Offset(8f,35f)) }
        compose.waitUntil(10000) { !open }
        compose.onNodeWithText("打开菜单").performClick()
        compose.onNodeWithText("长菜单").performTouchInput { swipeDown(startY=0f,endY=500f,durationMillis=600) }
        compose.waitUntil(10000) { !open }
        photo("menu-four-dismiss-paths")
    }
    @Test fun nestedConfirmationReturnsToParentDraft() {
        var parent by mutableStateOf(true);var confirm by mutableStateOf(false)
        render {
            if(parent) LingSheet({parent=false},"父表单") {
                LingButton("确认删除") { confirm=true }
                if(confirm) LingConfirm("再次确认","合成确认",onDismiss={confirm=false},onConfirm={confirm=false;parent=false})
            }
        }
        compose.onNodeWithText("确认删除").performClick()
        compose.waitUntil(10000) { compose.onAllNodesWithText("再次确认").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("取消").performClick()
        compose.waitUntil(10000) { compose.onAllNodesWithText("父表单").fetchSemanticsNodes().isNotEmpty() }
        assertTrue(parent);assertFalse(confirm)
        photo("nested-confirmation-return")
    }
    @Test fun editorNoticeDoesNotCoverStatusOrDrawerAndBackClosesDrawer() {
        runBlocking { AppGraph.prefs.update { it.copy(writeMode="generate",modeTipShown=false) } }
        render { EditorRoute(chapter,rememberNavController()) }
        compose.waitUntil(10000) { compose.onAllNodesWithText(ModeCopy.GENERATE_TIP).fetchSemanticsNodes().isNotEmpty() }
        assertTrue(compose.onNodeWithText(ModeCopy.GENERATE_TIP).fetchSemanticsNode().boundsInWindow.top>24f)
        photo("editor-safe-mode-notice")
        compose.onNodeWithContentDescription("本章便签").performClick()
        compose.onNodeWithText("本章便签").assertIsDisplayed()
        photo("drawer-above-mode-notice")
        back();compose.onNodeWithTag("manuscript-input").assertExists()
        compose.waitUntil(10000) { compose.onAllNodesWithText("本章便签").fetchSemanticsNodes().isEmpty() }
    }
    @Test fun systemBackPersistsChapterPlanRatherThanDiscardingIt() {
        render { val nav=rememberNavController();CompositionLocalProvider(LocalNav provides nav) { OutlineTab(book,nav) } }
        compose.waitUntil(10000) { compose.onAllNodesWithText("Q_开场").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Q_开场").performScrollTo().performClick()
        compose.onNode(hasSetTextAction() and hasText("旧核心事件")).performTextReplacement("Q_系统返回保存")
        back();back()
        compose.waitUntil(10000) { runBlocking { AppGraph.db.plan().chapterOnce(chapter)?.event=="Q_系统返回保存" } }
        assertEquals("KEEP_NOTE",runBlocking { AppGraph.db.plan().chapterOnce(chapter)!!.note })
        photo("plan-system-back-saved")
    }
    @Test fun existingUserSeesOneGuideInvitationAndCanSkip() {
        runBlocking { AppGraph.prefs.update { it.copy(authorGuide=false,authorGuideInviteRevision=0) } }
        render { LingWriteApp() }
        compose.waitUntil(10000) { compose.onAllNodesWithText("写作指导已上线").fetchSemanticsNodes().isNotEmpty() }
        photo("upgrade-guide-invitation")
        compose.onNodeWithText("稍后").performClick()
        compose.waitUntil(10000) { runBlocking { AppGraph.prefs.flow.first().authorGuideInviteRevision==AUTHOR_GUIDE_REVISION } }
        assertFalse(runBlocking { AppGraph.prefs.flow.first().authorGuide })
    }
    @Test fun loreCardCreateEditCancelAndReopenPreserveSavedFields() {
        lateinit var nav: androidx.navigation.NavHostController
        render { nav=rememberNavController();LingWriteApp(nav) }
        compose.runOnIdle { nav.navigate(Routes.lore(book,0)) }
        compose.onNodeWithText("新建设定卡").performScrollTo().performClick()
        compose.onAllNodes(hasSetTextAction())[0].performTextReplacement("Q_人物铜铃")
        compose.onAllNodes(hasSetTextAction())[2].performTextReplacement("KEEP_ROLE")
        compose.onNodeWithText("保存").performScrollTo().performClick()
        compose.waitUntil(10000) { runBlocking { AppGraph.db.lore().cardsOnce(book).any { it.name=="Q_人物铜铃" } } }
        val id=runBlocking { AppGraph.db.lore().cardsOnce(book).single().id }
        assertEquals("KEEP_ROLE",runBlocking { AppGraph.db.lore().cardOnce(id)!!.role })
        compose.onNodeWithContentDescription("编辑").performClick()
        compose.onAllNodes(hasSetTextAction())[0].performTextReplacement("Q_取消草稿")
        back();back()
        compose.waitUntil(10000) { compose.onAllNodesWithText("编辑 Q_人物铜铃").fetchSemanticsNodes().isEmpty() }
        assertEquals("Q_人物铜铃",runBlocking { AppGraph.db.lore().cardOnce(id)!!.name })
        compose.onNodeWithContentDescription("编辑").performClick()
        compose.onNode(hasSetTextAction() and hasText("Q_人物铜铃")).assertExists()
        photo("lore-saved-and-cancelled-draft-reopened")
    }
    @Test fun foreshadowRegistrationAndPayoffSaveUseActualButtons() {
        var open by mutableStateOf(true)
        var existing by mutableStateOf<ForeshadowEntity?>(null)
        render {
            if(open) ForeshadowSheet(FsDraft(book,chapter,1,"Q_铜铃原文",0,existing)) { open=false }
        }
        compose.onAllNodes(hasSetTextAction())[0].performTextReplacement("Q_铜铃缺口")
        compose.onAllNodes(hasSetTextAction())[2].performTextReplacement("8")
        compose.onNodeWithText("保存").performScrollTo().performClick()
        compose.waitUntil(10000) { !open }
        val saved=runBlocking { AppGraph.db.checks().foreshadowsOnce(book).single() }
        assertEquals(8,saved.payoff);assertEquals("Q_铜铃原文",saved.quote)
        compose.runOnIdle { existing=saved;open=true }
        compose.onNodeWithText("已回收").performScrollTo().performClick()
        compose.onNodeWithText("保存").performScrollTo().performClick()
        compose.waitUntil(10000) { !open }
        val paid=runBlocking { AppGraph.db.checks().foreshadowsOnce(book).single() }
        assertEquals("paid",paid.status);assertEquals(1,paid.paidAt)
        assertEquals("KEEP_NOTE",runBlocking { AppGraph.db.plan().chapterOnce(chapter)!!.note })
        photo("foreshadow-registered-and-paid")
    }
    @Test fun editorGuideOpensRealPlanAndCanReopenFromMoreMenu() {
        runBlocking { AppGraph.prefs.update { it.copy(authorGuide=true,authorGuideStep=5) } }
        lateinit var nav: androidx.navigation.NavHostController
        render { nav=rememberNavController();LingWriteApp(nav) }
        compose.runOnIdle { nav.navigate(Routes.editor(chapter)) }
        photo("guide-before-entry")
        compose.waitUntil(10000) { compose.onAllNodesWithText("打开实际入口").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("打开实际入口").performScrollTo().performClick()
        compose.waitUntil(10000) { compose.onAllNodes(hasSetTextAction() and hasText("旧核心事件")).fetchSemanticsNodes().isNotEmpty() }
        compose.onNode(hasSetTextAction() and hasText("旧核心事件")).performTextReplacement("Q_指南实际章纲")
        compose.onNodeWithText("保存").performScrollTo().performClick()
        compose.waitUntil(10000) { runBlocking { AppGraph.db.plan().chapterOnce(chapter)?.event=="Q_指南实际章纲" } }
        compose.onNodeWithContentDescription("更多").performClick()
        compose.onNodeWithText("写作指南").performScrollTo().performClick()
        compose.waitUntil(10000) { compose.onAllNodesWithText("作者写作指导").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("稍后继续 / 跳过").performScrollTo().performClick()
        compose.waitUntil(10000) { runBlocking { !AppGraph.prefs.flow.first().authorGuide } }
        assertEquals("KEEP_NOTE",runBlocking { AppGraph.db.plan().chapterOnce(chapter)!!.note })
        photo("editor-guide-real-plan-and-reopen")
    }
}
