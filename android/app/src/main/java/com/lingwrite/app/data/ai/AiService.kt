// SPDX-FileCopyrightText: 2026 WikG and LingWrite contributors
// SPDX-License-Identifier: AGPL-3.0-only
package com.lingwrite.app.data.ai

import com.lingwrite.app.data.LingJson
import com.lingwrite.app.data.Prefs
import com.lingwrite.app.data.db.AiDao
import com.lingwrite.app.data.db.CallLogEntity
import com.lingwrite.app.data.db.ProviderEntity
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.*

/** 选中的模型槽位：写作 / 自动任务（自动任务未指定时回退写作模型）。 */
enum class Slot { Writing, Auto }

data class Resolved(val provider: ProviderEntity, val model: String, val endpoint: Endpoint)

sealed interface AiStream {
    data class Partial(val text: String) : AiStream
    data class Finished(val text: String, val logId: Long) : AiStream
    data class Error(val message: String) : AiStream
}

class AiService(private val dao: AiDao, private val prefs: Prefs) {
    fun providers() = dao.providers()

    suspend fun resolve(slot: Slot): Resolved? {
        val p = prefs.flow.first()
        val list = dao.providersOnce().filter { it.enabled && it.keyCipher.isNotBlank() }
        fun parse(ref: String): Resolved? {
            val id = ref.substringBefore('|').toLongOrNull() ?: return null
            val prov = list.firstOrNull { it.id == id } ?: return null
            return toResolved(prov, ref.substringAfter('|').ifBlank { prov.models.split(',').first() })
        }
        return (if (slot == Slot.Auto) parse(p.autoModel) else null) ?: parse(p.writingModel)
            ?: list.firstOrNull()?.let { toResolved(it, it.models.split(',').first().trim()) }
    }

    fun toResolved(p: ProviderEntity, model: String): Resolved? {
        val key = KeyVault.decrypt(p.keyCipher) ?: return null
        return Resolved(p, model, Endpoint(p.protocol, p.baseUrl, p.auth, key))
    }

    /** 统一调用入口：流式输出 + 记账（调用日志 / 用量）。 */
    fun stream(
        task: String, slot: Slot, system: String, messages: List<ChatMessage>, maxTokens: Int = 1500,
        bookId: Long? = null, chapterId: Long? = null, scope: String = "", temperature: Double = 0.85,
        outcome: String = "pending",
    ): Flow<AiStream> = flow {
        val r = resolve(slot)
        if (r == null) { emit(AiStream.Error("还没有可用的模型。去 设置 › 模型设置 添加服务商和 Key。")); return@flow }
        val sb = StringBuilder()
        var inT: Int? = null; var outT: Int? = null; var err: String? = null
        try { providerFor(r.endpoint).stream(ChatRequest(system, messages, r.model, maxTokens, temperature)).collect { e ->
            when (e) {
                is LlmEvent.Delta -> { sb.append(e.text); emit(AiStream.Partial(sb.toString())) }
                is LlmEvent.Done -> { inT = e.inputTokens; outT = e.outputTokens }
                is LlmEvent.Failed -> err = e.message
            }
        } } catch (e: kotlinx.coroutines.CancellationException) { throw e }
        catch (e: Exception) { err = "连接失败：${e.message ?: "服务未返回结果"}" }
        if (err == null && sb.isBlank()) err = "模型没有返回内容，请重试；原稿未改动"
        val promptChars = system.length + messages.sumOf { it.content.length }
        val log = CallLogEntity(
            bookId = bookId, chapterId = chapterId, provider = r.provider.name, model = r.model, task = task,
            inputTokens = inT ?: estimateTokens(promptChars), outputTokens = outT ?: estimateTokens(sb.length),
            estimated = inT == null, outcome = if (err != null) "failed" else outcome, scope = scope, error = err.orEmpty(),
        )
        val id = dao.insertLog(log)
        if (err != null) emit(AiStream.Error(err!!)) else emit(AiStream.Finished(sb.toString().trim(), id))
    }

    /** 非交互调用（自动任务），返回完整文本。 */
    suspend fun complete(task: String, system: String, user: String, bookId: Long? = null, chapterId: Long? = null, maxTokens: Int = 1500, slot: Slot = Slot.Auto): Result<String> {
        var out: Result<String> = Result.failure(IllegalStateException("无结果"))
        stream(task, slot, system, listOf(ChatMessage("user", user)), maxTokens, bookId, chapterId, temperature = 0.3, outcome = "auto").collect {
            when (it) { is AiStream.Finished -> out = Result.success(it.text); is AiStream.Error -> out = Result.failure(IllegalStateException(it.message)); else -> {} }
        }
        return out
    }

    /** 多候选事件（A3）：每条候选单独带下标；后面的候选失败不打断前面的。 */
    sealed interface AiMulti {
        data class Partial(val index: Int, val text: String) : AiMulti
        data class Done(val index: Int, val text: String, val logId: Long) : AiMulti
        data class Finished(val ok: Int) : AiMulti
        data class Failed(val index: Int, val message: String) : AiMulti
    }

    /**
     * 顺序发起 [n] 次流式请求生成多候选（A3）。第 1 条失败直接报错；
     * 第 2 条起在提示词末尾追加“给出与前面不同的写法”，让候选之间有差异。
     *
     * 关于 `n` 参数（`ProviderEntity.supportsN`）：**有意不做，只走顺序生成**。原因：
     * 1. 多数 OpenAI 兼容网关对 `n>1` 的流式返回不按 `choices[i].index` 分流，解析不可靠；
     * 2. 顺序生成对 OpenAI 兼容 / Anthropic 两种协议都成立，且每条候选各自写 call_logs，
     *    采纳率统计（F4）拿得到准确的 chosen 下标；
     * 3. 顺序生成可以叠加“与前面不同”的提示，候选差异比同一次采样更大。
     * 代价：N 条候选按 N 次计费、更慢——发起前会在 UI 上说明。
     * 如果以后要接 `n` 参数，只需在 provider 能力里加 `supportsN` 并在这里分支，调用方无需改。
     */
    fun streamMany(
        task: String, slot: Slot, system: String, messages: List<ChatMessage>, n: Int,
        maxTokens: Int = 1500, bookId: Long? = null, chapterId: Long? = null, scope: String = "", temperature: Double = 0.85,
    ): Flow<AiMulti> = flow {
        var prev = ""
        var ok = 0
        for (i in 0 until n.coerceAtLeast(1)) {
            val msgs = if (i == 0 || prev.isBlank()) messages else messages.dropLast(1) +
                ChatMessage(messages.last().role, messages.last().content + "\n\n（请给出与前面不同的写法，不要重复：“${prev.take(40)}…”）")
            var failed: String? = null
            var result: AiStream.Finished? = null
            stream(task, slot, system, msgs, maxTokens, bookId, chapterId, scope = scope, temperature = temperature + i * 0.05).collect { e ->
                when (e) {
                    is AiStream.Partial -> emit(AiMulti.Partial(i, e.text))
                    is AiStream.Finished -> result = e
                    is AiStream.Error -> failed = e.message
                }
            }
            val r = result
            if (r != null) { ok++; prev = r.text; emit(AiMulti.Done(i, r.text, r.logId)) }
            else {
                val msg = failed ?: "没有返回内容"
                if (i == 0) { emit(AiMulti.Failed(0, msg)); return@flow }
                emit(AiMulti.Failed(i, msg))
            }
        }
        emit(AiMulti.Finished(ok))
    }

    /** 语义检索用的向量；模型为 prefs.embedModel（"providerId|model"）。 */
    suspend fun embed(texts: List<String>, bookId: Long? = null): Result<List<FloatArray>> = runCatching {
        val ref = prefs.flow.first().embedModel
        val id = ref.substringBefore('|').toLongOrNull() ?: error("还没选向量模型")
        val prov = dao.provider(id) ?: error("向量模型的服务商不存在")
        val r = toResolved(prov, ref.substringAfter('|')) ?: error("Key 读取失败")
        val (v, tok) = providerFor(r.endpoint).embed(r.model, texts)
        dao.insertLog(CallLogEntity(bookId = bookId, provider = prov.name, model = r.model, task = "embed", inputTokens = tok ?: estimateTokens(texts.sumOf { it.length }), estimated = tok == null, outcome = "auto"))
        v
    }

    suspend fun setOutcome(logId: Long, outcome: String) = dao.setOutcome(logId, outcome)

    /** 测试连接：发一条极短请求。 */
    suspend fun test(p: ProviderEntity, model: String): Result<String> {
        val r = toResolved(p, model) ?: return Result.failure(IllegalStateException("Key 读取失败，请重新填写"))
        var res: Result<String> = Result.failure(IllegalStateException("没有收到回复"))
        val t0 = System.currentTimeMillis()
        providerFor(r.endpoint).stream(ChatRequest("", listOf(ChatMessage("user", "回复“好”。")), model, 16, 0.0)).collect { e ->
            when (e) {
                is LlmEvent.Delta -> res = Result.success("连接正常 · ${System.currentTimeMillis() - t0} ms")
                is LlmEvent.Failed -> res = Result.failure(IllegalStateException(e.message))
                is LlmEvent.Done -> if (res.isFailure && res.exceptionOrNull()?.message == "没有收到回复") res = Result.success("连接正常 · ${System.currentTimeMillis() - t0} ms")
            }
        }
        dao.insertLog(CallLogEntity(provider = p.name, model = model, task = "test", outcome = if (res.isSuccess) "auto" else "failed", error = res.exceptionOrNull()?.message.orEmpty()))
        dao.updateProvider(p.copy(lastTest = if (res.isSuccess) "ok" else "fail"))
        return res
    }

    suspend fun listModels(p: ProviderEntity): Result<List<String>> = runCatching {
        val r = toResolved(p, "") ?: error("Key 读取失败")
        providerFor(r.endpoint).listModels()
    }

    companion object {
        /** 中文为主时约 1.6 字符 / token 的保守估算 */
        fun estimateTokens(chars: Int) = (chars / 1.6).toInt() + 1

        fun prices(json: String): Map<String, Pair<Double, Double>> = runCatching {
            LingJson.parseToJsonElement(json).jsonObject.mapValues { (_, v) -> v.jsonArray.let { it[0].jsonPrimitive.double to it[1].jsonPrimitive.double } }
        }.getOrDefault(emptyMap())

        fun encodePrices(m: Map<String, Pair<Double, Double>>) = buildJsonObject { m.forEach { (k, v) -> putJsonArray(k) { add(v.first); add(v.second) } } }.toString()
    }
}
