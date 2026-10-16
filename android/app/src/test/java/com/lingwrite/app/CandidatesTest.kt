// SPDX-FileCopyrightText: 2026 WikG and LingWrite contributors
// SPDX-License-Identifier: AGPL-3.0-only
package com.lingwrite.app

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lingwrite.app.data.Prefs
import com.lingwrite.app.data.ai.AiService
import com.lingwrite.app.data.ai.KeyVault
import com.lingwrite.app.data.ai.ChatMessage
import com.lingwrite.app.data.ai.FakeLlmRegistry
import com.lingwrite.app.data.ai.LlmEvent
import com.lingwrite.app.data.ai.LlmProvider
import com.lingwrite.app.data.ai.Slot
import com.lingwrite.app.data.db.LingDatabase
import com.lingwrite.app.data.db.ProviderEntity
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** A3：多候选的顺序流式生成与失败降级。 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = android.app.Application::class)
class CandidatesTest {
    @After fun cleanup() { FakeLlmRegistry.provider = null }

    private class ScriptProvider(val replies: List<String>, val failAt: Int? = null) : LlmProvider {
        var calls = 0
        val lastMessages = mutableListOf<String>()
        override fun stream(req: com.lingwrite.app.data.ai.ChatRequest) = flow {
            lastMessages += req.messages.last().content
            val i = calls++
            if (failAt == i) emit(LlmEvent.Failed("网络超时")) else {
                emit(LlmEvent.Delta(replies[i]))
                emit(LlmEvent.Done(10, 10))
            }
        }
    }

    private fun service(): Triple<AiService, LingDatabase, MockWebServer> = runBlocking {
        KeyVault.override = ({ s: String -> "enc:$s" } to { s: String -> s.removePrefix("enc:") })
        val ctx = ApplicationProvider.getApplicationContext<android.app.Application>()
        val db = LingDatabase.build(ctx, inMemory = true)
        val s = MockWebServer()
        s.enqueue(MockResponse().setBody("data: {\"choices\":[{\"delta\":{\"content\":\"x\"}}]}\n\ndata: [DONE]\n\n"))
        s.start()
        db.ai().insertProvider(ProviderEntity(presetId = "custom", name = "测", protocol = "openai", baseUrl = s.url("/v1").toString(), auth = "bearer", models = "m1", keyCipher = KeyVault.encrypt("secret")))
        Triple(AiService(db.ai(), Prefs(ctx)), db, s)
    }

    private fun done(ai: AiService, n: Int): List<AiService.AiMulti.Done> = runBlocking {
        ai.streamMany("continue", Slot.Writing, "sys", listOf(ChatMessage("user", "续写")), n).toList()
            .filterIsInstance<AiService.AiMulti.Done>()
    }

    @Test fun sequentialThreeCandidates() = runBlocking {
        val fake = ScriptProvider(listOf("候选一。", "候选二。", "候选三。"))
        FakeLlmRegistry.provider = fake
        val (ai, db, s) = service()
        val out = ai.streamMany("continue", Slot.Writing, "sys", listOf(ChatMessage("user", "续写")), 3).toList()
        val dones = out.filterIsInstance<AiService.AiMulti.Done>()
        assertEquals(listOf("候选一。", "候选二。", "候选三。"), dones.map { it.text })
        assertEquals(0, dones[0].index); assertEquals(2, dones[2].index)
        assertEquals(3, (out.last() as AiService.AiMulti.Finished).ok)
        assertEquals(3, fake.calls)
        // 每条候选各有 call_logs，采纳第 3 条后 chosen = 2
        val log = db.ai().log(dones[2].logId)!!
        assertEquals("continue", log.task)
        db.ai().setChoice(log.id, 3, 2)
        assertEquals(2, db.ai().log(log.id)!!.chosen)
        FakeLlmRegistry.provider = null; s.shutdown(); KeyVault.override = null
    }

    @Test fun laterFailureKeepsEarlierCandidates() = runBlocking {
        FakeLlmRegistry.provider = ScriptProvider(listOf("候选一。", "候选二。", "候选三。"), failAt = 1)
        val (ai, _, s) = service()
        val out = ai.streamMany("continue", Slot.Writing, "sys", listOf(ChatMessage("user", "续写")), 3).toList()
        val dones = out.filterIsInstance<AiService.AiMulti.Done>()
        assertEquals(listOf("候选一。", "候选三。"), dones.map { it.text })
        assertEquals(1, out.filterIsInstance<AiService.AiMulti.Failed>().single().index)
        assertEquals(2, (out.last() as AiService.AiMulti.Finished).ok)
        FakeLlmRegistry.provider = null; s.shutdown(); KeyVault.override = null
    }

    @Test fun firstFailureErrorsOut() = runBlocking {
        FakeLlmRegistry.provider = ScriptProvider(emptyList(), failAt = 0)
        val (ai, _, s) = service()
        val out = ai.streamMany("continue", Slot.Writing, "sys", listOf(ChatMessage("user", "续写")), 3).toList()
        assertTrue(out.single() is AiService.AiMulti.Failed)
        FakeLlmRegistry.provider = null; s.shutdown(); KeyVault.override = null
    }

    @Test fun promptsDiverge() = runBlocking {
        // 第 2 条及以后的提示词末尾追加“与前面不同”，让候选之间有差异（ai-features §3）
        val fake = ScriptProvider(listOf("甲。", "乙。", "丙。"))
        FakeLlmRegistry.provider = fake
        val (ai, _, s) = service()
        done(ai, 3)
        assertEquals(3, fake.lastMessages.size)
        assertTrue(fake.lastMessages[1].contains("与前面不同"))
        assertTrue(fake.lastMessages[2].contains("与前面不同"))
        assertTrue(!fake.lastMessages[0].contains("与前面不同"))
        FakeLlmRegistry.provider = null; s.shutdown(); KeyVault.override = null
    }
}
