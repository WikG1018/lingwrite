// SPDX-FileCopyrightText: 2026 WikG and LingWrite contributors
// SPDX-License-Identifier: AGPL-3.0-only
package com.lingwrite.app

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lingwrite.app.data.Prefs
import com.lingwrite.app.data.ai.*
import com.lingwrite.app.data.db.LingDatabase
import com.lingwrite.app.data.db.ProviderEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = android.app.Application::class)
class AiServiceTest {
    @Test fun streamLogsUsage() = runBlocking {
        KeyVault.override = ({ s: String -> "enc:$s" } to { s: String -> s.removePrefix("enc:") })
        val ctx = ApplicationProvider.getApplicationContext<android.app.Application>()
        val db = LingDatabase.build(ctx, inMemory = true)
        val ai = AiService(db.ai(), Prefs(ctx))
        val s = MockWebServer()
        s.enqueue(MockResponse().setBody("data: {\"choices\":[{\"delta\":{\"content\":\"续写\"}}]}\n\ndata: [DONE]\n\n")); s.start()
        db.ai().insertProvider(ProviderEntity(presetId = "custom", name = "测", protocol = "openai", baseUrl = s.url("/v1").toString(), auth = "bearer", models = "m1", keyCipher = KeyVault.encrypt("secret")))
        val out = ai.stream("continue", Slot.Writing, "sys", listOf(ChatMessage("user", "u")), bookId = 1).toList()
        assertEquals("Bearer secret", s.takeRequest().getHeader("Authorization"))
        val fin = out.last() as AiStream.Finished
        assertEquals("续写", fin.text)
        val log = db.ai().log(fin.logId)!!
        assertEquals("continue", log.task); assertEquals(true, log.estimated)
        ai.setOutcome(fin.logId, "accepted")
        assertEquals(1, db.ai().usageSince(0).first().single().calls)
        s.shutdown(); KeyVault.override = null
    }

    /** 兼容网关 200 + error 帧：必须转成 Error，且不能丢掉服务商错误信息。 */
    @Test fun streamSurfacesErrorFrame() = runBlocking {
        KeyVault.override = ({ s: String -> "enc:$s" } to { s: String -> s.removePrefix("enc:") })
        val ctx = ApplicationProvider.getApplicationContext<android.app.Application>()
        val db = LingDatabase.build(ctx, inMemory = true)
        val ai = AiService(db.ai(), Prefs(ctx))
        val s = MockWebServer()
        s.enqueue(MockResponse().setBody("data: {\"error\":{\"message\":\"quota exceeded\"}}\n\n")); s.start()
        db.ai().insertProvider(ProviderEntity(presetId = "custom", name = "测", protocol = "openai", baseUrl = s.url("/v1").toString(), auth = "bearer", models = "m1", keyCipher = KeyVault.encrypt("secret")))
        val out = ai.stream("continue", Slot.Writing, "sys", listOf(ChatMessage("user", "u")), bookId = 1).toList()
        val err = out.last() as AiStream.Error
        assertTrue("错误里应保留服务商信息，实际：${err.message}", err.message.contains("quota exceeded"))
        assertTrue("不应误报「模型没有返回内容」", !err.message.contains("没有返回内容"))
        s.shutdown(); KeyVault.override = null
    }

    /** choices 为 null 的兼容帧不能被当成异常丢掉整段生成。 */
    @Test fun streamToleratesNullChoices() = runBlocking {
        KeyVault.override = ({ s: String -> "enc:$s" } to { s: String -> s.removePrefix("enc:") })
        val ctx = ApplicationProvider.getApplicationContext<android.app.Application>()
        val db = LingDatabase.build(ctx, inMemory = true)
        val ai = AiService(db.ai(), Prefs(ctx))
        val s = MockWebServer()
        s.enqueue(MockResponse().setBody("data: {\"choices\":null}\n\ndata: {\"choices\":[{\"delta\":{\"content\":\"正常\"}}]}\n\ndata: [DONE]\n\n")); s.start()
        db.ai().insertProvider(ProviderEntity(presetId = "custom", name = "测", protocol = "openai", baseUrl = s.url("/v1").toString(), auth = "bearer", models = "m1", keyCipher = KeyVault.encrypt("secret")))
        val out = ai.stream("continue", Slot.Writing, "sys", listOf(ChatMessage("user", "u")), bookId = 1).toList()
        assertEquals("正常", (out.last() as AiStream.Finished).text)
        s.shutdown(); KeyVault.override = null
    }
}
