// SPDX-FileCopyrightText: 2026 WikG and LingWrite contributors
// SPDX-License-Identifier: AGPL-3.0-only
package com.lingwrite.app

import androidx.activity.ComponentActivity
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.navigation.compose.rememberNavController
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lingwrite.app.data.*
import com.lingwrite.app.data.ai.*
import com.lingwrite.app.data.db.*
import com.lingwrite.app.ui.routes.*
import com.lingwrite.app.ui.screens.EditorScreen
import com.lingwrite.app.ui.screens.FirstRunScreen
import com.lingwrite.app.ui.LingWriteApp
import com.lingwrite.app.ui.Routes
import androidx.test.platform.app.InstrumentationRegistry
import com.lingwrite.app.ui.theme.LingTheme
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith

/** E: 仅任务专用模拟器。每次新内存库，不读取真实稿件，无外部模型调用。 */
@RunWith(AndroidJUnit4::class)
class AuthorDeviceTest {
    @get:Rule val compose=createAndroidComposeRule<ComponentActivity>()
    private var b=0L;private var ch=0L
    @Before fun seed()=runBlocking {
        AppGraph.init(ApplicationProvider.getApplicationContext(),inMemory=true);Modules.install()
        AppGraph.prefs.update { AppPrefs(onboarded=true,authorGuideInviteRevision=AUTHOR_GUIDE_REVISION,autoCheck=false,autoUpdateCheck=false,stuckHint=false,notifyAll=false,notifyAsked=true) }
        b=AppGraph.books.createBook("E_雾港灯谱");ch=AppGraph.db.chapters().chaptersOnce(b).single().id
        AppGraph.books.renameChapter(ch,"E_首章");AppGraph.books.saveContent(ch,"中文合成稿件。雾港灯谱。")
    }
    @After fun cleanup(){FakeLlmRegistry.provider=null;KeyVault.override=null}
    @Test fun editorSavesChineseRevisionsUndoRedoAndReentry(){
        compose.setContent{LingTheme(false){EditorRoute(ch,rememberNavController())}}
        compose.waitUntil(10000){compose.onAllNodes(hasSetTextAction()).fetchSemanticsNodes().isNotEmpty()}
        compose.onNode(hasSetTextAction()).performTextReplacement("中文修订稿。保留罗澄与灯谱。")
        compose.onNodeWithContentDescription("撤销").performClick();compose.onNodeWithContentDescription("重做").performClick()
        compose.waitUntil(10000){runBlocking{AppGraph.db.chapters().get(ch)?.content=="中文修订稿。保留罗澄与灯谱。"}}
        assertEquals("中文修订稿。保留罗澄与灯谱。",runBlocking{AppGraph.books.editorChapter(ch)!!.content})
    }
    @Test fun chapterDeletionConfirmationCancelAndRecovery(){
        compose.setContent{LingTheme(false){CompositionLocalProvider(LocalNav provides rememberNavController()){WorkRoute(b,{},{},{},{})}}}
        compose.waitUntil(10000){compose.onAllNodes(hasScrollToIndexAction()).fetchSemanticsNodes().isNotEmpty()}
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("E_首章"))
        compose.onNodeWithText("E_首章").performTouchInput{longClick()}
        compose.onNodeWithText("删除本章").performClick();compose.onNodeWithText("取消").performClick()
        assertEquals(0L,runBlocking{AppGraph.db.chapters().get(ch)!!.deletedAt})
        compose.onNodeWithText("E_首章").performTouchInput{longClick()};compose.onNodeWithText("删除本章").performClick()
        compose.onNodeWithText("移入回收站").performClick()
        compose.waitUntil(10000){runBlocking{AppGraph.db.chapters().get(ch)!!.deletedAt>0}}
        runBlocking{AppGraph.books.restoreChapter(ch)};assertEquals("中文合成稿件。雾港灯谱。",runBlocking{AppGraph.db.chapters().get(ch)!!.content})
    }
    @Test fun actualAiAcceptanceRejectsLateResultWithoutChangingBody(){
        val c=runBlocking{AppGraph.db.chapters().get(ch)!!};val st=EditorState(c)
        val s=sessionFor(ch);s.clear();s.tool=Prompts.rewrite;s.target=0 until 4;s.anchor=TextAnchor(st.value.text,0,4);s.text="旧结果"
        st.edit(androidx.compose.ui.text.input.TextFieldValue("新编辑"+c.content))
        compose.setContent{LingTheme(false){EditorScreen(value=st.value,onValueChange=st::edit,marks=st.marks,bottomPanel={EditorHooks.aiPanel(this,st,mutableStateOf(true))})}}
        compose.onNodeWithText("替换选中").performClick();compose.waitUntil(10000){s.error!=null}
        assertEquals("新编辑"+c.content,st.value.text)
    }
    @Test fun guideSkipKeepsProgressAndDoesNotCreateBook(){
        runBlocking{AppGraph.prefs.update{it.copy(authorGuide=true,authorGuideStep=3)}}
        compose.setContent{LingTheme(false){AuthorGuide(rememberNavController())}}
        compose.waitUntil(10000){compose.onAllNodesWithText("稍后继续 / 跳过").fetchSemanticsNodes().isNotEmpty()}
        compose.onNodeWithText("稍后继续 / 跳过").performScrollTo().performClick()
        compose.waitUntil(10000){runBlocking{!AppGraph.prefs.flow.first().authorGuide}}
        assertEquals(3,runBlocking{AppGraph.prefs.flow.first().authorGuideStep});assertEquals(1,runBlocking{AppGraph.db.books().all().size})
    }
    @Test fun manualChapterPlanSavesWithoutOverwritingOtherFields(){
        runBlocking{AppGraph.db.plan().upsertChapter(ChapterPlanEntity(ch,b,event="旧事件",note="KEEP_AUTHOR_NOTE"))}
        var shown by mutableStateOf(true)
        compose.setContent{LingTheme(false){if(shown)ChapterPlanSheet(b,ch){shown=false}}}
        compose.waitUntil(10000){compose.onAllNodes(hasSetTextAction()).fetchSemanticsNodes().isNotEmpty()}
        compose.onAllNodes(hasSetTextAction())[0].performTextReplacement("雾港灯塔调查")
        compose.onNodeWithText("保存",useUnmergedTree=true).performScrollTo().performClick()
        compose.waitUntil(10000){runBlocking{AppGraph.db.plan().chapterOnce(ch)?.event=="雾港灯塔调查"}}
        assertEquals("KEEP_AUTHOR_NOTE",runBlocking{AppGraph.db.plan().chapterOnce(ch)!!.note})
    }
    @Test fun registeredAuthorEntryRoutesRenderOnDevice(){
        lateinit var nav:androidx.navigation.NavHostController
        compose.setContent{LingTheme(false){nav=rememberNavController();LingWriteApp(nav)}}
        val routes=listOf(Routes.Shelf,"work/$b?tab=0","work/$b?tab=1","work/$b?tab=2","work/$b?tab=3","work/$b?tab=4",
            Routes.lore(b,0),"lorelib/$b/character","lorelib/$b/faction","lorelib/$b/place","lorelib/$b/item","lorelib/$b/power","lorelib/$b/term",
            "graph/$b","maps/$b","lore-suggest/$b","threads/$b","golden/$b","issues/$b","style/$b",
            "chain/$b?step=1","chain/$b?step=2","chain/$b?step=3","chain/$b?step=4","chain/$b?step=5","chain/$b?step=6",
            Routes.editor(ch),Routes.search(b),Routes.search(0),Routes.Ideas,Routes.Stats,Routes.Models,"provider/0/custom",Routes.Settings,Routes.About,
            AboutModule.Notifications,UpdateModule.Route,Routes.Backup,Routes.export(b),Routes.reader(b,1),Routes.breakdown(b),Routes.wizard(b))
        val ctx=ApplicationProvider.getApplicationContext<android.content.Context>()
        val dir=java.io.File(ctx.getExternalFilesDir(null),"author-device-routes").apply{mkdirs()}
        val results=mutableListOf<String>()
        for((i,route) in routes.withIndex()){
            compose.runOnIdle{nav.navigate(route){popUpTo(Routes.Shelf);launchSingleTop=true}}
            compose.waitForIdle()
            assertTrue("no root: $route",compose.onAllNodes(isRoot()).fetchSemanticsNodes().isNotEmpty())
            val bitmap=InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
            java.io.File(dir,"route-${i.toString().padStart(2,'0')}.png").outputStream().use{bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)}
            bitmap.recycle();results+="$i\tPASS render\t$route"
        }
        java.io.File(dir,"routes.tsv").writeText(results.joinToString("\n"))
        assertEquals("中文合成稿件。雾港灯谱。",runBlocking{AppGraph.db.chapters().get(ch)!!.content})
    }
    @Test fun firstRunBothModesRemainReachableOnSmallDisplay(){
        var selected:WriteMode?=null
        compose.setContent{LingTheme(false){FirstRunScreen{selected=it}}}
        compose.onNodeWithText("生成模式").performScrollTo().performClick()
        compose.onNodeWithText("用生成模式开始").performClick()
        assertEquals(WriteMode.Generate,selected)
    }
}
