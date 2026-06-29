package com.smartcane.app.managers

import android.content.Context
import android.content.Intent
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
    private var isStartingListening = false

    private val mainHandler = Handler(Looper.getMainLooper())

    companion object {
        private const val TAG = "SpeechManager"
        private const val MAX_RETRIES = 3
        private const val HIGH_CONFIDENCE = 0.75f
        private const val LOW_CONFIDENCE = 0.50f
        private const val POST_TTS_DELAY = 800L
    }

    init {
        // Create recognizer once — reuse it
        mainHandler.post {
            createRecognizer()
        }
    }

    private fun createRecognizer() {
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            Log.e(TAG, "Speech recognition not available")
            return
        }
        speechRecognizer?.destroy()
        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(context)
        Log.d(TAG, "SpeechRecognizer created")
    }

    // ─────────────────────────────────────────────────────────────────
    // Public API
    // ─────────────────────────────────────────────────────────────────

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
            try {
                speechRecognizer?.stopListening()
                speechRecognizer?.cancel()
            } catch (e: Exception) {
                Log.e(TAG, "Error stopping: ${e.message}")
            }
            currentState = SpeechState.IDLE
        }
    }

    fun isListening() = currentState == SpeechState.LISTENING

    fun shutdown() {
        isStopped = true
        mainHandler.removeCallbacksAndMessages(null)
        mainHandler.post {
            speechRecognizer?.destroy()
            speechRecognizer = null
            currentState = SpeechState.IDLE
        }
    }

    // ─────────────────────────────────────────────────────────────────
    // Core Flow
    // ─────────────────────────────────────────────────────────────────

    private fun waitForTTSThenListen() {
        if (isStopped) return
        if (audioFeedbackManager.isSpeaking()) {
            mainHandler.postDelayed({ waitForTTSThenListen() }, 500)
        } else {
            mainHandler.postDelayed({ beginListening() }, POST_TTS_DELAY)
        }
    }

    private fun beginListening() {
        if (isStopped) return
        if (isStartingListening) {
            Log.d(TAG, "Already starting — skipping")
            return
        }
        isStartingListening = true
        mainHandler.removeCallbacksAndMessages(null)

        mainHandler.post {
            if (isStopped) {
                isStartingListening = false
                return@post
            }

            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                    RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
                putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 4000L)
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 3000L)
            }

            try {
                createRecognizer()
                speechRecognizer?.setRecognitionListener(createListener())
                speechRecognizer?.startListening(intent)
                isStartingListening = false  // ← reset AFTER successful start
                currentState = SpeechState.LISTENING
                Log.d(TAG, "Started listening (attempt ${retryCount + 1}/$MAX_RETRIES)")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start: ${e.message}")
                isStartingListening = false
                handleRetry()
            }
        }
    }

    private fun startRecognition() {
        if (isStopped) return

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(
                RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
            )
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            putExtra(
                RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS,
                4000L
            )
            putExtra(
                RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS,
                3000L
            )
        }

        try {
            // If recognizer is null or dead — recreate it
            if (speechRecognizer == null) {
                createRecognizer()
            }
            speechRecognizer?.setRecognitionListener(createListener())
            speechRecognizer?.startListening(intent)
            currentState = SpeechState.LISTENING
            Log.d(TAG, "Started listening (attempt ${retryCount + 1}/$MAX_RETRIES)")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start: ${e.message}")
            handleRetry()
        }
    }

    // ─────────────────────────────────────────────────────────────────
    // Listener
    // ─────────────────────────────────────────────────────────────────

    private fun createListener() = object : RecognitionListener {

        override fun onReadyForSpeech(params: Bundle?) {
            Log.d(TAG, "✅ Ready for speech")
        }

        override fun onBeginningOfSpeech() {
            Log.d(TAG, "✅ Speech detected")
        }

        override fun onEndOfSpeech() {
            Log.d(TAG, "End of speech")
            currentState = SpeechState.PROCESSING
        }

        override fun onResults(results: Bundle?) {
            if (isStopped) return
            currentState = SpeechState.IDLE

            val matches = results
                ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            val confidences = results
                ?.getFloatArray(SpeechRecognizer.CONFIDENCE_SCORES)

            Log.d(TAG, "Results: $matches")

            if (matches.isNullOrEmpty()) {
                handleRetry()
                return
            }

            val bestText = matches[0]
            val bestConfidence = confidences?.getOrNull(0) ?: 0.6f
            retryCount = 0
            processResult(bestText, bestConfidence)
        }

        override fun onError(error: Int) {
            if (isStopped) return
            currentState = SpeechState.IDLE
            isStartingListening = false  // ← always reset here
            mainHandler.removeCallbacksAndMessages(null)  // ← cancel ALL pending
            Log.e(TAG, "Speech error: ${getErrorMessage(error)} (code $error)")

            val delayMs = when (error) {
                SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> 3000L
                SpeechRecognizer.ERROR_CLIENT -> 1000L
                11 -> 1000L
                SpeechRecognizer.ERROR_NETWORK,
                SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> 5000L
                SpeechRecognizer.ERROR_NO_MATCH,
                SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> {
                    handleRetry()
                    return
                }
                else -> {
                    handleRetry()
                    return
                }
            }

            mainHandler.postDelayed({
                if (!isStopped) beginListening()
            }, delayMs)
        }

        override fun onRmsChanged(rmsdB: Float) {}
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onPartialResults(partialResults: Bundle?) {}
        override fun onEvent(eventType: Int, params: Bundle?) {}
    }

    // ─────────────────────────────────────────────────────────────────
    // Result Processing
    // ─────────────────────────────────────────────────────────────────

    private fun processResult(text: String, confidence: Float) {
        val cleanText = text.lowercase().trim()
        Log.d(TAG, "Processing: '$cleanText' confidence=$confidence")

        when {
            confidence >= HIGH_CONFIDENCE || confidence == 0.0f -> {
                onResultCallback?.invoke(
                    SpeechResult(cleanText, confidence, false)
                )
            }
            confidence >= LOW_CONFIDENCE -> {
                pendingConfirmationText = cleanText
                audioFeedbackManager.speak(
                    "Did you say $cleanText? Say yes or no.",
                    SpeechPriority.NORMAL
                )
                mainHandler.postDelayed({
                    if (!isStopped) listenForConfirmation()
                }, 100)
            }
            else -> handleRetry()
        }
    }

    private fun listenForConfirmation() {
        if (isStopped) return
        if (audioFeedbackManager.isSpeaking()) {
            mainHandler.postDelayed({ listenForConfirmation() }, 300)
        } else {
            mainHandler.postDelayed({ startYesNoListening() }, POST_TTS_DELAY)
        }
    }

    private fun startYesNoListening() {
        if (isStopped) return

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(
                RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
            )
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
        }

        try {
            speechRecognizer?.stopListening()
            speechRecognizer?.cancel()
            mainHandler.postDelayed({
                if (!isStopped) {
                    speechRecognizer?.setRecognitionListener(
                        createYesNoListener()
                    )
                    speechRecognizer?.startListening(intent)
                    Log.d(TAG, "Listening for yes/no")
                }
            }, 200)
        } catch (e: Exception) {
            handleRetry()
        }
    }

    private fun createYesNoListener() = object : RecognitionListener {
        override fun onResults(results: Bundle?) {
            if (isStopped) return
            val answer = results
                ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                ?.getOrNull(0)?.lowercase()?.trim() ?: ""

            Log.d(TAG, "Yes/No heard: '$answer'")

            when {
                answer.containsAny("yes", "yeah", "correct", "oui") -> {
                    onResultCallback?.invoke(
                        SpeechResult(pendingConfirmationText, 0.7f, false)
                    )
                }
                answer.containsAny("no", "nope", "non") -> {
                    audioFeedbackManager.speak(
                        "Please say again.",
                        SpeechPriority.NORMAL
                    )
                    waitForTTSThenListen()
                }
                else -> handleRetry()
            }
        }

        override fun onError(error: Int) {
            Log.e(TAG, "Yes/No error: $error")
            handleRetry()
        }

        override fun onReadyForSpeech(params: Bundle?) {}
        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(rmsdB: Float) {}
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() {}
        override fun onPartialResults(partialResults: Bundle?) {}
        override fun onEvent(eventType: Int, params: Bundle?) {}
    }

    // ─────────────────────────────────────────────────────────────────
    // Retry
    // ─────────────────────────────────────────────────────────────────

    private fun handleRetry() {
        if (isStopped) return

        // Cancel any pending callbacks
        mainHandler.removeCallbacksAndMessages(null)

        retryCount++
        Log.d(TAG, "Retry $retryCount/$MAX_RETRIES")

        if (retryCount >= MAX_RETRIES) {
            currentState = SpeechState.ERROR
            onErrorCallback?.invoke("Max retries reached")
            return
        }

        audioFeedbackManager.speak(
            "I didn't hear anything. Please try again.",
            SpeechPriority.NORMAL
        )
        waitForTTSThenListen()
    }

    // ─────────────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────────────

    private fun getErrorMessage(error: Int): String = when (error) {
        SpeechRecognizer.ERROR_AUDIO -> "Audio recording error"
        SpeechRecognizer.ERROR_CLIENT -> "Client error"
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Missing mic permission"
        SpeechRecognizer.ERROR_NETWORK -> "Network error"
        SpeechRecognizer.ERROR_NO_MATCH -> "No speech match"
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Recognizer busy"
        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "No speech detected"
        11 -> "Cannot check support"
        else -> "Unknown error"
    }

    private fun String.containsAny(vararg keywords: String): Boolean =
        keywords.any { this.contains(it) }
}
