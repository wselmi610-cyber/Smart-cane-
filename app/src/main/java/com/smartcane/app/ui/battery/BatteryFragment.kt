package com.smartcane.app.ui.battery

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import com.smartcane.app.R
import com.smartcane.app.SmartCaneApplication
import com.smartcane.app.databinding.FragmentBatteryBinding
import com.smartcane.app.managers.AppStateManager
import com.smartcane.app.managers.SpeechPriority

class BatteryFragment : Fragment() {

    private var _binding: FragmentBatteryBinding? = null
    private val binding get() = _binding!!

    private val app by lazy { requireActivity().application as SmartCaneApplication }
    private val audio by lazy { app.audioFeedbackManager }
    private val speech by lazy { app.speechManager }

    private val refreshHandler = Handler(Looper.getMainLooper())
    private val REFRESH_INTERVAL = 30_000L // refresh every 30 seconds

    private val refreshRunnable = object : Runnable {
        override fun run() {
            readAndUpdateBattery(announce = false)
            refreshHandler.postDelayed(this, REFRESH_INTERVAL)
        }
    }

    // Battery broadcast receiver
    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == Intent.ACTION_BATTERY_CHANGED) {
                updatePhoneBatteryFromIntent(intent, announce = false)
            }
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentBatteryBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupButtons()
    }

    override fun onResume() {
        super.onResume()
        audio.stopSpeaking()

        requireContext().registerReceiver(
            batteryReceiver,
            IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        )

        val state = app.appStateManager
        val visits = state.getVisitCount(AppStateManager.Screen.BATTERY)
        state.recordVisit(AppStateManager.Screen.BATTERY)

        // Only announce on first and second visit this session
        readAndUpdateBattery(announce = visits < 2)
        refreshHandler.postDelayed(refreshRunnable, REFRESH_INTERVAL)
        startVoiceListening()
    }

    override fun onPause() {
        super.onPause()
        speech.stopListening()
        audio.stopSpeaking()
        refreshHandler.removeCallbacks(refreshRunnable)
        try {
            requireContext().unregisterReceiver(batteryReceiver)
        } catch (e: Exception) { }
    }

    // ─────────────────────────────────────────────────────────────────────
    // Battery Reading
    // ─────────────────────────────────────────────────────────────────────

    private fun readAndUpdateBattery(announce: Boolean) {
        val intent = requireContext().registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        if (intent != null) {
            updatePhoneBatteryFromIntent(intent, announce)
        }
        updateCaneBattery(announce)
    }

    private fun updatePhoneBatteryFromIntent(intent: Intent, announce: Boolean) {
        if (!isAdded) return

        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        val percent = if (level >= 0 && scale > 0) (level * 100 / scale) else -1

        val isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                status == BatteryManager.BATTERY_STATUS_FULL
        val isFull = status == BatteryManager.BATTERY_STATUS_FULL

        val statusText = when {
            isFull -> "Full"
            isCharging -> "Charging"
            percent <= 15 -> "Low"
            percent <= 30 -> "Fair"
            else -> "Good"
        }

        val indicatorColor = when {
            isFull || percent > 50 -> 0xFF00FF00.toInt()  // Green
            percent > 20 -> 0xFFFFAA00.toInt()             // Orange
            else -> 0xFFFF2200.toInt()                      // Red
        }

        binding.tvPhoneBatteryPercent.text = if (percent >= 0) "$percent%" else "--"
        binding.tvPhoneBatteryStatus.text = statusText
        binding.phoneBatteryIndicator.setIndicatorColor(indicatorColor)
        if (percent >= 0) {
            binding.phoneBatteryIndicator.progress = percent
        }

        if (announce && percent >= 0) {
            announceFullStatus(percent, statusText, isCharging)
        }
    }

    private fun updateCaneBattery(announce: Boolean) {
        if (!isAdded) return

        // Check if cane service is available via MainActivity
        val mainActivity = activity
        val caneService = try {
            val field = mainActivity?.javaClass?.getDeclaredField("caneService")
            field?.isAccessible = true
            field?.get(mainActivity)
        } catch (e: Exception) { null }

        val caneBatteryLevel = try {
            val method = caneService?.javaClass?.getMethod("getCaneBatteryLevel")
            method?.invoke(caneService) as? Int ?: -1
        } catch (e: Exception) { -1 }

        val isCaneConnected = try {
            val method = caneService?.javaClass?.getMethod("getIsCaneConnected")
            method?.invoke(caneService) as? Boolean ?: false
        } catch (e: Exception) { false }

        if (isCaneConnected && caneBatteryLevel >= 0) {
            val caneStatus = when {
                caneBatteryLevel > 50 -> "Good"
                caneBatteryLevel > 20 -> "Fair"
                else -> "Low"
            }
            val caneColor = when {
                caneBatteryLevel > 50 -> 0xFF00AAFF.toInt()
                caneBatteryLevel > 20 -> 0xFFFFAA00.toInt()
                else -> 0xFFFF2200.toInt()
            }
            binding.tvCaneBatteryPercent.text = "$caneBatteryLevel%"
            binding.tvCaneBatteryStatus.text = caneStatus
            binding.caneBatteryIndicator.progress = caneBatteryLevel
            binding.caneBatteryIndicator.setIndicatorColor(caneColor)
            binding.tvCaneConnection.text = "● Cane Connected"
            binding.tvCaneConnection.setTextColor(
                resources.getColor(android.R.color.holo_green_light, null)
            )
        } else {
            binding.tvCaneBatteryPercent.text = "--"
            binding.tvCaneBatteryStatus.text = "Disconnected"
            binding.caneBatteryIndicator.progress = 0
            binding.tvCaneConnection.text = "● Cane Disconnected"
            binding.tvCaneConnection.setTextColor(
                resources.getColor(android.R.color.holo_red_light, null)
            )
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // Voice Announcement
    // ─────────────────────────────────────────────────────────────────────

    private fun announceFullStatus(
        phonePercent: Int,
        phoneStatus: String,
        isCharging: Boolean
    ) {
        val visits = app.appStateManager
            .getVisitCount(AppStateManager.Screen.BATTERY)

        val message = when {
            visits <= 1 -> {
                // First time — full
                val chargingText = if (isCharging) ", charging" else ""
                val caneText = getCaneStatusText()
                "Phone: $phonePercent percent. $phoneStatus$chargingText. $caneText"
            }
            else -> {
                // Repeat — just the number
                "Phone $phonePercent percent."
            }
        }

        audio.speak(message, SpeechPriority.NORMAL)
    }

    private fun getCaneStatusText(): String {
        val caneService = try {
            val field = activity?.javaClass?.getDeclaredField("caneService")
            field?.isAccessible = true
            field?.get(activity)
        } catch (e: Exception) { null }

        val isCaneConnected = try {
            val method = caneService?.javaClass?.getMethod("getIsCaneConnected")
            method?.invoke(caneService) as? Boolean ?: false
        } catch (e: Exception) { false }

        val caneBatteryLevel = try {
            val method = caneService?.javaClass?.getMethod("getCaneBatteryLevel")
            method?.invoke(caneService) as? Int ?: -1
        } catch (e: Exception) { -1 }

        return if (isCaneConnected && caneBatteryLevel >= 0) {
            "Smart Cane battery: $caneBatteryLevel percent."
        } else {
            "Smart Cane: not connected."
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // Voice Listening
    // ─────────────────────────────────────────────────────────────────────

    private fun startVoiceListening() {
        if (!isAdded) return
        speech.startListening(
            onResult = { result ->
                val command = result.text.lowercase().trim()
                when {
                    command.containsAny("refresh", "update", "reload", "again") -> {
                        audio.speak("Refreshing battery status.", SpeechPriority.NORMAL)
                        readAndUpdateBattery(announce = true)
                    }
                    command.containsAny("back", "home", "cancel") -> {
                        goBackHome()
                    }
                    else -> {
                        audio.speak(
                            "Say refresh to update, or back to go home.",
                            SpeechPriority.NORMAL
                        )
                        startVoiceListening()
                    }
                }
            },
            onError = {
                if (isAdded) startVoiceListening()
            }
        )
    }

    // ─────────────────────────────────────────────────────────────────────
    // Buttons
    // ─────────────────────────────────────────────────────────────────────

    private fun setupButtons() {
        binding.btnRefreshBattery.setOnClickListener {
            speech.stopListening()
            audio.speak("Refreshing.", SpeechPriority.NORMAL)
            readAndUpdateBattery(announce = true)
        }
        binding.btnBatteryBack.setOnClickListener {
            goBackHome()
        }
    }

    private fun goBackHome() {
        speech.stopListening()
        audio.stopSpeaking()
        refreshHandler.removeCallbacks(refreshRunnable)
        findNavController().navigate(R.id.homeFragment)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}

private fun String.containsAny(vararg keywords: String): Boolean =
    keywords.any { this.contains(it) }
