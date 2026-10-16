// SPDX-FileCopyrightText: 2026 WikG and LingWrite contributors
// SPDX-License-Identifier: AGPL-3.0-only
package com.lingwrite.app

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lingwrite.app.data.*
import com.lingwrite.app.data.ai.*
import com.lingwrite.app.data.db.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** 一个统一故事逐步演进，D=实际 Room；R=捕获实际功能发给假模型的请求。 */
@RunWith(AndroidJUnit4::class)
@Config(sdk=[34],application=Application::class)
class AuthorJourneyTest {
    private var b=0L
    private var chapters=listOf<ChapterEntity>()
    private var luo=0L
    private val requests=mutableListOf<ChatRequest>()
    @Before fun seed()=runBlocking {
        AppGraph.init(ApplicationProvider.getApplicationContext(),inMemory=true)
        b=AppGraph.books.createBook("雾港灯谱","悬疑")
        val v1=AppGraph.db.chapters().volumesOnce(b).single().id
        val v2=AppGraph.books.addVolume(b,"潮声");val v3=AppGraph.books.addVolume(b,"灯谱")
        for(n in 2..18)AppGraph.books.addChapterTo(b,if(n<=6)v1 else if(n<=12)v2 else v3,"雾港第$n")
        chapters=AppGraph.db.chapters().chaptersOnce(b)
        chapters.forEach { c->
            val content=when(c.number){7->"";8->"  \n ";11->"长".repeat(18099)+"尾";4,10,18->"罗澄 FUTURE_C${c.number.toString().padStart(2,'0')}";else->"罗澄走进雾港。第${c.number}章合成正文"}
            AppGraph.books.saveContent(c.id,content)
            AppGraph.db.context().upsertSummary(SummaryEntity(c.id,b,if(c.number in listOf(4,10,18))"FUTURE_C${c.number.toString().padStart(2,'0')}" else "前情${c.number}"))
        }
        AppGraph.db.plan().upsertBook(BookPlanEntity(b,synopsis="计划：PLANNED_C18 终局",style="简练",idea="追查灯谱"))
        AppGraph.db.plan().upsertChapter(ChapterPlanEntity(chapters[2].id,b,event="调查灯塔",note="KEEP_AUTHOR_NOTE"))
        LoreType.entries.forEach { type ->
            val id=AppGraph.db.lore().insertCard(LoreCardEntity(bookId=b,type=type.id,name=if(type==LoreType.Character)"罗澄" else type.label,fields=encodeFields(listOf(Field("要点","CURRENT_LORE"))),inject="always"))
            if(type==LoreType.Character)luo=id
        }
        for(n in listOf(4,10,18))AppGraph.db.lore().insertState(LoreStateEntity(cardId=luo,fromChapter=n,field="要点",value="FUTURE_C${n.toString().padStart(2,'0')}"))
        AppGraph.db.lore().insertCard(LoreCardEntity(bookId=b,type="character",name="未确认",pending=true,inject="always",fields=encodeFields(listOf(Field("秘密","PENDING_ONLY")))))
        AppGraph.db.lore().insertCard(LoreCardEntity(bookId=b,type="term",name="不带入",inject="never",fields=encodeFields(listOf(Field("秘密","NEVER_ONLY")))))
        AppGraph.db.checks().insertForeshadow(ForeshadowEntity(bookId=b,chapterId=chapters[0].id,plantNumber=1,content="铜灯线索",payoff=6))
        AppGraph.db.checks().insertForeshadow(ForeshadowEntity(bookId=b,chapterId=chapters[17].id,plantNumber=18,content="FUTURE_C18",payoff=19))
        val other=AppGraph.books.createBook("雾港灯谱");val oc=AppGraph.db.chapters().chaptersOnce(other).single()
        AppGraph.books.saveContent(oc.id,"B_ONLY")
        KeyVault.override=({s:String->s} to {s:String->s})
        AppGraph.db.ai().insertProvider(ProviderEntity(presetId="custom",name="controlled-fake",protocol="openai",baseUrl="http://invalid.local",auth="bearer",models="mock",keyCipher="synthetic"))
        Unit
    }
    @After fun cleanup(){FakeLlmRegistry.provider=null;KeyVault.override=null}
    private fun fake(reply:(ChatRequest)->String){
        FakeLlmRegistry.provider=object:LlmProvider{override fun stream(req:ChatRequest)=flow{requests+=req;emit(LlmEvent.Delta(reply(req)));emit(LlmEvent.Done(10,10))}}
    }
    private fun noFuture(text:String){listOf("FUTURE_C04","FUTURE_C10","FUTURE_C18","B_ONLY","PENDING_ONLY","NEVER_ONLY").forEach{assertFalse("unexpected $it",text.contains(it))}}

    @Test fun revisingChapterThreeUsesOnlyPastFactsAndCurrentStates()=runBlocking {
        val parts=setOf(ContextEngine.Part.Synopsis,ContextEngine.Part.PrevSummaries(10),ContextEngine.Part.Characters,ContextEngine.Part.Lore(),ContextEngine.Part.OpenForeshadows)
        val brief=ContextEngine.brief(b,chapters[2].id,parts)
        noFuture(brief);assertTrue(brief.contains("CURRENT_LORE"));assertTrue(brief.contains("PLANNED_C18"))
        val body=ContextEngine.build(b,chapters[2].id,3,"罗澄来访")
        noFuture(body);assertTrue(body.contains("CURRENT_LORE"))
    }
    @Test fun actualConsistencyAndLoreRequestsExcludeFutureSentinels()=runBlocking {
        fake{req->if(req.messages.last().content.startsWith("从下面"))"""[{"who":"罗澄","claim":"来访","quote":"罗澄"}]""" else "[]"}
        Consistency.check(chapters[2].id);LoreSuggest.generate(chapters[2].id)
        assertTrue(requests.size>=4)
        requests.forEach{noFuture(it.messages.joinToString { m->m.content })}
    }
    @Test fun perChapterStatesAndRelationshipsFollowChronology()=runBlocking {
        val cards=AppGraph.db.lore().cardsOnce(b);val c=cards.single{it.id==luo};val states=AppGraph.db.lore().statesForBook(b)
        assertEquals("CURRENT_LORE",resolveAt(c,states,3).single().v)
        assertEquals("FUTURE_C04",resolveAt(c,states,5).single().v)
        assertEquals("FUTURE_C10",resolveAt(c,states,12).single().v)
        val partner=AppGraph.db.lore().insertCard(LoreCardEntity(bookId=b,type="character",name="林乔"))
        AppGraph.db.lore().insertRelation(LoreRelationEntity(bookId=b,fromId=luo,toId=partner,label="同伴",fromChapter=5))
        AppGraph.db.lore().insertRelation(LoreRelationEntity(bookId=b,fromId=luo,toId=partner,label="敌对",fromChapter=12))
        val rel=AppGraph.db.lore().relationsOnce(b)
        assertTrue(relationsAt(rel,3).isEmpty());assertEquals("同伴",relationsAt(rel,10).single().label);assertEquals("敌对",relationsAt(rel,18).single().label)
    }
    @Test fun actualChapterGenerationStoresOriginVolumeAndPreservesAuthorPlan()=runBlocking {
        fake{ """[{"title":"续章","event":"推进调查","hook":"灯闪","cliff":"新线索"}]""" }
        val v=AppGraph.db.chapters().volumesOnce(b)[1]
        val drafts=ChainRunner.chapters(b,v.id,3)
        assertEquals(v.id,ChainState.parse(AppGraph.db.plan().bookOnce(b)!!.chain).chapterVolumeId)
        ChainRunner.adoptChapters(b,v.id,drafts)
        assertEquals("KEEP_AUTHOR_NOTE",AppGraph.db.plan().chapterOnce(chapters[2].id)!!.note)
        for(n in 4..12)AppGraph.books.addVolume(b,"空卷$n")
        assertEquals(12,AppGraph.db.chapters().volumesOnce(b).size)
    }
    @Test fun handConfirmedSummaryWinsAgainstDelayedAutoResult()=runBlocking {
        val c=chapters[2]
        fake{runBlocking{AppGraph.db.context().upsertSummary(SummaryEntity(c.id,b,"KEEP_AUTHOR_SUMMARY",confirmed=true))};"旧自动摘要"}
        ContextEngine.afterChapterDone(c.id)
        assertEquals("KEEP_AUTHOR_SUMMARY",AppGraph.db.context().summaryOnce(c.id)!!.text)
    }
    @Test fun ambiguousSameNameRelationDoesNotSilentlyChooseFirstPerson()=runBlocking {
        repeat(2){AppGraph.db.lore().insertCard(LoreCardEntity(bookId=b,type="character",name="林乔"))}
        AppGraph.db.loreSuggest().insert(listOf(LoreSuggestionEntity(bookId=b,chapterId=chapters[2].id,number=3,kind="relation",cardId=luo,payload="""{"target":"林乔","label":"同伴"}""")))
        val id=AppGraph.db.loreSuggest().pending(b).first().single().id
        assertFalse(LoreSuggest.adopt(id));assertTrue(AppGraph.db.lore().relationsOnce(b).isEmpty())
    }
    @Test fun emptyErrorAndThrownFakeResponsesNeverBecomeAcceptableBody()=runBlocking {
        fake{""}
        assertTrue(AppGraph.ai.stream("rewrite",Slot.Writing,"",listOf(ChatMessage("user","合成选区"))).toList().last() is AiStream.Error)
        FakeLlmRegistry.provider=object:LlmProvider{override fun stream(req:ChatRequest)=flow<LlmEvent>{throw java.io.IOException("synthetic timeout")}}
        assertTrue(AppGraph.ai.complete("check","","合成原稿",b,chapters[2].id).isFailure)
        assertEquals("罗澄走进雾港。第3章合成正文",AppGraph.db.chapters().get(chapters[2].id)!!.content)
    }
    @Test fun cancellationStopsControlledStreamWithoutWritingManuscript()=runBlocking {
        val started=CompletableDeferred<Unit>()
        FakeLlmRegistry.provider=object:LlmProvider{override fun stream(req:ChatRequest)=flow{emit(LlmEvent.Delta("未完成"));started.complete(Unit);awaitCancellation()}}
        val job=launch { AppGraph.ai.stream("continue",Slot.Writing,"",listOf(ChatMessage("user","合成")),bookId=b,chapterId=chapters[2].id).collect() }
        started.await();job.cancelAndJoin()
        assertEquals("罗澄走进雾港。第3章合成正文",AppGraph.db.chapters().get(chapters[2].id)!!.content)
    }
    @Test fun lateLandedCheckCannotOverwriteNewAuthorPlan()=runBlocking {
        val c=chapters[2]
        fake { runBlocking {
            val cp=AppGraph.db.plan().chapterOnce(c.id)!!
            AppGraph.db.plan().upsertChapter(cp.copy(event="NEW_AUTHOR_EVENT",note="NEW_AUTHOR_NOTE"))
        }; """{"event":true,"hook":true,"cliff":true,"note":"old"}""" }
        assertEquals("正文或章纲已修改，请重新检查",com.lingwrite.app.ui.routes.Outline.checkLanded(c.id))
        val cp=AppGraph.db.plan().chapterOnce(c.id)!!
        assertEquals("NEW_AUTHOR_EVENT",cp.event);assertEquals("NEW_AUTHOR_NOTE",cp.note);assertEquals("",cp.landed)
    }
}
