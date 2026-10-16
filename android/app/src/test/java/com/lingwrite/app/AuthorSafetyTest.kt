// SPDX-FileCopyrightText: 2026 WikG and LingWrite contributors
// SPDX-License-Identifier: AGPL-3.0-only
package com.lingwrite.app

import android.app.Application
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lingwrite.app.data.*
import com.lingwrite.app.data.ai.*
import com.lingwrite.app.data.db.*
import com.lingwrite.app.ui.routes.EditorState
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.*

@RunWith(AndroidJUnit4::class)
@Config(sdk=[34], application=Application::class)
class AuthorSafetyTest {
    private val ctx get()=ApplicationProvider.getApplicationContext<Application>()
    @Before fun seed(){AppGraph.init(ctx,inMemory=true)}
    private suspend fun book()=AppGraph.books.createBook("雾港灯谱")
    private suspend fun chapter(book:Long)=AppGraph.db.chapters().chaptersOnce(book).single()
    private fun zip()=Backup.readZip(ByteArrayInputStream(ByteArrayOutputStream().also { Backup.writeZip(it,ctx,AppPrefs()) }.toByteArray()))

    @Test fun deletedChapterKeepsBodyPlanAndHistoryAndRestores()=runBlocking {
        val b=book();val c=chapter(b)
        AppGraph.books.saveContent(c.id,"合成稿件")
        AppGraph.books.snapshot(c.id)
        AppGraph.db.plan().upsertChapter(ChapterPlanEntity(c.id,b,event="KEEP_AUTHOR_NOTE"))
        AppGraph.books.deleteChapter(c.id)
        assertTrue(AppGraph.db.chapters().chaptersOnce(b).isEmpty())
        assertEquals("合成稿件",AppGraph.db.chapters().get(c.id)!!.content)
        assertEquals(2,AppGraph.books.versions(c.id).first().size)
        assertEquals("KEEP_AUTHOR_NOTE",AppGraph.db.plan().chapterOnce(c.id)!!.event)
        AppGraph.books.saveContent(c.id,"迟到保存不能改回收站")
        AppGraph.books.restoreChapter(c.id)
        assertEquals("合成稿件",chapter(b).content)
    }
    @Test fun queuedExitSaveIsVisibleToImmediateReentry()=runBlocking {
        val b=book();val c=chapter(b)
        AppGraph.books.saveContent(c.id,"旧A")
        AppGraph.books.queueSave(c.id,"最新B","[]")
        val reopened=AppGraph.books.editorChapter(c.id)!!
        assertEquals("最新B",reopened.content)
        AppGraph.books.saveContent(c.id,reopened.content)
        assertEquals("最新B",AppGraph.db.chapters().get(c.id)!!.content)
    }
    @Test fun staleSaveDoesNotReplaceNewerExitDraft()=runBlocking {
        val b=book();val c=chapter(b)
        AppGraph.books.saveContent(c.id,"基线")
        val rev=AppGraph.books.captureRevision()
        val job=AppGraph.books.queueSave(c.id,"最新B","[]")
        AppGraph.books.saveContent(c.id,"旧A",revision=rev)
        job.join()
        assertEquals("最新B",AppGraph.db.chapters().get(c.id)!!.content)
        assertEquals("最新B",AppGraph.books.editorChapter(c.id)!!.content)
    }
    @Test fun newerExitDraftSurvivesConcurrentStaleSaves()=runBlocking {
        val b=book();val c=chapter(b)
        val jobs=mutableListOf<Job>()
        repeat(30){i->
            val rev=AppGraph.books.captureRevision()
            jobs+=launch(Dispatchers.Default){AppGraph.books.saveContent(c.id,"旧$i",revision=rev)}
            jobs+=AppGraph.books.queueSave(c.id,"新$i","[]")
        }
        jobs.forEach{it.join()}
        assertEquals("新29",AppGraph.db.chapters().get(c.id)!!.content)
    }
    @Test fun guideReplacesOpenChainAndWorkDestinations(){
        assertTrue(com.lingwrite.app.ui.routes.guideReplacesDestination("chain/{bookId}?step={step}","chain/3?step=4"))
        assertTrue(com.lingwrite.app.ui.routes.guideReplacesDestination("work/{bookId}?tab={tab}","work/3?tab=3"))
        assertFalse(com.lingwrite.app.ui.routes.guideReplacesDestination("shelf","chain/3?step=4"))
        assertFalse(com.lingwrite.app.ui.routes.guideReplacesDestination(null,"work/3?tab=3"))
    }
    @Test fun contentSaveDoesNotOverwriteTitleOrCompletedState()=runBlocking {
        val b=book();val c=chapter(b)
        coroutineScope {
            val saving=launch(Dispatchers.IO){repeat(20){AppGraph.books.saveContent(c.id,"保存$it")}}
            AppGraph.books.renameChapter(c.id,"新名");AppGraph.books.setChapterState(c.id,"done");saving.join()
        }
        val saved=AppGraph.db.chapters().get(c.id)!!
        assertEquals("新名",saved.title);assertEquals("done",saved.state)
    }
    @Test fun anchorRejectsEditedBodyAndDistinguishesRepeatedSentence(){
        val text="他推门。雨声。它推门。他推门。"
        val at=text.lastIndexOf("他推门。")
        val a=TextAnchor(text,at,at+4)
        assertTrue(a.valid(text));assertEquals(at,a.range(text)!!.first)
        assertFalse(a.valid("新增"+text));assertNull(a.range("新增"+text))
    }
    @Test fun undoRedoPreservesChineseCompositionAndMarks(){
        val st=EditorState(ChapterEntity(bookId=1,volumeId=1,number=1,title="",content="原稿"))
        val composing=TextFieldValue("原稿中文",TextRange(4),TextRange(2,4))
        st.edit(composing);assertEquals(composing.composition,st.value.composition)
        st.undo();assertEquals("原稿",st.value.text);st.redo();assertEquals("原稿中文",st.value.text)
        st.undo();st.edit(TextFieldValue("另一稿"));assertFalse(st.canRedo)
    }
    @Test fun mergeRemapsAskHistoryToNewChapter()=runBlocking {
        val b=book();val c=chapter(b)
        AppGraph.db.stats().insertIdea(IdeaEntity(text="问",kind="ask",bookId=b,chapterId=c.id,answer="答"))
        val a=zip();AppGraph.books.rename(b,"改名旧书")
        assertEquals(1,Backup.restore(a,ctx,false))
        val nb=AppGraph.db.books().all().single { it.id!=b }.id;val nc=chapter(nb)
        assertEquals(1,AppGraph.db.stats().askHistory(nc.id).first().size)
        assertEquals(1,AppGraph.db.stats().askHistory(c.id).first().size)
    }
    @Test fun malformedAndBadImageArchiveCannotOverwrite()=runBlocking {
        val b=book();val a=zip()
        val empty=Backup.Archive(buildJsonObject{put("app","lingwrite");put("format",1);put("tables",JsonObject(emptyMap()))},emptyMap())
        assertTrue(runCatching{Backup.restore(empty,ctx,true)}.isFailure)
        assertTrue(runCatching{Backup.restore(Backup.Archive(a.json,mapOf("maps/missing/nested.png" to byteArrayOf(1))),ctx,true)}.isFailure)
        assertEquals("雾港灯谱",AppGraph.db.books().get(b)!!.title)
    }
    @Test fun legacyBackupDefaultsAndTrashRoundTrip()=runBlocking {
        val b=book();val c=chapter(b);AppGraph.books.saveContent(c.id,"迁移正文")
        val a=zip();val ts=a.tables.toMutableMap()
        ts["books"]=JsonArray(ts["books"]!!.jsonArray.map{JsonObject(it.jsonObject-setOf("writeMode","bufferTarget","publishedUpTo"))})
        ts["chapters"]=JsonArray(ts["chapters"]!!.jsonArray.map{JsonObject(it.jsonObject-"deletedAt")})
        assertEquals(1,Backup.restore(Backup.Archive(JsonObject(a.json+("tables" to JsonObject(ts))),emptyMap()),ctx,true))
        assertEquals("迁移正文",chapter(b).content)
        AppGraph.books.deleteChapter(c.id);val trash=zip();Backup.restore(trash,ctx,true)
        assertEquals(1,AppGraph.books.deletedChapters(b).first().size)
    }
    @Test fun exportVolumeOrderAndReorderedSearchStayConsistent()=runBlocking {
        val b=book();val c=chapter(b);AppGraph.books.saveContent(c.id,"第一卷审核索引")
        AppGraph.books.addVolume(b,"后卷");val other=AppGraph.books.nextChapter(b);AppGraph.books.saveContent(other,"第二卷正文")
        val inserted=AppGraph.books.nextChapter(b,afterChapterId=c.id);AppGraph.books.saveContent(inserted,"第一卷新增正文")
        val out=Exporter.render(b,Exporter.Options());assertTrue(out.indexOf("第一卷新增正文")<out.indexOf("第二卷正文"))
        ContextEngine.refresh(b);AppGraph.books.moveChapter(c.id,1);ContextEngine.refresh(b)
        assertEquals(AppGraph.db.chapters().get(c.id)!!.number,ContextEngine.search("审核索引",b).single().number)
    }
    @Test fun chineseSingleCharacterAndMultiWordSearchRequiresBoth()=runBlocking {
        val b=book();val c=chapter(b);AppGraph.books.saveContent(c.id,"沈砚在窗前")
        val n=AppGraph.books.nextChapter(b);AppGraph.books.saveContent(n,"沈砚拔出剑")
        ContextEngine.refresh(b);assertEquals(n,ContextEngine.search("沈砚 剑",b).single().chapterId)
    }
    @Test fun proofFailureIsNotSuccessfulEmptyResult()=runBlocking {
        val b=book();val c=chapter(b)
        assertTrue(runCatching{Proof.run(b,c.id,"已后在见")}.isFailure)
    }
    @Test fun partialVolumeOverwriteKeepsOriginalIndex()=runBlocking {
        val b=book();val v2=AppGraph.books.addVolume(b,"卷二");val v3=AppGraph.books.addVolume(b,"卷三")
        val drafts=listOf(ChainPrompts.VolumeDraft(name="第二新卷",outline="第二纲",goal="目标二",climax="高潮二",chapters=2),ChainPrompts.VolumeDraft(name="第三新卷",outline="第三纲",goal="目标三",climax="高潮三",chapters=2))
        ChainRunner.adoptVolumes(b,drafts,true,listOf(1,2))
        assertNull(AppGraph.db.plan().volumeOnce(AppGraph.db.chapters().volumesOnce(b).first().id))
        assertEquals("第二纲",AppGraph.db.plan().volumeOnce(v2)!!.outline);assertEquals("第三纲",AppGraph.db.plan().volumeOnce(v3)!!.outline)
    }
    @Test fun shortUpdateRequestContainsActualUnwrittenPlan()=runBlocking {
        val b=book();val c=chapter(b)
        AppGraph.db.plan().upsertChapter(ChapterPlanEntity(c.id,b,event="UPCOMING_SENTINEL"))
        KeyVault.override=({s:String->s} to {s:String->s})
        AppGraph.db.ai().insertProvider(ProviderEntity(presetId="custom",name="fake",protocol="openai",baseUrl="http://invalid.local",auth="bearer",models="mock",keyCipher="synthetic"))
        var request=""
        FakeLlmRegistry.provider=object:LlmProvider{override fun stream(req:ChatRequest)=flow{request=req.messages.joinToString{it.content};emit(LlmEvent.Delta("""{"split":{"from":1,"a":{},"b":{}},"bridges":[]}"""));emit(LlmEvent.Done(1,1))}}
        try{SerialBoard.plan(b,SerialBoard.state(b));assertTrue(request.contains("UPCOMING_SENTINEL"))}finally{FakeLlmRegistry.provider=null;KeyVault.override=null}
    }
    @Test fun oldUsersDoNotReceiveForcedGuideAndGuidePrefsRoundTrip(){
        assertFalse(AppPrefs(onboarded=true).authorGuide)
        val p=AppPrefs(authorGuide=true,authorGuideStep=4)
        assertEquals(p,LingJson.decodeFromString(AppPrefs.serializer(),LingJson.encodeToString(AppPrefs.serializer(),p)))
    }
    @Test fun mergeAlsoRemapsJsonCandidateVolumeAndSameNameRelationTarget()=runBlocking {
        val b=book();val c=chapter(b);val v=AppGraph.db.chapters().volumesOnce(b).single()
        val from=AppGraph.db.lore().insertCard(LoreCardEntity(bookId=b,type="character",name="林乔"))
        val to=AppGraph.db.lore().insertCard(LoreCardEntity(bookId=b,type="character",name="林乔"))
        AppGraph.db.plan().upsertBook(BookPlanEntity(b,chain=ChainState(chapterVolumeId=v.id).encode()))
        AppGraph.db.loreSuggest().insert(listOf(LoreSuggestionEntity(bookId=b,chapterId=c.id,number=1,kind="relation",cardId=from,payload="""{"cardId":$from,"targetId":$to,"target":"林乔","label":"同伴"}""")))
        val a=zip();AppGraph.books.rename(b,"旧书");Backup.restore(a,ctx,false)
        val nb=AppGraph.db.books().all().single{it.id!=b}.id;val nv=AppGraph.db.chapters().volumesOnce(nb).single()
        assertEquals(nv.id,ChainState.parse(AppGraph.db.plan().bookOnce(nb)!!.chain).chapterVolumeId)
        val suggestion=AppGraph.db.loreSuggest().pending(nb).first().single()
        assertTrue(LoreSuggest.adopt(suggestion.id))
        val rel=AppGraph.db.lore().relationsOnce(nb).single()
        assertNotEquals(from,rel.fromId);assertNotEquals(to,rel.toId)
        assertEquals(nb,AppGraph.db.lore().cardOnce(rel.toId)!!.bookId)
    }
    @Test fun noteUndoPreservesLaterAuthorChangesAndTrashIsNotSemanticContext()=runBlocking {
        val b=book();val c=chapter(b);val dao=AppGraph.db.plan()
        dao.upsertChapter(ChapterPlanEntity(c.id,b,note="原备注+AI回收建议+后来手改"))
        assertEquals(0,dao.restoreNoteIfUnchanged(c.id,"原备注+AI回收建议","原备注"))
        assertEquals("原备注+AI回收建议+后来手改",dao.chapterOnce(c.id)!!.note)
        AppGraph.books.saveContent(c.id,"不可再引用的已删稿");ContextEngine.refresh(b)
        AppGraph.db.context().passageIds(c.id).forEach{AppGraph.db.context().setVector(it,"1,0")}
        AppGraph.books.deleteChapter(c.id)
        assertTrue(AppGraph.db.context().vectored(b).isEmpty())
        assertTrue(ContextEngine.search("已删稿",b).isEmpty())
    }
}
