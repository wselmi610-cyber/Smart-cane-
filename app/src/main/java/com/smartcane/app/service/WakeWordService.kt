package com.smartcane.app.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import androidx.core.app.NotificationCompat
import com.smartcane.app.MainActivity
import com.smartcane.app.R
import com.smartcane.app.SmartCaneApplication
import com.smartcane.app.managers.SpeechPriority
import android.content.Context
class WakeWordService : Service() {

    private var recognizer: SpeechRecognizer? = null
    private var isListening = false
    private var isDestroyed = false
    private var isAppForeground = false
    private val handler = Handler(Looper.getMainLooper())
    private val audioManager by lazy {
        getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
    }
    private var wakeFocusRequest: android.media.AudioFocusRequest? = null
    companion object {
        private const val TAG = "WakeWordService"
        const val CHANNEL_ID = "WakeWordChannel"
        const val NOTIFICATION_ID = 2
        const val ACTION_APP_FOREGROUND = "com.smartcane.app.APP_FOREGROUND"
        const val ACTION_APP_BACKGROUND = "com.smartcane.app.APP_BACKGROUND"

        val WAKE_WORDS = listOf(
            "hey cane", "hi cane", "hey kane", "hey can",
            "ok cane", "okay cane", "smart cane",
            "hurricane", "hey came", "hey gain",
            "hey chain", "hey lane", "a cane",
            "hey canne", "bonjour canne", "salut canne",
            "ok canne"
        )
    }

    // ─────────────────────────────────────────────────────────────────
    // Lifecycle
    // ─────────────────────────────────────────────────────────────────

    override fun onCreate() {
        super.onCreate()

        // Force delete old cached channel so new settings apply
        val manager = getSystemService(NotificationManager::class.java)
        manager.deleteNotificationChannel("WakeWordAlert")

        createNotificationChannel()
        
        // Android 14+ requires explicit foreground service type in startForeground()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID, 
                buildSilentNotification(),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            )
        } else {
            startForeground(NOTIFICATION_ID, buildSilentNotification())
        }
        
        Log.d(TAG, "✅ WakeWordService created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        Log.d(TAG, "onStartCommand action=$action")

        when (action) {
            ACTION_APP_FOREGROUND -> {
                isAppForeground = true
                Log.d(TAG, "🔴 App foreground — stopping mic")
                stopRecognizer()
            }
            ACTION_APP_BACKGROUND -> {
                isAppForeground = false
                Log.d(TAG, "🟢 App background — starting mic in 1500ms")
                handler.postDelayed({
                    if (!isDestroyed && !isAppForeground) {
                        startListening()
                    }
                }, 1500)
            }
            else -> {
                Log.d(TAG, "🟡 Service started — waiting for background signal")
            }
        }
        return START_STICKY
    }


    override fun onDestroy() {
        isDestroyed = true
        handler.removeCallbacksAndMessages(null)
        stopRecognizer()
        Log.d(TAG, "WakeWordService destroyed")
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ─────────────────────────────────────────────────────────────────
    // Recognizer
    // ─────────────────────────────────────────────────────────────────

    private fun startListening() {
        if (isDestroyed || isAppForeground || isListening) {
            Log.d(TAG, "startListening blocked — " +
                    "destroyed=$isDestroyed " +
                    "foreground=$isAppForeground " +
                    "listening=$isListening")
            return
        }

        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            Log.e(TAG, "❌ Recognition not available")
            return
        }

        // Double check before touching audio focus
        if (isAppForeground) {
            Log.d(TAG, "App foreground — not requesting audio focus")
            return
        }

        isListening = true
        Log.d(TAG, "🎤 Starting recognizer...")

        // Do NOT request audio focus at all
        // SpeechRecognizer handles its own audio focus internally
        // Requesting it manually causes conflicts with TTS and STT

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(
                RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
            )
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-US")
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
            putExtra(
                RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS,
                5000L
            )
            putExtra(
                RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS,
                5000L
            )
        }

        try {
            recognizer?.destroy()
            recognizer = SpeechRecognizer.createSpeechRecognizer(this)
            recognizer?.setRecognitionListener(recognitionListener)
            recognizer?.startListening(intent)
            Log.d(TAG, "✅ Recognizer started")
        } catch (e: Exception) {
            Log.e(TAG, "❌ Failed to start: ${e.message}")
            isListening = false
            restartAfter(3000)
        }
    }

    private fun stopRecognizer() {
        isListening = false
        handler.removeCallbacksAndMessages(null)
        try {
            recognizer?.stopListening()
            recognizer?.cancel()
            recognizer?.destroy()
            recognizer = null
            Log.d(TAG, "Recognizer stopped")
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping: ${e.message}")
        }
    }

    private fun restartAfter(delayMs: Long) {
        if (isDestroyed || isAppForeground) return
        Log.d(TAG, "Restarting in ${delayMs}ms")
        handler.postDelayed({
            if (!isDestroyed && !isAppForeground && !isListening) {
                startListening()
            }
        }, delayMs)
    }

    // ─────────────────────────────────────────────────────────────────
    // Recognition Listener
    // ─────────────────────────────────────────────────────────────────

    private val recognitionListener = object : RecognitionListener {

        override fun onReadyForSpeech(params: Bundle?) {
            Log.d(TAG, "✅ Mic ready")
        }

        override fun onBeginningOfSpeech() {
            Log.d(TAG, "🗣 Speech detected")
        }

        override fun onEndOfSpeech() {
            Log.d(TAG, "End of speech")
            isListening = false
        }

        override fun onResults(results: Bundle?) {
            isListening = false
            if (isAppForeground || isDestroyed) {
                Log.d(TAG, "Result ignored — app foreground")
                return
            }

            val matches = results
                ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                ?: emptyList()

            Log.d(TAG, "Results: $matches")

            val detected = matches.any { spoken ->
                WAKE_WORDS.any { wake ->
                    spoken.lowercase().contains(wake)
                }
            }

            if (detected) {
                Log.d(TAG, "🚀 Wake word detected!")
                alertUser()
            } else {
                restartAfter(1500)
            }
        }

        override fun onError(error: Int) {
            isListening = false
            val name = when (error) {
                SpeechRecognizer.ERROR_AUDIO           -> "AUDIO"
                SpeechRecognizer.ERROR_CLIENT          -> "CLIENT"
                SpeechRecognizer.ERROR_NETWORK         -> "NETWORK"
                SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "NETWORK_TIMEOUT"
                SpeechRecognizer.ERROR_NO_MATCH        -> "NO_MATCH"
                SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "BUSY"
                SpeechRecognizer.ERROR_SERVER          -> "SERVER"
                SpeechRecognizer.ERROR_SPEECH_TIMEOUT  -> "SPEECH_TIMEOUT"
                SpeechRecognizer.ERROR_CANNOT_CHECK_SUPPORT -> "CANNOT_CHECK_SUPPORT"
                else                                   -> "UNKNOWN($error)"
            }
            Log.d(TAG, "⚠️ Error: $name")
            if (isAppForeground || isDestroyed) return

            val delay = when (error) {
                SpeechRecognizer.ERROR_RECOGNIZER_BUSY   -> 4000L
                SpeechRecognizer.ERROR_NETWORK,
                SpeechRecognizer.ERROR_NETWORK_TIMEOUT   -> 10000L
                11                                       -> 2000L
                else                                     -> 1500L
            }
            restartAfter(delay)
        }

        override fun onRmsChanged(rmsdB: Float) {}
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onPartialResults(partial: Bundle?) {}
        override fun onEvent(type: Int, params: Bundle?) {}
    }

    // ─────────────────────────────────────────────────────────────────
    // Alert User — speak + notify + vibrate
    // ─────────────────────────────────────────────────────────────────

    private fun alertUser() {
        Log.d(TAG, "🔔 Alerting user...")

        try {
            val pm = getSystemService(POWER_SERVICE) as PowerManager
            val wl = pm.newWakeLock(
                PowerManager.FULL_WAKE_LOCK or
                        PowerManager.ACQUIRE_CAUSES_WAKEUP or
                        PowerManager.ON_AFTER_RELEASE,
                "SmartCane:WakeWordWakeLock"
            )
            wl.acquire(10000L)

            // Speak
            try {
                val app = application as SmartCaneApplication
                app.audioFeedbackManager.speak(
                    "Smart Cane. Opening now.",
                    SpeechPriority.CRITICAL
                )
            } catch (e: Exception) {
                Log.e(TAG, "TTS failed: ${e.message}")
            }

            handler.postDelayed({
                val wakeIntent = Intent(
                    this,
                    com.smartcane.app.ui.WakeActivity::class.java
                ).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                            Intent.FLAG_ACTIVITY_CLEAR_TOP
                }

                // Try AccessibilityService first (most reliable)
                val opened = CaneAccessibilityService.openApp(wakeIntent)

                if (!opened) {
                    // Fallback — direct startActivity
                    try {
                        startActivity(wakeIntent)
                        Log.d(TAG, "✅ Opened via direct startActivity")
                    } catch (e: Exception) {
                        Log.e(TAG, "❌ Both methods failed: ${e.message}")
                        showWakeNotification()
                    }
                }

                handler.postDelayed({
                    try { wl.release() } catch (e: Exception) { }
                }, 10000L)
            }, 500L)

        } catch (e: Exception) {
            Log.e(TAG, "❌ alertUser failed: ${e.message}")
        }

        restartAfter(5000)
    }

    private fun showWakeNotification() {
        val wakeIntent = Intent(
            this, com.smartcane.app.ui.WakeActivity::class.java
        ).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP
        }

        val pendingIntent = PendingIntent.getActivity(
            this, 2, wakeIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(this, "WakeWordAlert")
            .setContentTitle("Smart Cane")
            .setContentText("Opening Smart Cane...")
            .setSmallIcon(R.mipmap.ic_launcher)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .setFullScreenIntent(pendingIntent, true)
            .setVibrate(longArrayOf(0, 400, 100, 400, 100, 400))
            // No sound
            .setSound(null)
            // Show on lock screen
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .build()

        // Set FLAG_INSISTENT so it demands attention
        notification.flags = notification.flags or
                android.app.Notification.FLAG_INSISTENT

        getSystemService(NotificationManager::class.java).notify(3, notification)
        Log.d(TAG, "✅ Full screen notification fired")

        handler.postDelayed({
            getSystemService(NotificationManager::class.java).cancel(3)
        }, 30000L)
    }

    // ─────────────────────────────────────────────────────────────────
    // Notifications
    // ─────────────────────────────────────────────────────────────────

    private fun createNotificationChannel() {
        // Silent background channel
        val silentChannel = NotificationChannel(
            CHANNEL_ID,
            "Wake Word Background",
            NotificationManager.IMPORTANCE_MIN
        ).apply {
            setShowBadge(false)
            setSound(null, null)
        }

        // Alert channel — IMPORTANCE_HIGH is required for
        // fullScreenIntent to trigger automatically
        val alertChannel = NotificationChannel(
            "WakeWordAlert",
            "Smart Cane Wake Alert",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "Opens Smart Cane when Hey Cane detected"
            enableVibration(true)
            vibrationPattern = longArrayOf(0, 400, 100, 400, 100, 400)
            // No sound — TTS handles audio
            setSound(null, null)
            // Lock screen visibility
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        }

        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(silentChannel)
        manager.createNotificationChannel(alertChannel)
    }

    private fun buildSilentNotification(): Notification {
        val pi = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Smart Cane")
            .setContentText("Say Hey Cane to activate")
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentIntent(pi)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setVisibility(NotificationCompat.VISIBILITY_SECRET)
            .build()
    }
}
