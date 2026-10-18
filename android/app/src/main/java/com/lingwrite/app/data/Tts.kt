// SPDX-FileCopyrightText: 2026 WikG and LingWrite contributors
// SPDX-License-Identifier: AGPL-3.0-only
package com.lingwrite.app.data

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.Locale
import java.util.UUID

/** 听稿：把正文按句子切成短段，交给系统 TTS 顺序朗读；音频不落盘。 */
object TtsChunks {
    /** 按句号/换行切句，再合并成不超过 [max] 字的段，避免单次 utterance 过长。 */
    fun split(text: String, max: Int = 180): List<String> {
        if (text.isBlank()) return emptyList()
        val out = ArrayList<String>()
        val sb = StringBuilder()
        for ((_, sentence) in sentences(text)) {
            if (sentence.isBlank()) continue
            if (sb.isNotEmpty() && sb.length + sentence.length > max) {
                out += sb.toString().trim()
                sb.clear()
            }
            if (sentence.length > max) {
                sentence.chunked(max).forEach { out += it }
            } else {
                sb.append(sentence)
            }
        }
        if (sb.isNotBlank()) out += sb.toString().trim()
        return out.filter { it.isNotBlank() }
    }
}

/**
 * 朗读队列的状态：一次 [begin] 会排入多段 utterance，只有**最后一段**结束（或出错 / 被 [clear]）才算读完。
 * 纯逻辑，便于单测；[TtsEngine] 在 TTS 回调线程里调用。
 */
class TtsQueueState {
    @Volatile private var lastId: String? = null
    @Volatile var speaking: Boolean = false; private set

    /** 开始一批朗读；返回各段的 utteranceId。 */
    fun begin(count: Int, idOf: (Int) -> String = { UUID.randomUUID().toString() }): List<String> {
        val ids = List(count) { idOf(it) }
        lastId = ids.lastOrNull()
        speaking = ids.isNotEmpty()
        return ids
    }

    /** 任何一段开始都算在读（可能是 QUEUE_FLUSH 后的新一批）。 */
    fun onStart(id: String?) { if (id != null && lastId != null) speaking = true }

    /** 段结束；返回 true 表示整批读完（应释放音频焦点）。旧批次的回调忽略。 */
    fun onFinish(id: String?): Boolean {
        if (id == null || id != lastId) return false
        lastId = null; speaking = false
        return true
    }

    fun clear() { lastId = null; speaking = false }
}

/** 朗读语言：按作品语言选 Locale；不支持时退回系统语言。 */
object TtsLang {
    fun localeFor(tag: String?): Locale = when (tag) {
        null, "", "zh-CN" -> Locale.SIMPLIFIED_CHINESE
        "zh-TW" -> Locale.TRADITIONAL_CHINESE
        "en" -> Locale.US
        "ja" -> Locale.JAPAN
        else -> Locale.forLanguageTag(tag)
    }

    /** 依次尝试的 Locale（去重）：作品语言 → 系统默认。 */
    fun candidates(tag: String?, systemDefault: Locale = Locale.getDefault()): List<Locale> =
        listOf(localeFor(tag), systemDefault).distinct()
}

/**
 * 系统 TTS 封装。初始化异步；[ready]/[speaking]/[error] 给 Compose 收集。
 * 系统语音引擎可能联网，由系统设置决定；App 不保存音频。
 *
 * - 正文切成多段排队朗读，只有最后一段结束才回到「播放」并释放音频焦点（[TtsQueueState]）。
 * - 语言由 [setLanguage] 按作品语言设置，不支持时退回系统语言。
 * - 音频焦点永久或短暂丢失（来电、导航播报）时停止朗读；可降低音量（duck）时继续。
 */
class TtsEngine(ctx: Context) {
    private val app = ctx.applicationContext
    private val audio = app.getSystemService(AudioManager::class.java)
    private var focus: AudioFocusRequest? = null
    private var tts: TextToSpeech? = null
    private val queue = TtsQueueState()
    @Volatile private var initialized = false
    @Volatile private var langTag: String? = null
    private val _ready = MutableStateFlow(false)
    private val _speaking = MutableStateFlow(false)
    private val _error = MutableStateFlow<String?>(null)
    val ready: StateFlow<Boolean> = _ready
    val speaking: StateFlow<Boolean> = _speaking
    val error: StateFlow<String?> = _error

    init {
        tts = TextToSpeech(app) { status ->
            if (status != TextToSpeech.SUCCESS) {
                _error.value = "init"
                return@TextToSpeech
            }
            val t = tts ?: return@TextToSpeech
            t.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) { queue.onStart(utteranceId); _speaking.value = queue.speaking }
                override fun onDone(utteranceId: String?) { finish(utteranceId) }
                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) { finish(utteranceId) }
                override fun onError(utteranceId: String?, errorCode: Int) { finish(utteranceId) }
                override fun onStop(utteranceId: String?, interrupted: Boolean) { finish(utteranceId) }
            })
            initialized = true
            applyLanguage()
        }
    }

    private fun finish(id: String?) {
        if (queue.onFinish(id)) { _speaking.value = false; abandonFocus() }
    }

    /** 作品语言（"zh-CN" / "zh-TW" / "en" / "ja"，null = 简体中文）；初始化前后调用都可以。 */
    fun setLanguage(tag: String?) {
        langTag = tag
        if (initialized) applyLanguage()
    }

    private fun applyLanguage() {
        val t = tts ?: return
        val ok = TtsLang.candidates(langTag).any { loc ->
            val r = runCatching { t.setLanguage(loc) }.getOrDefault(TextToSpeech.LANG_NOT_SUPPORTED)
            r != TextToSpeech.LANG_MISSING_DATA && r != TextToSpeech.LANG_NOT_SUPPORTED
        }
        if (ok) { _error.value = null; _ready.value = true } else { _ready.value = false; _error.value = "language" }
    }

    fun speak(text: String, from: Int = 0, rate: Float = 1f): Boolean {
        val t = tts ?: return false
        if (!_ready.value) return false
        val body = text.substring(from.coerceIn(0, text.length))
        val chunks = TtsChunks.split(body)
        if (chunks.isEmpty()) return false
        if (!requestFocus()) return false
        t.setSpeechRate(rate.coerceIn(0.5f, 2.0f))
        val ids = queue.begin(chunks.size)
        chunks.forEachIndexed { i, chunk ->
            t.speak(chunk, if (i == 0) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD, null, ids[i])
        }
        _speaking.value = true
        return true
    }

    fun stop() {
        queue.clear()
        tts?.stop()
        _speaking.value = false
        abandonFocus()
    }

    fun shutdown() {
        stop()
        tts?.shutdown()
        tts = null
    }

    private val focusListener = AudioManager.OnAudioFocusChangeListener { change ->
        when (change) {
            AudioManager.AUDIOFOCUS_LOSS, AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> stop()
        }
    }

    private fun requestFocus(): Boolean {
        val a = audio ?: return true
        if (focus == null) {
            focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                .setOnAudioFocusChangeListener(focusListener)
                .build()
        }
        // 通话中等情况拿不到焦点：不朗读。
        return focus?.let { a.requestAudioFocus(it) != AudioManager.AUDIOFOCUS_REQUEST_FAILED } ?: true
    }

    private fun abandonFocus() { focus?.let { audio?.abandonAudioFocusRequest(it) } }
}
