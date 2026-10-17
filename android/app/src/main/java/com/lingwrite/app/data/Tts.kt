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
 * 系统 TTS 封装。初始化异步；[ready]/[speaking]/[error] 给 Compose 收集。
 * 系统语音引擎可能联网，由系统设置决定；App 不保存音频。
 */
class TtsEngine(ctx: Context) {
    private val app = ctx.applicationContext
    private val audio = app.getSystemService(AudioManager::class.java)
    private var focus: AudioFocusRequest? = null
    private var tts: TextToSpeech? = null
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
            val lang = t.setLanguage(Locale.SIMPLIFIED_CHINESE)
            if (lang == TextToSpeech.LANG_MISSING_DATA || lang == TextToSpeech.LANG_NOT_SUPPORTED) {
                _error.value = "language"
            } else {
                _ready.value = true
            }
            t.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) { _speaking.value = true }
                override fun onDone(utteranceId: String?) { _speaking.value = false; abandonFocus() }
                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) { _speaking.value = false; abandonFocus() }
            })
        }
    }

    fun speak(text: String, from: Int = 0, rate: Float = 1f): Boolean {
        val t = tts ?: return false
        if (!_ready.value) return false
        val body = text.substring(from.coerceIn(0, text.length))
        val chunks = TtsChunks.split(body)
        if (chunks.isEmpty()) return false
        requestFocus()
        t.setSpeechRate(rate.coerceIn(0.5f, 2.0f))
        t.stop()
        chunks.forEachIndexed { i, chunk ->
            t.speak(chunk, if (i == 0) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD, null, UUID.randomUUID().toString())
        }
        _speaking.value = true
        return true
    }

    fun stop() {
        tts?.stop()
        _speaking.value = false
        abandonFocus()
    }

    fun shutdown() {
        stop()
        tts?.shutdown()
        tts = null
    }

    private fun requestFocus() {
        val a = audio ?: return
        if (focus == null) {
            focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                .build()
        }
        focus?.let { a.requestAudioFocus(it) }
    }

    private fun abandonFocus() { focus?.let { audio?.abandonAudioFocusRequest(it) } }
}
