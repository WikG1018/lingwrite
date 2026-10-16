// SPDX-FileCopyrightText: 2026 WikG and LingWrite contributors
// SPDX-License-Identifier: AGPL-3.0-only
package com.lingwrite.app

import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.navigation.compose.rememberNavController
import com.github.takahirom.roborazzi.captureRoboImage
import com.lingwrite.app.data.TextAnchor
import com.lingwrite.app.data.ai.Prompts
import com.lingwrite.app.ui.routes.*
import com.lingwrite.app.ui.screens.EditorScreen
import com.lingwrite.app.ui.theme.LingTheme
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** U: 实际 Compose handler，不声称实体设备覆盖。 */
class AuthorSafetyUiTest:RouteShotBase(){
    private fun shot(name:String){val roots=compose.onAllNodes(isRoot());roots[roots.fetchSemanticsNodes().lastIndex].captureRoboImage(File(System.getProperty("lingwrite.shotDir"),"fixed-$name.png").path)}
    @Test fun deletingRequiresConfirmationAndPreservesRecoverableHistory(){
        val c=runBlocking{AppGraph.db.chapters().chaptersOnce(bookId).last().also{AppGraph.books.renameChapter(it.id,"U_DELETE");AppGraph.books.snapshot(it.id)}}
        compose.setContent{LingTheme(false){CompositionLocalProvider(LocalNav provides rememberNavController()){WorkRoute(bookId,{},{},{},{})}}}
        compose.waitUntil(5000){compose.onAllNodesWithText("U_DELETE").fetchSemanticsNodes().isNotEmpty()}
        compose.onNodeWithText("U_DELETE").performScrollTo().performTouchInput{longClick()}
        compose.onNodeWithText("删除本章").performClick()
        compose.onNodeWithText("删除本章？").assertExists();assertEquals(0L,runBlocking{AppGraph.db.chapters().get(c.id)!!.deletedAt})
        shot("delete-confirm")
        compose.onNodeWithText("移入回收站").performClick()
        compose.waitUntil(5000){runBlocking{AppGraph.db.chapters().get(c.id)!!.deletedAt>0}}
        assertTrue(runBlocking{AppGraph.books.versions(c.id).first().isNotEmpty()})
        runBlocking{AppGraph.books.restoreChapter(c.id)}
        assertEquals(c.content,runBlocking{AppGraph.db.chapters().get(c.id)!!.content})
    }
    @Test fun staleAiAcceptShowsConflictAndLeavesAuthorsRevision(){
        val c=runBlocking{AppGraph.db.chapters().chaptersOnce(bookId).last()}
        val st=EditorState(c.copy(content="甲乙丙丁戊己"));val s=sessionFor(c.id)
        s.clear();s.tool=Prompts.rewrite;s.target=2 until 4;s.anchor=TextAnchor(st.value.text,2,4);s.text="改写"
        st.edit(TextFieldValue("新增甲乙丙丁戊己",TextRange(8)))
        compose.setContent{LingTheme(false){EditorScreen(value=st.value,onValueChange=st::edit,marks=st.marks,bottomPanel={EditorHooks.aiPanel(this,st,mutableStateOf(true))})}}
        compose.onNodeWithText("替换选中").performClick()
        compose.waitUntil(5000){s.error!=null}
        assertEquals("新增甲乙丙丁戊己",st.value.text);shot("ai-conflict")
    }
    @Test fun repeatedSentenceAnnotationUsesSelectedSecondOccurrence(){
        val c=runBlocking{AppGraph.db.chapters().chaptersOnce(bookId).last()};val body="他推门。雨声。他推门。"
        val st=EditorState(c.copy(content=body));val s=sessionFor(c.id);s.clear()
        val at=body.lastIndexOf("他推门。");s.revisions=listOf(Revision(Annotation("他推门。","第二处",TextAnchor(body,at,at+4)),"他离开。"))
        compose.setContent{LingTheme(false){EditorHooks.overlays(st,rememberNavController())}}
        compose.onNodeWithText("应用选中的修改").performClick()
        compose.waitUntil(5000){st.value.text=="他推门。雨声。他离开。"}
        assertEquals("他推门。雨声。他离开。",st.value.text)
    }
    @Test fun completedDraftOffersBatchAdoptionButStreamingDoesNot(){
        val streaming=mutableStateOf(-1)
        compose.setContent{LingTheme(false){DraftPane(listOf("一","二"),2,streaming.value,{},{},{},{},{})}}
        compose.onNodeWithText("全部采纳").assertExists();shot("draft-complete")
        compose.runOnIdle{streaming.value=1};compose.onNodeWithText("全部采纳").assertDoesNotExist()
    }
    @Test fun guideCanSkipAndResumeWithoutCreatingOrEditingBook(){
        val count=runBlocking{AppGraph.db.books().all().size};val text=runBlocking{AppGraph.db.chapters().chaptersOnce(bookId).map{it.content}}
        runBlocking{AppGraph.prefs.update{it.copy(onboarded=true,authorGuide=true,authorGuideStep=2)}}
        compose.setContent{LingTheme(false){AuthorGuide(rememberNavController())}}
        compose.waitUntil(5000){compose.onAllNodesWithText("稍后继续 / 跳过").fetchSemanticsNodes().isNotEmpty()}
        shot("guide-step3")
        compose.onNodeWithText("稍后继续 / 跳过").performScrollTo().performClick()
        compose.waitUntil(5000){runBlocking{!AppGraph.prefs.flow.first().authorGuide}}
        assertEquals(2,runBlocking{AppGraph.prefs.flow.first().authorGuideStep})
        assertEquals(count,runBlocking{AppGraph.db.books().all().size});assertEquals(text,runBlocking{AppGraph.db.chapters().chaptersOnce(bookId).map{it.content}})
    }
}
