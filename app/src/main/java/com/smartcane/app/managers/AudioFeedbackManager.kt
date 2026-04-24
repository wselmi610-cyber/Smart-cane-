package com.smartcane.app.managers

import android.content.Context
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import java.util.Locale
import java.util.UUID

enum class SpeechPriority {
    CRITICAL,
    HIGH,
    NORMAL,
    LOW
}

class AudioFeedbackManager(
    private val context: Context,
    private val talkBackDetector: TalkBackDetector
) {
    private var tts: TextToSpeech? = null
    private var isReady = false
    private val messageQueue = ArrayDeque<Pair<String, SpeechPriority>>()
    private var isSpeaking = false

    companion object {
        private const val TAG = "AudioFeedbackManager"
    }

    init {
        initializeTTS()
    }

    private fun initializeTTS() {
        tts = TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) {
                val result = tts?.setLanguage(Locale.getDefault())
                if (result == TextToSpeech.LANG_MISSING_DATA ||
                    result == TextToSpeech.LANG_NOT_SUPPORTED
                ) {
                    tts?.setLanguage(Locale.US)
                }
                isReady = true
                setupUtteranceListener()
                Log.d(TAG, "TTS initialized successfully")
            } else {
                Log.e(TAG, "TTS initialization failed")
            }
        }
    }

    private fun setupUtteranceListener() {
        tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) { isSpeaking = true }
            override fun onDone(utteranceId: String?) {
                isSpeaking = false
                processQueue()
            }
            override fun onError(utteranceId: String?) {
                isSpeaking = false
                processQueue()
            }
        })
    }

    fun speak(text: String, priority: SpeechPriority = SpeechPriority.NORMAL) {
        if (!isReady) {
            messageQueue.addLast(Pair(text, priority))
            return
        }
        when {
            talkBackDetector.isTalkBackActive() -> {
                if (priority == SpeechPriority.CRITICAL) speakImmediately(text)
            }
            priority == SpeechPriority.CRITICAL -> {
                clearQueue()
                speakImmediately(text)
            }
            priority == SpeechPriority.HIGH -> {
                messageQueue.addFirst(Pair(text, priority))
                if (!isSpeaking) processQueue()
            }
            else -> {
                if (priority == SpeechPriority.LOW && messageQueue.size > 2) return
                messageQueue.addLast(Pair(text, priority))
                if (!isSpeaking) processQueue()
            }
        }
    }

    private fun speakImmediately(text: String) {
        val params = Bundle()
        params.putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, 1.0f)
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, params, UUID.randomUUID().toString())
        isSpeaking = true
    }

    private fun processQueue() {
        if (messageQueue.isEmpty()) { isSpeaking = false; return }
        val (text, _) = messageQueue.removeFirst()
        val params = Bundle()
        params.putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, 1.0f)
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, params, UUID.randomUUID().toString())
    }

    private fun clearQueue() {
        messageQueue.clear()
        tts?.stop()
    }

    fun stopSpeaking() {
        clearQueue()
        tts?.stop()
        isSpeaking = false
    }
    fun isSpeaking(): Boolean = isSpeaking
    fun setSpeechRate(rate: Float) { tts?.setSpeechRate(rate) }

    fun shutdown() {
        clearQueue()
        tts?.stop()
        tts?.shutdown()
        tts = null
        isReady = false
    }
}