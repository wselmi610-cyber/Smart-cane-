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
import com.smartcane.app.service.WakeWordService

class MainActivity : AppCompatActivity() {

    internal var caneService: CaneMonitorService? = null
    private var serviceBound = false

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, service: IBinder) {
            val binder = service as CaneMonitorService.CaneBinder
            caneService = binder.getService()
            serviceBound = true
            Log.d("MainActivity", "CaneMonitorService connected")
        }
        override fun onServiceDisconnected(name: ComponentName) {
            serviceBound = false
            caneService = null
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        window.addFlags(
            android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
        )
        requestRequiredPermissions()
    }

    override fun onStart() {
        super.onStart()

        sendToWakeWord(WakeWordService.ACTION_APP_FOREGROUND)

        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            val caneIntent = Intent(this, CaneMonitorService::class.java)
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    startForegroundService(caneIntent)
                } else {
                    startService(caneIntent)
                }
                bindService(caneIntent, serviceConnection, BIND_AUTO_CREATE)
            } catch (e: Exception) {
                Log.e("MainActivity", "CaneService failed: ${e.message}")
            }
            startWakeWordService()
            startReminderService()  // ← ADD THIS
        }, 2000)
    }

    override fun onStop() {
        super.onStop()

        // Tell WakeWordService app is hidden — can use mic now
        sendToWakeWord(WakeWordService.ACTION_APP_BACKGROUND)

        if (serviceBound) {
            unbindService(serviceConnection)
            serviceBound = false
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        val app = application as SmartCaneApplication
        try { app.speechManager.shutdown() } catch (e: Exception) { }
    }

    // ─────────────────────────────────────────────────────────────────
    // Wake Word
    // ─────────────────────────────────────────────────────────────────

    private fun sendToWakeWord(action: String) {
        try {
            val intent = Intent(this, WakeWordService::class.java).apply {
                this.action = action
            }
            // Only send signals if we have permission to avoid crashes on startup
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                startService(intent)
                Log.d("MainActivity", "WakeWord signal: $action")
            }
        } catch (e: Exception) {
            Log.e("MainActivity", "WakeWord signal failed: ${e.message}")
        }
    }

    private fun startWakeWordService() {
        // On Android 14 (API 34), RECORD_AUDIO MUST be granted BEFORE starting 
        // a foreground service with type 'microphone'.
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            Log.w("MainActivity", "Skipping WakeWordService: RECORD_AUDIO not granted yet")
            return
        }

        try {
            val intent = Intent(this, WakeWordService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }
            Log.d("MainActivity", "WakeWordService started")
        } catch (e: Exception) {
            Log.e("MainActivity", "WakeWordService failed: ${e.message}")
        }
    }

    // ─────────────────────────────────────────────────────────────────
    // Permissions
    // ─────────────────────────────────────────────────────────────────

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val denied = permissions.filterValues { !it }.keys
        if (denied.isNotEmpty()) {
            Log.w("MainActivity", "Denied: $denied")
        }
        
        // If microphone permission is granted, we can now safely start the WakeWordService
        if (permissions[Manifest.permission.RECORD_AUDIO] == true) {
            Log.d("MainActivity", "RECORD_AUDIO granted, starting WakeWordService")
            startWakeWordService()
        }
    }

    private var isReinitializing = false

    override fun onResume() {
        super.onResume()
        val app = application as SmartCaneApplication

        // Force reset speaking state — onDone may never have fired
        // if audio was interrupted by settings/accessibility screen
        app.audioFeedbackManager.forceReset()
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
        
        // Android 14 requirement: FOREGROUND_SERVICE_MICROPHONE permission
        // This is a normal permission, but it's good to ensure it's in the manifest (already checked).
        
        val notGranted = required.filter {
            ContextCompat.checkSelfPermission(this, it) !=
                    PackageManager.PERMISSION_GRANTED
        }
        if (notGranted.isNotEmpty()) {
            permissionLauncher.launch(notGranted.toTypedArray())
        }
    }
    private fun startReminderService() {
        try {
            val intent = Intent(this, com.smartcane.app.service.ReminderService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }
            Log.d("MainActivity", "ReminderService started")
        } catch (e: Exception) {
            Log.e("MainActivity", "ReminderService failed: ${e.message}")
        }
    }
}
