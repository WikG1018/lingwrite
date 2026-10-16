// SPDX-FileCopyrightText: 2026 WikG and LingWrite contributors
// SPDX-License-Identifier: AGPL-3.0-only
package com.lingwrite.app

import com.lingwrite.app.data.ai.*
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AdapterTest {
    private val req = ChatRequest("sys", listOf(ChatMessage("user", "hi")), "m")

    @Test fun openAiStreamsAndUsesCustomAuthHeader() = runBlocking {
        val s = MockWebServer()
        s.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream").setBody(
            "data: {\"choices\":[{\"delta\":{\"content\":\"你\"}}]}\n\ndata: {\"choices\":[{\"delta\":{\"content\":\"好\"}}]}\n\ndata: {\"choices\":[],\"usage\":{\"prompt_tokens\":5,\"completion_tokens\":2}}\n\ndata: [DONE]\n\n"))
        s.start()
        val ev = OpenAiCompatProvider(Endpoint("openai", s.url("/v1").toString(), "api-key", "k123")).stream(req).toList()
        val r = s.takeRequest()
        assertEquals("/v1/chat/completions", r.path)
        assertEquals("k123", r.getHeader("api-key"))
        assertTrue(r.body.readUtf8().contains("\"system\""))
        assertEquals("你好", ev.filterIsInstance<LlmEvent.Delta>().joinToString("") { it.text })
        assertEquals(LlmEvent.Done(5, 2), ev.last())
        s.shutdown()
    }

    @Test fun anthropicStreams() = runBlocking {
        val s = MockWebServer()
        s.enqueue(MockResponse().setBody(
            "event: message_start\ndata: {\"type\":\"message_start\",\"message\":{\"usage\":{\"input_tokens\":9}}}\n\n" +
            "event: content_block_delta\ndata: {\"type\":\"content_block_delta\",\"delta\":{\"type\":\"text_delta\",\"text\":\"剑\"}}\n\n" +
            "event: message_delta\ndata: {\"type\":\"message_delta\",\"usage\":{\"output_tokens\":1}}\n\n" +
            "event: message_stop\ndata: {\"type\":\"message_stop\"}\n\n"))
        s.start()
        val ev = AnthropicProvider(Endpoint("anthropic", s.url("/").toString().trimEnd('/'), "x-api-key", "ak")).stream(req).toList()
        val r = s.takeRequest()
        assertEquals("/v1/messages", r.path)
        assertEquals("ak", r.getHeader("x-api-key"))
        assertEquals("2023-06-01", r.getHeader("anthropic-version"))
        assertTrue(r.body.readUtf8().contains("\"max_tokens\""))
        assertEquals("剑", (ev.first() as LlmEvent.Delta).text)
        assertEquals(LlmEvent.Done(9, 1), ev.last())
        s.shutdown()
    }

    @Test fun errorIsFriendly() = runBlocking {
        val s = MockWebServer(); s.enqueue(MockResponse().setResponseCode(401).setBody("{}")); s.start()
        val ev = OpenAiCompatProvider(Endpoint("openai", s.url("/").toString(), "bearer", "x")).stream(req).toList()
        assertTrue((ev.single() as LlmEvent.Failed).message.contains("Key"))
        s.shutdown()
    }
}
