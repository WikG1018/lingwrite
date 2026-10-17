// SPDX-FileCopyrightText: 2026 WikG and LingWrite contributors
// SPDX-License-Identifier: AGPL-3.0-only
package com.lingwrite.app.data.ai

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.channels.trySendBlocking
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

data class ChatMessage(val role: String, val content: String)
data class ChatRequest(val system: String, val messages: List<ChatMessage>, val model: String, val maxTokens: Int = 2048, val temperature: Double = 0.85)

sealed interface LlmEvent {
    data class Delta(val text: String) : LlmEvent
    data class Done(val inputTokens: Int?, val outputTokens: Int?) : LlmEvent
    data class Failed(val message: String, val code: Int? = null) : LlmEvent
}

data class Endpoint(val protocol: String, val baseUrl: String, val auth: String, val key: String)

/** 两个适配器共用的接口：统一的流式文本与错误。 */
interface LlmProvider {
    fun stream(req: ChatRequest): Flow<LlmEvent>
    suspend fun listModels(): List<String> = emptyList()
    /** 向量接口（OpenAI 兼容 /embeddings）。 */
    suspend fun embed(model: String, inputs: List<String>): Pair<List<FloatArray>, Int?> = throw IOException("这个服务商不支持向量接口")
}

object Http {
    val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS).readTimeout(120, TimeUnit.SECONDS).callTimeout(0, TimeUnit.SECONDS).build()
    val JSON = "application/json; charset=utf-8".toMediaType()
}

fun providerFor(e: Endpoint): LlmProvider = FakeLlmRegistry.provider ?: if (e.protocol == "anthropic") AnthropicProvider(e) else OpenAiCompatProvider(e)

/** 测试注入点：非空时所有调用走这个假 provider（不经过网络）。 */
object FakeLlmRegistry {
    var provider: LlmProvider? = null
}

/** 把错误码翻译成作者看得懂的话（不包含 Key）。 */
fun friendlyError(code: Int?, body: String): String = when (code) {
    401, 403 -> "Key 无效或没有权限（$code）"
    404 -> "接口地址或模型名不对（404）"
    429 -> "请求太频繁或额度用完了（429）"
    in 500..599 -> "服务商暂时出错（$code），稍后再试"
    null -> "连接失败：$body"
    else -> "请求失败（$code）：" + body.take(160)
}

private fun Request.Builder.auth(e: Endpoint): Request.Builder = when (e.auth) {
    "x-api-key" -> header("x-api-key", e.key)
    "api-key" -> header("api-key", e.key)
    else -> header("Authorization", "Bearer ${e.key}")
}

/** 逐行读 SSE，回调 data 字段。 */
private fun sseLines(body: ResponseBody, onData: (event: String?, data: String) -> Boolean) {
    val src = body.source()
    var event: String? = null
    while (!src.exhausted()) {
        val line = src.readUtf8Line() ?: break
        when {
            line.startsWith("event:") -> event = line.removePrefix("event:").trim()
            line.startsWith("data:") -> { if (!onData(event, line.removePrefix("data:").trim())) return }
            line.isEmpty() -> event = null
        }
    }
}

class OpenAiCompatProvider(private val e: Endpoint) : LlmProvider {
    private val base = e.baseUrl.trimEnd('/').removeSuffix("/chat/completions")

    override fun stream(req: ChatRequest): Flow<LlmEvent> = callbackFlow {
        val body = buildJsonObject {
            put("model", req.model); put("stream", true); put("max_tokens", req.maxTokens); put("temperature", req.temperature)
            putJsonObject("stream_options") { put("include_usage", true) }
            putJsonArray("messages") {
                if (req.system.isNotBlank()) addJsonObject { put("role", "system"); put("content", req.system) }
                req.messages.forEach { m -> addJsonObject { put("role", m.role); put("content", m.content) } }
            }
        }.toString()
        val call = Http.client.newCall(Request.Builder().url("$base/chat/completions").auth(e).header("Accept", "text/event-stream").post(body.toRequestBody(Http.JSON)).build())
        coroutineContext[kotlinx.coroutines.Job]?.invokeOnCompletion { call.cancel() }
        try {
            call.execute().use { r ->
                if (!r.isSuccessful) { trySendBlocking(LlmEvent.Failed(friendlyError(r.code, r.body?.string().orEmpty()), r.code)); return@use }
                var inT: Int? = null; var outT: Int? = null
                sseLines(r.body!!) { _, d ->
                    if (d == "[DONE]") return@sseLines false
                    val o = runCatching { Json.parseToJsonElement(d).jsonObject }.getOrNull() ?: return@sseLines true
                    // 部分兼容网关会以 200 + error 帧返回失败；明确转成 Failed，而不是丢光已生成内容后只报「没有返回内容」。
                    val errorObj = o["error"] as? JsonObject
                    if (errorObj != null) {
                        val msg = (errorObj["message"] as? JsonPrimitive)?.contentOrNull ?: errorObj.toString()
                        trySendBlocking(LlmEvent.Failed(friendlyError(null, msg)))
                        return@sseLines false
                    }
                    // choices 缺失 / 为 null / 非数组时跳过这一帧，不能抛异常把整段生成判失败。
                    val delta = (o["choices"] as? JsonArray)?.firstOrNull()?.let { it as? JsonObject }?.get("delta") as? JsonObject
                    ((delta?.get("content") as? JsonPrimitive)?.contentOrNull)?.let { if (it.isNotEmpty()) trySendBlocking(LlmEvent.Delta(it)) }
                    (o["usage"] as? JsonObject)?.let { u -> inT = (u["prompt_tokens"] as? JsonPrimitive)?.intOrNull; outT = (u["completion_tokens"] as? JsonPrimitive)?.intOrNull }
                    true
                }
                trySendBlocking(LlmEvent.Done(inT, outT))
            }
        } catch (ex: kotlinx.coroutines.CancellationException) {
            throw ex
        } catch (ex: Exception) {
            if (!call.isCanceled()) trySendBlocking(LlmEvent.Failed(friendlyError(null, ex.message ?: ex.javaClass.simpleName)))
        }
        close()
        awaitClose { call.cancel() }
    }.flowOn(Dispatchers.IO)

    override suspend fun embed(model: String, inputs: List<String>): Pair<List<FloatArray>, Int?> = kotlinx.coroutines.withContext(Dispatchers.IO) {
        val body = buildJsonObject { put("model", model); putJsonArray("input") { inputs.forEach { add(it) } } }.toString()
        Http.client.newCall(Request.Builder().url("$base/embeddings").auth(e).post(body.toRequestBody(Http.JSON)).build()).execute().use { r ->
            if (!r.isSuccessful) throw IOException(friendlyError(r.code, r.body?.string().orEmpty()))
            val o = Json.parseToJsonElement(r.body!!.string()).jsonObject
            val vecs = o["data"]!!.jsonArray.sortedBy { it.jsonObject["index"]?.jsonPrimitive?.intOrNull ?: 0 }
                .map { d -> d.jsonObject["embedding"]!!.jsonArray.map { it.jsonPrimitive.float }.toFloatArray() }
            vecs to (o["usage"] as? JsonObject)?.get("prompt_tokens")?.jsonPrimitive?.intOrNull
        }
    }

    override suspend fun listModels(): List<String> = kotlinx.coroutines.withContext(Dispatchers.IO) {
        Http.client.newCall(Request.Builder().url("$base/models").auth(e).get().build()).execute().use { r ->
            if (!r.isSuccessful) throw IOException(friendlyError(r.code, r.body?.string().orEmpty()))
            Json.parseToJsonElement(r.body!!.string()).jsonObject["data"]?.jsonArray?.mapNotNull { it.jsonObject["id"]?.jsonPrimitive?.contentOrNull }?.sorted().orEmpty()
        }
    }
}

class AnthropicProvider(private val e: Endpoint) : LlmProvider {
    private val url = e.baseUrl.trimEnd('/').let { b ->
        when { b.endsWith("/messages") -> b; b.endsWith("/v1") -> "$b/messages"; else -> "$b/v1/messages" }
    }

    override fun stream(req: ChatRequest): Flow<LlmEvent> = callbackFlow {
        val body = buildJsonObject {
            put("model", req.model); put("stream", true); put("max_tokens", req.maxTokens); put("temperature", req.temperature.coerceAtMost(1.0))
            if (req.system.isNotBlank()) put("system", req.system)
            putJsonArray("messages") { req.messages.forEach { m -> addJsonObject { put("role", m.role); put("content", m.content) } } }
        }.toString()
        val rb = Request.Builder().url(url).header("anthropic-version", "2023-06-01").header("Accept", "text/event-stream")
        val call = Http.client.newCall(rb.auth(e.copy(auth = if (e.auth == "bearer") "bearer" else "x-api-key")).post(body.toRequestBody(Http.JSON)).build())
        coroutineContext[kotlinx.coroutines.Job]?.invokeOnCompletion { call.cancel() }
        try {
            call.execute().use { r ->
                if (!r.isSuccessful) { trySendBlocking(LlmEvent.Failed(friendlyError(r.code, r.body?.string().orEmpty()), r.code)); return@use }
                var inT: Int? = null; var outT: Int? = null
                sseLines(r.body!!) { _, d ->
                    val o = runCatching { Json.parseToJsonElement(d).jsonObject }.getOrNull() ?: return@sseLines true
                    val type = (o["type"] as? JsonPrimitive)?.contentOrNull
                    when (type) {
                        "message_start" -> inT = ((o["message"] as? JsonObject)?.get("usage") as? JsonObject)?.get("input_tokens")?.let { (it as? JsonPrimitive)?.intOrNull }
                        "content_block_delta" -> (((o["delta"] as? JsonObject)?.get("text")) as? JsonPrimitive)?.contentOrNull?.let { trySendBlocking(LlmEvent.Delta(it)) }
                        "message_delta" -> outT = (((o["usage"] as? JsonObject)?.get("output_tokens")) as? JsonPrimitive)?.intOrNull ?: outT
                        "error" -> {
                            val msg = (((o["error"] as? JsonObject)?.get("message")) as? JsonPrimitive)?.contentOrNull ?: "服务商返回错误"
                            trySendBlocking(LlmEvent.Failed(msg)); return@sseLines false
                        }
                        "message_stop" -> return@sseLines false
                    }
                    true
                }
                trySendBlocking(LlmEvent.Done(inT, outT))
            }
        } catch (ex: kotlinx.coroutines.CancellationException) {
            throw ex
        } catch (ex: Exception) {
            if (!call.isCanceled()) trySendBlocking(LlmEvent.Failed(friendlyError(null, ex.message ?: ex.javaClass.simpleName)))
        }
        close()
        awaitClose { call.cancel() }
    }.flowOn(Dispatchers.IO)
}
