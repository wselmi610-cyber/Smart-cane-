package com.smartcane.app.service

import android.annotation.SuppressLint
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
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.location.Geocoder
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.VibrationEffect
import android.os.Vibrator
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
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

// ── Cane BLE UUIDs ────────────────────────────────────────────────────
private val CANE_SERVICE_UUID =
    UUID.fromString("0000ffe0-0000-1000-8000-00805f9b34fb")
private val CANE_CHARACTERISTIC_UUID =
    UUID.fromString("0000ffe1-0000-1000-8000-00805f9b34fb")
private val CCCD_UUID =
    UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

// ── Cane command bytes ─────────────────────────────────────────────────
private const val CMD_SOS = "SOS"
private const val CMD_POSITION = "POS"
private const val CMD_BATTERY = "BAT"

class CaneMonitorService : Service() {

    // ── Binder ────────────────────────────────────────────────────────
    inner class CaneBinder : Binder() {
        fun getService(): CaneMonitorService = this@CaneMonitorService
    }
    private val binder = CaneBinder()
    override fun onBind(intent: Intent): IBinder = binder

    // ── App singletons ────────────────────────────────────────────────
    private val app by lazy { application as SmartCaneApplication }
    private val audio by lazy { app.audioFeedbackManager }

    // ── Bluetooth ─────────────────────────────────────────────────────
    private var bluetoothGatt: BluetoothGatt? = null
    private var isConnected = false
    private var reconnectAttempts = 0
    private val maxReconnect = 5

    // ── State ─────────────────────────────────────────────────────────
    var caneBatteryLevel: Int = -1
        private set
    var isCaneConnected: Boolean = false
        private set

    // ── Callbacks ─────────────────────────────────────────────────────
    var onCaneConnected: (() -> Unit)? = null
    var onCaneDisconnected: (() -> Unit)? = null
    var onBatteryUpdate: ((Int) -> Unit)? = null
    var onSosTriggered: (() -> Unit)? = null
    var onPositionRequested: ((String) -> Unit)? = null

    companion object {
        private const val TAG = "CaneMonitorService"
        const val CHANNEL_ID = "SmartCaneChannel"
        const val NOTIFICATION_ID = 1
    }

    // ─────────────────────────────────────────────────────────────────
    // Lifecycle
    // ─────────────────────────────────────────────────────────────────

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        
        // Android 14+ requires explicit foreground service type
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID, 
                buildNotification("Smart Cane active"),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
            )
        } else {
            startForeground(NOTIFICATION_ID, buildNotification("Smart Cane active"))
        }
        
        registerBluetoothReceiver()
        Log.d(TAG, "CaneMonitorService created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        disconnectCane()
        try { unregisterReceiver(bluetoothStateReceiver) } catch (e: Exception) { }
        Log.d(TAG, "CaneMonitorService destroyed")
    }

    // ─────────────────────────────────────────────────────────────────
    // Notification
    // ─────────────────────────────────────────────────────────────────

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Smart Cane Monitor",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Monitors Smart Cane connection and alerts"
        }
        getSystemService(NotificationManager::class.java)
            .createNotificationChannel(channel)
    }

    private fun buildNotification(message: String): Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent, PendingIntent.FLAG_IMMUTABLE
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
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, buildNotification(message))
    }

    // ─────────────────────────────────────────────────────────────────
    // Bluetooth — Permission Helper
    // ─────────────────────────────────────────────────────────────────

    private fun hasBluetoothPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ContextCompat.checkSelfPermission(
                this, android.Manifest.permission.BLUETOOTH_CONNECT
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true // Older Android — permission not required at runtime
        }
    }

    // ─────────────────────────────────────────────────────────────────
    // Bluetooth Connection
    // ─────────────────────────────────────────────────────────────────

    @SuppressLint("MissingPermission")
    fun connectToCane(device: BluetoothDevice) {
        if (!hasBluetoothPermission()) {
            Log.e(TAG, "Missing Bluetooth permission")
            return
        }
        Log.d(TAG, "Connecting to cane: ${device.address}")
        bluetoothGatt = device.connectGatt(this, false, gattCallback)
    }

    fun disconnectCane() {
        if (hasBluetoothPermission()) {
            try {
                bluetoothGatt?.disconnect()
                bluetoothGatt?.close()
            } catch (e: Exception) {
                Log.e(TAG, "Disconnect error: ${e.message}")
            }
        }
        bluetoothGatt = null
        isConnected = false
        isCaneConnected = false
    }

    @SuppressLint("MissingPermission")
    private val gattCallback = object : BluetoothGattCallback() {

        override fun onConnectionStateChange(
            gatt: BluetoothGatt, status: Int, newState: Int
        ) {
            if (!hasBluetoothPermission()) return

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
            if (!hasBluetoothPermission()) return
            if (status == BluetoothGatt.GATT_SUCCESS) {
                enableNotifications(gatt)
            }
        }

        // Android 13+ callback
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray
        ) {
            val message = String(value).trim()
            Log.d(TAG, "Cane message: $message")
            handleCaneMessage(message)
        }

        // Legacy callback for older Android
        @Suppress("DEPRECATION")
        @Deprecated("Deprecated in Java")
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic
        ) {
            val message = String(characteristic.value ?: return).trim()
            Log.d(TAG, "Cane message (legacy): $message")
            handleCaneMessage(message)
        }
    }

    @SuppressLint("MissingPermission")
    private fun enableNotifications(gatt: BluetoothGatt) {
        if (!hasBluetoothPermission()) return

        val service = gatt.getService(CANE_SERVICE_UUID) ?: run {
            Log.e(TAG, "Cane service not found")
            return
        }
        val characteristic = service.getCharacteristic(CANE_CHARACTERISTIC_UUID) ?: run {
            Log.e(TAG, "Cane characteristic not found")
            return
        }
        gatt.setCharacteristicNotification(characteristic, true)

        val descriptor = characteristic.getDescriptor(CCCD_UUID)
        descriptor?.let {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                gatt.writeDescriptor(
                    it, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                )
            } else {
                @Suppress("DEPRECATION")
                it.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                @Suppress("DEPRECATION")
                gatt.writeDescriptor(it)
            }
        }
        Log.d(TAG, "BLE notifications enabled")
    }

    @SuppressLint("MissingPermission")
    private fun attemptReconnect(device: BluetoothDevice) {
        if (reconnectAttempts >= maxReconnect) {
            Log.e(TAG, "Max reconnect attempts reached")
            return
        }
        if (!hasBluetoothPermission()) return

        reconnectAttempts++
        CoroutineScope(Dispatchers.IO).launch {
            Log.d(TAG, "Reconnect attempt $reconnectAttempts/$maxReconnect")
            delay(10_000L)
            if (!isConnected) {
                bluetoothGatt?.close()
                connectToCane(device)
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────
    // Cane Message Handler
    // ─────────────────────────────────────────────────────────────────

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

    // ─────────────────────────────────────────────────────────────────
    // SOS Handler
    // ─────────────────────────────────────────────────────────────────

    private fun handleSOS() {
        Log.d(TAG, "SOS triggered")
        vibrateAlert()

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

            val locationHelper = LocationHelper(this@CaneMonitorService)
            val location = locationHelper.getLastKnownLocation()

            val smsBody = if (location != null) {
                "🆘 SOS Alert! Your family member needs help.\n" +
                        "Location: https://maps.google.com/" +
                        "?q=${location.latitude},${location.longitude}"
            } else {
                "🆘 SOS Alert! Your family member needs help.\n" +
                        "Location unavailable."
            }

            val smsManager = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                getSystemService(android.telephony.SmsManager::class.java)
            } else {
                @Suppress("DEPRECATION")
                android.telephony.SmsManager.getDefault()
            }

            contacts.forEach { contact ->
                try {
                    smsManager?.sendTextMessage(
                        contact.phoneNumber, null, smsBody, null, null
                    )
                    Log.d(TAG, "SOS SMS sent to ${contact.name}")
                } catch (e: Exception) {
                    Log.e(TAG, "SMS failed to ${contact.name}: ${e.message}")
                }
            }

            CoroutineScope(Dispatchers.Main).launch {
                audio.speak(
                    getString(R.string.tts_sos_sent),
                    SpeechPriority.CRITICAL
                )
                onSosTriggered?.invoke()
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────
    // Position Handler
    // ─────────────────────────────────────────────────────────────────

    private fun handlePositionRequest() {
        Log.d(TAG, "Position request")
        CoroutineScope(Dispatchers.IO).launch {
            val locationHelper = LocationHelper(this@CaneMonitorService)
            val location = locationHelper.getLastKnownLocation()

            if (location == null) {
                CoroutineScope(Dispatchers.Main).launch {
                    audio.speak(
                        "Cannot get your position. Make sure GPS is on.",
                        SpeechPriority.HIGH
                    )
                }
                return@launch
            }

            try {
                val geocoder = Geocoder(this@CaneMonitorService, Locale.getDefault())

                val addresses = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    // Modern non-deprecated approach
                    var result: List<android.location.Address>? = null
                    geocoder.getFromLocation(
                        location.latitude, location.longitude, 1
                    ) { addresses -> result = addresses }
                    // Small wait for callback
                    delay(2000)
                    result
                } else {
                    @Suppress("DEPRECATION")
                    geocoder.getFromLocation(
                        location.latitude, location.longitude, 1
                    )
                }

                val address = addresses?.firstOrNull()
                val spokenAddress = when {
                    address == null ->
                        "Coordinates ${location.latitude}, ${location.longitude}"
                    address.thoroughfare != null ->
                        "You are on ${address.thoroughfare}" +
                                if (address.locality != null) ", ${address.locality}" else ""
                    address.locality != null ->
                        "You are in ${address.locality}"
                    else ->
                        "Coordinates ${location.latitude}, ${location.longitude}"
                }

                CoroutineScope(Dispatchers.Main).launch {
                    audio.speak(spokenAddress, SpeechPriority.HIGH)
                    onPositionRequested?.invoke(spokenAddress)
                }

            } catch (e: Exception) {
                Log.e(TAG, "Geocoding failed: ${e.message}")
                CoroutineScope(Dispatchers.Main).launch {
                    audio.speak(
                        "You are at ${location.latitude}, ${location.longitude}",
                        SpeechPriority.HIGH
                    )
                }
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────
    // Battery Handler
    // ─────────────────────────────────────────────────────────────────

    private fun handleBatteryUpdate(message: String) {
        val level = message.removePrefix(CMD_BATTERY)
            .removePrefix(":").trim().toIntOrNull() ?: return

        caneBatteryLevel = level
        Log.d(TAG, "Cane battery: $level%")
        onBatteryUpdate?.invoke(level)

        if (level <= 15) {
            CoroutineScope(Dispatchers.Main).launch {
                audio.speak(
                    "Warning. Cane battery at $level percent. Please charge soon.",
                    SpeechPriority.HIGH
                )
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────
    // Bluetooth State Receiver
    // ─────────────────────────────────────────────────────────────────

    private fun registerBluetoothReceiver() {
        val filter = IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED)
        registerReceiver(bluetoothStateReceiver, filter)
    }

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

    // ─────────────────────────────────────────────────────────────────
    // Vibration
    // ─────────────────────────────────────────────────────────────────

    private fun vibrateAlert() {
        val vibrator = getSystemService(Vibrator::class.java)
        val pattern = longArrayOf(0, 500, 200, 500, 200, 500)
        vibrator?.vibrate(VibrationEffect.createWaveform(pattern, -1))
    }
}
