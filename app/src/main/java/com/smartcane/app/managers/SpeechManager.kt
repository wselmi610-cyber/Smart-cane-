package com.smartcane.app.managers

import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import java.util.Locale

enum class SpeechState { IDLE, LISTENING, PROCESSING, ERROR }

data class SpeechResult(
    val text: String,
    val confidence: Float,
    val needsConfirmation: Boolean
)

class SpeechManager(
    private val context: Context,
    private val audioFeedbackManager: AudioFeedbackManager
) {
    private var speechRecognizer: SpeechRecognizer? = null
    private var currentState = SpeechState.IDLE
    private var retryCount = 0
    private var onResultCallback: ((SpeechResult) -> Unit)? = null
    private var onErrorCallback: ((String) -> Unit)? = null
    private var pendingConfirmationText = ""
    private var isStopped = false

    private val mainHandler = Handler(Looper.getMainLooper())
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    private var audioFocusRequest: android.media.AudioFocusRequest? = null

    private fun requestAudioFocusForMic() {
        audioFocusRequest = android.media.AudioFocusRequest.Builder(
            AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE
        ).apply {
            setAudioAttributes(
                android.media.AudioAttributes.Builder()
                    .setUsage(android.media.AudioAttributes.USAGE_VOICE_COMMUNICATION)
                    .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            setAcceptsDelayedFocusGain(false)
            setOnAudioFocusChangeListener { }
        }.build()

        audioManager.requestAudioFocus(audioFocusRequest!!)
    }

    private fun releaseAudioFocus() {
        audioFocusRequest?.let {
            audioManager.abandonAudioFocusRequest(it)
            audioFocusRequest = null
        }
    }

    companion object {
        private const val TAG = "SpeechManager"
        private const val MAX_RETRIES = 3
        private const val HIGH_CONFIDENCE = 0.75f
        private const val LOW_CONFIDENCE = 0.50f
        private const val POST_TTS_DELAY = 700L
    }

    init {
        initializeSpeechRecognizer()
    }

    private fun initializeSpeechRecognizer() {
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            Log.e(TAG, "Speech recognition not available")
            return
        }
        mainHandler.post {
            destroyRecognizer()
            speechRecognizer = SpeechRecognizer.createSpeechRecognizer(context)
            speechRecognizer?.setRecognitionListener(createRecognitionListener())
            Log.d(TAG, "SpeechRecognizer initialized on main thread")
        }
    }

    private fun destroyRecognizer() {
        speechRecognizer?.apply {
            setRecognitionListener(null)
            stopListening()
            cancel()
            destroy()
        }
        speechRecognizer = null
    }

    // ─────────────────────────────────────────────────────────────────────
    // Public API
    // ─────────────────────────────────────────────────────────────────────

    fun startListening(
        onResult: (SpeechResult) -> Unit,
        onError: (String) -> Unit
    ) {
        isStopped = false
        onResultCallback = onResult
        onErrorCallback = onError
        retryCount = 0

        waitForTTSThenListen()
    }

    fun stopListening() {
        isStopped = true
        mainHandler.removeCallbacksAndMessages(null)
        mainHandler.post {
            destroyRecognizer()
            currentState = SpeechState.IDLE
        }
    }

    fun isListening() = currentState == SpeechState.LISTENING

    fun shutdown() {
        stopListening()
        mainHandler.post {
            destroyRecognizer()
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // Core Listening Logic
    // ─────────────────────────────────────────────────────────────────────

    private fun waitForTTSThenListen() {
        if (isStopped) return

        if (audioFeedbackManager.isSpeaking()) {
            mainHandler.postDelayed({ waitForTTSThenListen() }, 300)
        } else {
            mainHandler.postDelayed({ beginListening() }, POST_TTS_DELAY)
        }
    }

    private fun beginListening() {
        if (isStopped) return

        mainHandler.post {
            currentState = SpeechState.LISTENING
            requestAudioFocusForMic()
            audioManager.adjustStreamVolume(
                AudioManager.STREAM_NOTIFICATION,
                AudioManager.ADJUST_MUTE,
                0
            )

            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                    putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
                }
            }

            try {
                destroyRecognizer()
                speechRecognizer = SpeechRecognizer.createSpeechRecognizer(context)
                speechRecognizer?.setRecognitionListener(createRecognitionListener())
                speechRecognizer?.startListening(intent)
                Log.d(TAG, "Started listening (attempt ${retryCount + 1}/$MAX_RETRIES)")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start: ${e.message}")
                handleRetry()
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // Recognition Listener
    // ─────────────────────────────────────────────────────────────────────

    private fun createRecognitionListener() = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) { Log.d(TAG, "✅ Ready for speech") }
        override fun onBeginningOfSpeech() { Log.d(TAG, "✅ Speech detected") }

        override fun onResults(results: Bundle?) {
            unmuteAudio()
            currentState = SpeechState.PROCESSING

            val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            val confidences = results?.getFloatArray(SpeechRecognizer.CONFIDENCE_SCORES)

            if (matches.isNullOrEmpty()) {
                handleRetry()
                return
            }

            val bestText = matches[0]
            val bestConfidence = confidences?.getOrNull(0) ?: 0.6f
            processResult(bestText, bestConfidence)
        }

        override fun onError(error: Int) {
            unmuteAudio()
            Log.e(TAG, "Speech error: ${getErrorMessage(error)} (code $error)")

            if (isStopped) return

            when (error) {
                SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> {
                    mainHandler.postDelayed({ if (!isStopped) beginListening() }, 1000)
                }
                SpeechRecognizer.ERROR_SPEECH_TIMEOUT, SpeechRecognizer.ERROR_NO_MATCH -> {
                    handleRetry()
                }
                else -> handleRetry()
            }
        }

        override fun onRmsChanged(rmsdB: Float) {}
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() { Log.d(TAG, "End of speech") }
        override fun onPartialResults(partialResults: Bundle?) {}
        override fun onEvent(eventType: Int, params: Bundle?) {}
    }

    private fun processResult(text: String, confidence: Float) {
        val cleanText = text.lowercase().trim()
        if (confidence >= HIGH_CONFIDENCE || confidence == 0.0f) {
            retryCount = 0
            onResultCallback?.invoke(SpeechResult(cleanText, confidence, false))
        } else if (confidence >= LOW_CONFIDENCE) {
            pendingConfirmationText = cleanText
            audioFeedbackManager.speak("Did you say $cleanText? Say yes or no.")
            mainHandler.postDelayed({ if (!isStopped) listenForConfirmation() }, 100)
        } else {
            handleRetry()
        }
    }

    private fun listenForConfirmation() {
        if (isStopped) return
        if (audioFeedbackManager.isSpeaking()) {
            mainHandler.postDelayed({ listenForConfirmation() }, 300)
        } else {
            mainHandler.postDelayed({ beginListeningForYesNo() }, POST_TTS_DELAY)
        }
    }

    private fun beginListeningForYesNo() {
        if (isStopped) return
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        }

        destroyRecognizer()
        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(context)
        speechRecognizer?.setRecognitionListener(object : RecognitionListener {
            override fun onResults(results: Bundle?) {
                val answer = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.getOrNull(0)?.lowercase()?.trim() ?: ""

                if (answer.containsAny("yes", "yeah", "correct")) {
                    onResultCallback?.invoke(SpeechResult(pendingConfirmationText, 0.7f, false))
                } else {
                    handleRetry()
                }
            }
            override fun onError(error: Int) { handleRetry() }
            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onPartialResults(partialResults: Bundle?) {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
        speechRecognizer?.startListening(intent)
    }

    private fun handleRetry() {
        if (isStopped) return
        retryCount++
        if (retryCount >= MAX_RETRIES) {
            onErrorCallback?.invoke("Max retries reached")
            return
        }
        waitForTTSThenListen()
    }

    private fun unmuteAudio() {
        try {
            releaseAudioFocus()
            audioManager.adjustStreamVolume(AudioManager.STREAM_NOTIFICATION, AudioManager.ADJUST_UNMUTE, 0)
        } catch (e: Exception) { Log.e(TAG, "Unmute failed: ${e.message}") }
    }

    private fun getErrorMessage(error: Int): String = when (error) {
        SpeechRecognizer.ERROR_AUDIO -> "Audio recording error"
        SpeechRecognizer.ERROR_CLIENT -> "Client error"
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Missing mic permission"
        SpeechRecognizer.ERROR_NETWORK -> "Network error"
        SpeechRecognizer.ERROR_NO_MATCH -> "No speech match"
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Recognizer busy"
        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "No speech detected"
        else -> "Unknown error"
    }

    private fun String.containsAny(vararg keywords: String): Boolean = keywords.any { this.contains(it) }
}
