package com.smartcane.app

import android.Manifest
import android.content.ComponentName
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.util.Log
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.smartcane.app.service.CaneMonitorService
import com.smartcane.app.managers.AudioFeedbackManager
import com.smartcane.app.managers.SpeechManager
import com.smartcane.app.managers.TalkBackDetector
import com.smartcane.app.managers.SpeechPriority // Added this import for audio feedback

class MainActivity : AppCompatActivity() {
    private lateinit var audioFeedbackManager: AudioFeedbackManager
    private lateinit var speechManager: SpeechManager

    internal var caneService: CaneMonitorService? = null
    private var serviceBound = false

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, service: IBinder) {
            // 1. Bind the service first
            val binder = service as CaneMonitorService.CaneBinder
            caneService = binder.getService()
            serviceBound = true
            Log.d("MainActivity", "CaneMonitorService connected")

            // 2. Start listening and send the results to our Smart Router
            speechManager.startListening(
                onResult = { result ->
                    handleVoiceCommand(result.text)
                },
                onError = { error ->
                    Log.e("MainActivity", "Speech Error: $error")
                }
            )
        }

        override fun onServiceDisconnected(name: ComponentName) {
            serviceBound = false
            caneService = null
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Keep screen on
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        // Initialize Managers
        val talkBackDetector = TalkBackDetector(this)
        audioFeedbackManager = AudioFeedbackManager(this, talkBackDetector)
        speechManager = SpeechManager(this, audioFeedbackManager)

        requestRequiredPermissions()
    }

    override fun onStart() {
        super.onStart()
        val intent = Intent(this, CaneMonitorService::class.java)
        startForegroundService(intent)
        bindService(intent, serviceConnection, BIND_AUTO_CREATE)
    }

    override fun onStop() {
        speechManager.stopListening()
        super.onStop()
        if (serviceBound) {
            unbindService(serviceConnection)
            serviceBound = false
        }
    }

    override fun onDestroy() {
        speechManager.shutdown()
        super.onDestroy()
    }

    // ─────────────────────────────────────────────────────────────────────
    // Permissions
    // ─────────────────────────────────────────────────────────────────────

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val denied = permissions.filterValues { !it }.keys
        if (denied.isNotEmpty()) {
            Log.w("MainActivity", "Denied permissions: $denied")
        }
    }

    private fun requestRequiredPermissions() {
        val required = mutableListOf(
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.SEND_SMS,
            Manifest.permission.CALL_PHONE,
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            required.add(Manifest.permission.BLUETOOTH_CONNECT)
            required.add(Manifest.permission.BLUETOOTH_SCAN)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            required.add(Manifest.permission.POST_NOTIFICATIONS)
        }

        val notGranted = required.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (notGranted.isNotEmpty()) {
            permissionLauncher.launch(notGranted.toTypedArray())
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // Voice Command Smart Router
    // ─────────────────────────────────────────────────────────────────────

    enum class CommandIntent {
        NAVIGATE, BATTERY, FAMILY, UNKNOWN
    }

    private fun parseCommand(spokenText: String): CommandIntent {
        val text = spokenText.lowercase()
        return when {
            Regex(".*(navigate|navigation|go to|take me|directions|route).*").matches(text) -> CommandIntent.NAVIGATE
            Regex(".*(battery|power|charge|juice|percent).*").matches(text) -> CommandIntent.BATTERY
            Regex(".*(family |family conatact |call|contact|son|daughter|wife|husband).*").matches(text) -> CommandIntent.FAMILY
            else -> CommandIntent.UNKNOWN
        }
    }

    private fun handleVoiceCommand(result: String) {
        Log.d("MainActivity", "Raw speech received: '$result'")

        val intent = parseCommand(result)
        Log.d("MainActivity", "Understood Intent: $intent")

        when (intent) {
            CommandIntent.NAVIGATE -> {
                audioFeedbackManager.speak("Opening navigation", SpeechPriority.HIGH)
                // val intent = Intent(this, NavigationActivity::class.java)
                // startActivity(intent)
            }
            CommandIntent.BATTERY -> {
                // We will build the real battery checker later!
                // val level = caneService?.getBatteryLevel() ?: "unknown"
                audioFeedbackManager.speak("Battery checking is not yet implemented", SpeechPriority.NORMAL)
            }
            CommandIntent.FAMILY -> {
                audioFeedbackManager.speak("Opening family contacts", SpeechPriority.HIGH)
                // val intent = Intent(this, FamilyActivity::class.java)
                // startActivity(intent)
            }
            CommandIntent.UNKNOWN -> {
                audioFeedbackManager.speak("I didn't catch that. Please try again.", SpeechPriority.NORMAL)
            }
        }
    }
}