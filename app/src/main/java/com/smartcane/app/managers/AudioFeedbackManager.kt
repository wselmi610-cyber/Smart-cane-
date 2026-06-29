package com.smartcane.app.managers

import android.content.Context
import android.media.AudioAttributes
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import java.util.Locale
import java.util.UUID


enum class SpeechPriority { CRITICAL, HIGH, NORMAL, LOW }

class AudioFeedbackManager(
    private val context: Context,
    private val talkBackDetector: TalkBackDetector
) {
    private var tts: TextToSpeech? = null
    private var isReady = false
    private var isSpeaking = false
    private var isInitializing = false
    private val messageQueue = ArrayDeque<Pair<String, SpeechPriority>>()

    companion object {
        private const val TAG = "AudioFeedbackManager"
        private const val TTS_ENGINE = "com.google.android.tts"
    }

    init {
        initializeTTS()
    }

    // ─────────────────────────────────────────────────────────────────
    // Init
    // ─────────────────────────────────────────────────────────────────

    private fun initializeTTS() {
        // Prevent double initialization
        if (isInitializing) {
            Log.d(TAG, "Already initializing — skipping")
            return
        }
        isInitializing = true
        isReady = false

        Log.d(TAG, "Starting TTS init...")

        tts = TextToSpeech(context, { status ->
            isInitializing = false

            if (status == TextToSpeech.SUCCESS) {
                val langResult = tts?.setLanguage(Locale.getDefault())
                if (langResult == TextToSpeech.LANG_MISSING_DATA ||
                    langResult == TextToSpeech.LANG_NOT_SUPPORTED
                ) {
                    tts?.setLanguage(Locale.US)
                }

                tts?.setSpeechRate(0.9f)

                // Use ALARM stream — bypasses silent mode
                tts?.setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )

                setupUtteranceListener()
                isReady = true
                Log.d(TAG, "TTS initialized successfully")

                // Play any queued messages
                android.os.Handler(android.os.Looper.getMainLooper()).post {
                    processQueue()
                }

            } else {
                Log.e(TAG, "TTS init failed status=$status — retry in 2s")
                android.os.Handler(android.os.Looper.getMainLooper())
                    .postDelayed({ initializeTTS() }, 2000)
            }
        }, TTS_ENGINE)
    }

    private fun setupUtteranceListener() {
        tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {
                android.os.Handler(android.os.Looper.getMainLooper()).post {
                    isSpeaking = true
                    // Cancel watchdog — onStart fired, audio is alive
                    utteranceTimeoutHandler.removeCallbacksAndMessages(null)
                    Log.d(TAG, "onStart utteranceId=$utteranceId ✅")
                }
            }

            override fun onDone(utteranceId: String?) {
                android.os.Handler(android.os.Looper.getMainLooper()).post {
                    isSpeaking = false
                    Log.d(TAG, "onDone utteranceId=$utteranceId")
                    processQueue()
                }
            }

            override fun onError(utteranceId: String?) {
                android.os.Handler(android.os.Looper.getMainLooper()).post {
                    isSpeaking = false
                    Log.d(TAG, "onError utteranceId=$utteranceId")
                    processQueue()
                }
            }
        })
    }


    // ─────────────────────────────────────────────────────────────────
    // Public API
    // ─────────────────────────────────────────────────────────────────

    fun speak(text: String, priority: SpeechPriority = SpeechPriority.NORMAL) {
        Log.d(TAG, "speak() called: '$text' isReady=$isReady isSpeaking=$isSpeaking")

        if (!isReady) {
            messageQueue.addLast(Pair(text, priority))
            Log.d(TAG, "Queued (not ready): '$text'")
            return
        }

        when {
            talkBackDetector.isTalkBackActive() -> {
                if (priority == SpeechPriority.CRITICAL) {
                    Log.d(TAG, "TalkBack active — speaking CRITICAL only")
                    speakNow(text)
                }
            }
            priority == SpeechPriority.CRITICAL -> {
                Log.d(TAG, "CRITICAL priority — flushing queue")
                messageQueue.clear()
                utteranceTimeoutHandler.removeCallbacksAndMessages(null)
                tts?.stop()
                isSpeaking = false
                speakNow(text)
            }
            priority == SpeechPriority.HIGH -> {
                Log.d(TAG, "HIGH priority — adding to front")
                messageQueue.addFirst(Pair(text, priority))
                if (!isSpeaking) processQueue()
            }
            else -> {
                Log.d(TAG, "NORMAL/LOW priority — isSpeaking=$isSpeaking queueSize=${messageQueue.size}")
                if (priority == SpeechPriority.LOW && messageQueue.size > 2) {
                    Log.d(TAG, "LOW priority dropped — queue full")
                    return
                }
                messageQueue.addLast(Pair(text, priority))
                Log.d(TAG, "Added to queue. Queue size=${messageQueue.size}")
                if (!isSpeaking) {
                    Log.d(TAG, "Not speaking — calling processQueue()")
                    processQueue()
                } else {
                    Log.d(TAG, "Already speaking — message queued for later")
                }
            }
        }
    }

    fun stopSpeaking() {
        messageQueue.clear()
        tts?.stop()
        isSpeaking = false
    }
    fun forceReset() {
        Log.d(TAG, "forceReset() called")
        try {
            tts?.stop()
        } catch (e: Exception) { }
        isSpeaking = false
        messageQueue.clear() // clear stale queue from before settings
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            if (isReady) {
                Log.d(TAG, "forceReset — TTS ready after reset")
            } else {
                Log.d(TAG, "forceReset — TTS not ready, reinitializing")
                reinitialize()
            }
        }, 300L)
    }


    fun isSpeaking(): Boolean = isSpeaking

    fun isReady(): Boolean = isReady

    fun setSpeechRate(rate: Float) {
        tts?.setSpeechRate(rate)
    }

    fun reinitialize() {
        Log.d(TAG, "Reinitializing TTS...")
        if (isInitializing) {
            Log.d(TAG, "Already initializing — skip reinitialize")
            return
        }
        // Only reinitialize if truly dead
        if (isReady && tts != null) {
            Log.d(TAG, "TTS already ready — skip reinitialize")
            return
        }
        try {
            tts?.stop()
            tts?.shutdown()
            tts = null
        } catch (e: Exception) {
            Log.e(TAG, "Shutdown error: ${e.message}")
        }
        isReady = false
        isSpeaking = false
        android.os.Handler(android.os.Looper.getMainLooper())
            .postDelayed({ initializeTTS() }, 500)
    }

    fun shutdown() {
        messageQueue.clear()
        try {
            tts?.stop()
            tts?.shutdown()
            tts = null
        } catch (e: Exception) {
            Log.e(TAG, "Shutdown error: ${e.message}")
        }
        isReady = false
        isSpeaking = false
    }

    // ─────────────────────────────────────────────────────────────────
    // Internal
    // ─────────────────────────────────────────────────────────────────

    private fun speakNow(text: String) {
        val params = Bundle().apply {
            putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, 1.0f)
        }
        val utteranceId = UUID.randomUUID().toString()
        val result = tts?.speak(
            text,
            TextToSpeech.QUEUE_FLUSH,
            params,
            utteranceId
        )
        Log.d(TAG, "speakNow result=$result utteranceId=$utteranceId")
        if (result == TextToSpeech.SUCCESS) {
            isSpeaking = true
        }
    }

    private var lastUtteranceId: String? = null
    private var utteranceTimeoutHandler = android.os.Handler(android.os.Looper.getMainLooper())

    private fun processQueue() {
        if (!isReady || isSpeaking || messageQueue.isEmpty()) {
            if (messageQueue.isEmpty()) isSpeaking = false
            return
        }
        val (text, _) = messageQueue.removeFirst()
        val params = Bundle().apply {
            putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, 1.0f)
        }
        val utteranceId = UUID.randomUUID().toString()
        lastUtteranceId = utteranceId

        val result = tts?.speak(
            text,
            TextToSpeech.QUEUE_FLUSH,
            params,
            utteranceId
        )
        Log.d(TAG, "processQueue result=$result utteranceId=$utteranceId text='$text'")

        if (result == TextToSpeech.SUCCESS) {
            isSpeaking = true

            // Watchdog — if onStart doesn't fire in 2s, TTS audio is dead
            utteranceTimeoutHandler.removeCallbacksAndMessages(null)
            utteranceTimeoutHandler.postDelayed({
                if (lastUtteranceId == utteranceId && isSpeaking) {
                    Log.e(TAG, "❌ onStart never fired — TTS audio dead — hard reset")
                    hardReset(text)
                }
            }, 2000L)
        } else {
            Log.e(TAG, "TTS speak failed result=$result")
            isReady = false
            isSpeaking = false
            reinitialize()
        }
    }
    private fun hardReset(pendingText: String? = null) {
        Log.d(TAG, "hardReset() — destroying and rebuilding TTS engine")
        utteranceTimeoutHandler.removeCallbacksAndMessages(null)
        isSpeaking = false
        isReady = false

        try {
            tts?.stop()
            tts?.shutdown()
            tts = null
        } catch (e: Exception) { }

        // Re-add the failed text to front of queue
        if (pendingText != null) {
            messageQueue.addFirst(Pair(pendingText, SpeechPriority.NORMAL))
        }

        // Rebuild engine completely
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            initializeTTS()
        }, 1000L)
    }

}