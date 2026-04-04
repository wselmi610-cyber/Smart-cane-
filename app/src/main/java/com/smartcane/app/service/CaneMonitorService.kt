package com.smartcane.app.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.location.Geocoder
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.VibrationEffect
import android.os.Vibrator
import android.util.Log
import androidx.core.app.NotificationCompat
import com.smartcane.app.MainActivity
import com.smartcane.app.R
import com.smartcane.app.SmartCaneApplication
import com.smartcane.app.managers.SpeechPriority
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Locale
import java.util.UUID

// ── Cane BLE UUIDs (replace with your actual hardware UUIDs) ──────────────
private val CANE_SERVICE_UUID       = UUID.fromString("0000ffe0-0000-1000-8000-00805f9b34fb")
private val CANE_CHARACTERISTIC_UUID = UUID.fromString("0000ffe1-0000-1000-8000-00805f9b34fb")
private val CCCD_UUID               = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

// ── Cane command bytes (replace with your actual protocol) ────────────────
private const val CMD_SOS      = "SOS"
private const val CMD_POSITION = "POS"
private const val CMD_BATTERY  = "BAT"

class CaneMonitorService : Service() {

    // ── Binder for Activity/Fragment access ───────────────────────────────
    inner class CaneBinder : Binder() {
        fun getService(): CaneMonitorService = this@CaneMonitorService
    }
    private val binder = CaneBinder()
    override fun onBind(intent: Intent): IBinder = binder

    // ── App-level singletons ──────────────────────────────────────────────
    private val app by lazy { application as SmartCaneApplication }
    private val audio by lazy { app.audioFeedbackManager }

    // ── Bluetooth ─────────────────────────────────────────────────────────
    private var bluetoothGatt: BluetoothGatt? = null
    private var isConnected = false
    private var reconnectAttempts = 0
    private val MAX_RECONNECT = 5

    // ── State ─────────────────────────────────────────────────────────────
    var caneBatteryLevel: Int = -1
        private set
    var isCaneConnected: Boolean = false
        private set

    // ── Callbacks exposed to UI ───────────────────────────────────────────
    var onCaneConnected: (() -> Unit)? = null
    var onCaneDisconnected: (() -> Unit)? = null
    var onBatteryUpdate: ((Int) -> Unit)? = null
    var onSosTriggered: (() -> Unit)? = null
    var onPositionRequested: ((String) -> Unit)? = null

    companion object {
        private const val TAG = "CaneMonitorService"
        const val CHANNEL_ID = "SmartCaneChannel"
        const val NOTIFICATION_ID = 1
        const val ACTION_SOS = "com.smartcane.app.ACTION_SOS"
        const val ACTION_POSITION = "com.smartcane.app.ACTION_POSITION"
        const val ACTION_BATTERY = "com.smartcane.app.ACTION_BATTERY"
    }

    // ─────────────────────────────────────────────────────────────────────
    // Lifecycle
    // ─────────────────────────────────────────────────────────────────────

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification("Smart Cane active"))
        registerBluetoothReceiver()
        Log.d(TAG, "CaneMonitorService created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "Service started")
        return START_STICKY   // Restart automatically if killed
    }

    override fun onDestroy() {
        super.onDestroy()
        disconnectCane()
        try { unregisterReceiver(bluetoothStateReceiver) } catch (e: Exception) { }
        Log.d(TAG, "CaneMonitorService destroyed")
    }

    // ─────────────────────────────────────────────────────────────────────
    // Foreground Notification
    // ─────────────────────────────────────────────────────────────────────

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Smart Cane Monitor",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Monitors Smart Cane connection and alerts"
        }
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(channel)
    }

    private fun buildNotification(message: String): Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Smart Cane")
            .setContentText(message)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(message: String) {
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, buildNotification(message))
    }

    // ─────────────────────────────────────────────────────────────────────
    // Bluetooth Connection
    // ─────────────────────────────────────────────────────────────────────

    fun connectToCane(device: BluetoothDevice) {
        Log.d(TAG, "Connecting to cane: ${device.address}")
        bluetoothGatt = device.connectGatt(this, false, gattCallback)
    }

    fun disconnectCane() {
        bluetoothGatt?.disconnect()
        bluetoothGatt?.close()
        bluetoothGatt = null
        isConnected = false
        isCaneConnected = false
    }

    private val gattCallback = object : BluetoothGattCallback() {

        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    Log.d(TAG, "Cane connected")
                    isConnected = true
                    isCaneConnected = true
                    reconnectAttempts = 0
                    gatt.discoverServices()
                    CoroutineScope(Dispatchers.Main).launch {
                        audio.speak(
                            getString(R.string.tts_cane_connected),
                            SpeechPriority.HIGH
                        )
                        updateNotification("Smart Cane connected")
                        onCaneConnected?.invoke()
                    }
                }

                BluetoothProfile.STATE_DISCONNECTED -> {
                    Log.d(TAG, "Cane disconnected")
                    isConnected = false
                    isCaneConnected = false
                    CoroutineScope(Dispatchers.Main).launch {
                        // CRITICAL — interrupts everything
                        audio.speak(
                            getString(R.string.tts_cane_disconnected),
                            SpeechPriority.CRITICAL
                        )
                        vibrateAlert()
                        updateNotification("Smart Cane disconnected!")
                        onCaneDisconnected?.invoke()
                    }
                    attemptReconnect(gatt.device)
                }
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                enableNotifications(gatt)
            }
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray
        ) {
            val message = String(value).trim()
            Log.d(TAG, "Cane message received: $message")
            handleCaneMessage(message)
        }

        // For older API compatibility
        @Deprecated("Deprecated in Java")
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic
        ) {
            val message = String(characteristic.value ?: return).trim()
            Log.d(TAG, "Cane message received (legacy): $message")
            handleCaneMessage(message)
        }
    }

    private fun enableNotifications(gatt: BluetoothGatt) {
        val service = gatt.getService(CANE_SERVICE_UUID) ?: run {
            Log.e(TAG, "Cane service not found. Check UUIDs.")
            return
        }
        val characteristic = service.getCharacteristic(CANE_CHARACTERISTIC_UUID) ?: run {
            Log.e(TAG, "Cane characteristic not found. Check UUIDs.")
            return
        }
        gatt.setCharacteristicNotification(characteristic, true)
        val descriptor = characteristic.getDescriptor(CCCD_UUID)
        descriptor?.let {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                gatt.writeDescriptor(it, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
            } else {
                @Suppress("DEPRECATION")
                it.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                @Suppress("DEPRECATION")
                gatt.writeDescriptor(it)
            }
        }
        Log.d(TAG, "BLE notifications enabled")
    }

    private fun attemptReconnect(device: BluetoothDevice) {
        if (reconnectAttempts >= MAX_RECONNECT) {
            Log.e(TAG, "Max reconnect attempts reached")
            return
        }
        reconnectAttempts++
        CoroutineScope(Dispatchers.IO).launch {
            Log.d(TAG, "Reconnect attempt $reconnectAttempts/$MAX_RECONNECT")
            delay(10_000L)   // Wait 10 seconds between attempts
            if (!isConnected) {
                bluetoothGatt?.close()
                connectToCane(device)
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // Cane Message Handler
    // ─────────────────────────────────────────────────────────────────────

    private fun handleCaneMessage(message: String) {
        CoroutineScope(Dispatchers.Main).launch {
            when {
                message.startsWith(CMD_SOS) -> handleSOS()
                message.startsWith(CMD_POSITION) -> handlePositionRequest()
                message.startsWith(CMD_BATTERY) -> handleBatteryUpdate(message)
                else -> Log.w(TAG, "Unknown cane message: $message")
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // SOS Handler
    // ─────────────────────────────────────────────────────────────────────

    private fun handleSOS() {
        Log.d(TAG, "SOS triggered from cane")
        vibrateAlert()

        // Get current location and send SMS to all contacts
        CoroutineScope(Dispatchers.IO).launch {
            val contacts = app.contactRepository.getAllContactsOnce()

            if (contacts.isEmpty()) {
                CoroutineScope(Dispatchers.Main).launch {
                    audio.speak(
                        "SOS pressed but no family contacts saved.",
                        SpeechPriority.CRITICAL
                    )
                }
                return@launch
            }

            // Get last known location
            val locationHelper = LocationHelper(this@CaneMonitorService)
            val location = locationHelper.getLastKnownLocation()

            val smsBody = if (location != null) {
                "🆘 SOS Alert! Your family member needs help.\n" +
                        "Location: https://maps.google.com/?q=${location.latitude},${location.longitude}"
            } else {
                "🆘 SOS Alert! Your family member needs help.\n" +
                        "Location unavailable."
            }

            // Send SMS to all contacts
            val smsManager = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                getSystemService(android.telephony.SmsManager::class.java)
            } else {
                @Suppress("DEPRECATION")
                android.telephony.SmsManager.getDefault()
            }

            var allSent = true
            contacts.forEach { contact ->
                try {
                    smsManager?.sendTextMessage(
                        contact.phoneNumber, null, smsBody, null, null
                    )
                    Log.d(TAG, "SOS SMS sent to ${contact.name}")
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to send SMS to ${contact.name}: ${e.message}")
                    allSent = false
                }
            }

            CoroutineScope(Dispatchers.Main).launch {
                val message = if (allSent)
                    getString(R.string.tts_sos_sent)
                else
                    "SOS sent to some contacts. Check phone signal."

                audio.speak(message, SpeechPriority.CRITICAL)
                onSosTriggered?.invoke()
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // Position Handler
    // ─────────────────────────────────────────────────────────────────────

    private fun handlePositionRequest() {
        Log.d(TAG, "Position request from cane")
        CoroutineScope(Dispatchers.IO).launch {
            val locationHelper = LocationHelper(this@CaneMonitorService)
            val location = locationHelper.getLastKnownLocation()

            if (location == null) {
                CoroutineScope(Dispatchers.Main).launch {
                    audio.speak(
                        "Cannot get your position right now. Make sure GPS is on.",
                        SpeechPriority.HIGH
                    )
                }
                return@launch
            }

            // Reverse geocode to street address
            try {
                val geocoder = Geocoder(this@CaneMonitorService, Locale.getDefault())
                val addresses = geocoder.getFromLocation(
                    location.latitude, location.longitude, 1
                )
                val address = addresses?.firstOrNull()
                val spokenAddress = when {
                    address == null -> "Coordinates ${location.latitude}, ${location.longitude}"
                    address.thoroughfare != null ->
                        "You are on ${address.thoroughfare}" +
                                if (address.locality != null) ", ${address.locality}" else ""
                    address.locality != null -> "You are in ${address.locality}"
                    else -> "Coordinates ${location.latitude}, ${location.longitude}"
                }

                CoroutineScope(Dispatchers.Main).launch {
                    audio.speak(spokenAddress, SpeechPriority.HIGH)
                    onPositionRequested?.invoke(spokenAddress)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Geocoding failed: ${e.message}")
                CoroutineScope(Dispatchers.Main).launch {
                    audio.speak(
                        "You are at coordinates ${location.latitude}, ${location.longitude}",
                        SpeechPriority.HIGH
                    )
                }
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // Battery Handler
    // ─────────────────────────────────────────────────────────────────────

    private fun handleBatteryUpdate(message: String) {
        // Expected format: "BAT:75" (percentage)
        val level = message.removePrefix(CMD_BATTERY)
            .removePrefix(":").trim().toIntOrNull() ?: return

        caneBatteryLevel = level
        Log.d(TAG, "Cane battery: $level%")

        onBatteryUpdate?.invoke(level)

        // Warn if low
        if (level <= 15) {
            CoroutineScope(Dispatchers.Main).launch {
                audio.speak(
                    "Warning. Smart Cane battery is at $level percent. Please charge soon.",
                    SpeechPriority.HIGH
                )
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // Bluetooth State Receiver
    // ─────────────────────────────────────────────────────────────────────

    private val bluetoothStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == BluetoothAdapter.ACTION_STATE_CHANGED) {
                val state = intent.getIntExtra(
                    BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR
                )
                if (state == BluetoothAdapter.STATE_OFF) {
                    CoroutineScope(Dispatchers.Main).launch {
                        audio.speak(
                            "Bluetooth turned off. Smart Cane disconnected.",
                            SpeechPriority.CRITICAL
                        )
                    }
                }
            }
        }
    }

    private fun registerBluetoothReceiver() {
        val filter = IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED)
        registerReceiver(bluetoothStateReceiver, filter)
    }

    // ─────────────────────────────────────────────────────────────────────
    // Vibration Alert (tactile backup)
    // ─────────────────────────────────────────────────────────────────────

    private fun vibrateAlert() {
        val vibrator = getSystemService(Vibrator::class.java)
        val pattern = longArrayOf(0, 500, 200, 500, 200, 500)
        vibrator?.vibrate(
            VibrationEffect.createWaveform(pattern, -1)
        )
    }
}