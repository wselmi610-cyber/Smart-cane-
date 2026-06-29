package com.smartcane.app.ui.home

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.telephony.SmsManager
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import com.smartcane.app.R
import com.smartcane.app.SmartCaneApplication
import com.smartcane.app.databinding.FragmentHomeBinding
import com.smartcane.app.managers.SpeechPriority
import com.smartcane.app.managers.SpeechResult
import com.smartcane.app.service.LocationHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class HomeFragment : Fragment() {

    private var _binding: FragmentHomeBinding? = null
    private val binding get() = _binding!!

    private val app by lazy { requireActivity().application as SmartCaneApplication }
    private val audio by lazy { app.audioFeedbackManager }
    private val speech by lazy { app.speechManager }

    private var intentionalNavigation = false

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentHomeBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupButtons()
        setupCaneStatusObserver()
    }

    override fun onResume() {
        super.onResume()
        intentionalNavigation = false

        audio.speak(
            getString(R.string.tts_home_ready),
            SpeechPriority.NORMAL
        )

        binding.root.postDelayed({
            if (isAdded && !intentionalNavigation) {
                startVoiceListening()
            }
        }, 1500)

        if (!isAccessibilityEnabled()) {
            binding.root.postDelayed({
                if (isAdded) {
                    audio.speak(
                        "Please enable Smart Cane accessibility service " +
                                "for Hey Cane to work.",
                        SpeechPriority.LOW
                    )
                    binding.root.postDelayed({
                        if (isAdded) openAccessibilitySettings()
                    }, 3000L)
                }
            }, 4000L)
        }
    }

    override fun onPause() {
        super.onPause()
        speech.stopListening()
        audio.stopSpeaking()
    }

    private fun isAccessibilityEnabled(): Boolean {
        val service = "${requireContext().packageName}/" +
                "com.smartcane.app.service.CaneAccessibilityService"
        try {
            val enabled = android.provider.Settings.Secure.getInt(
                requireContext().contentResolver,
                android.provider.Settings.Secure.ACCESSIBILITY_ENABLED
            )
            if (enabled == 1) {
                val services = android.provider.Settings.Secure.getString(
                    requireContext().contentResolver,
                    android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
                )
                return services?.contains(service) == true
            }
        } catch (e: Exception) { }
        return false
    }

    private fun openAccessibilitySettings() {
        try {
            val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
            startActivity(intent)
        } catch (e: Exception) { }
    }

    private fun startVoiceListening() {
        if (intentionalNavigation) return
        speech.startListening(
            onResult = { result ->
                if (!intentionalNavigation) handleVoiceCommand(result)
            },
            onError = {
                if (!intentionalNavigation && isAdded) {
                    binding.root.postDelayed({
                        if (isAdded && !intentionalNavigation) startVoiceListening()
                    }, 500)
                }
            }
        )
    }

    private fun handleVoiceCommand(result: SpeechResult) {
        val command = result.text.lowercase().trim()
        when {
            command.containsAny("navigate", "navigation", "go to", "directions",
                "naviguer", "itinéraire", "aller") ->
                goToNavigation()

            command.containsAny("family", "call", "contact", "contacts", "phone",
                "famille", "appeler") ->
                goToFamily()

            command.containsAny("history", "trips", "previous", "past",
                "historique", "voyages") ->
                goToHistory()

            command.containsAny("battery", "power", "charge", "level",
                "batterie") ->
                goToBattery()

            // ── Help / SOS ────────────────────────────────────────────────
            command.containsAny(
                "help", "sos", "emergency", "danger", "save me",
                "aide", "secours", "urgence", "au secours"
            ) -> sendHelpSMS()

            // ── Time ──────────────────────────────────────────────────────
            command.containsAny(
                "time", "heure", "what time", "quelle heure", "clock"
            ) -> speakCurrentTime()

            // ── Date ──────────────────────────────────────────────────────
            command.containsAny(
                "date", "day", "today", "jour", "aujourd",
                "quel jour", "يوم", "التاريخ", "اليوم"
            ) -> speakCurrentDate()

            // ── Time AND Date together ─────────────────────────────────────
            command.containsAny(
                "time and date", "date and time",
                "heure et date", "jour et heure"
            ) -> {

                speakCurrentTime()
                binding.root.postDelayed({ speakCurrentDate() }, 3000)
            }
            command.containsAny(
                "reminder", "remind", "reminders",
                "rappel", "rappels"
            ) -> goToReminder()

            else -> {
                audio.speak("I didn't understand.", SpeechPriority.NORMAL)
                binding.root.postDelayed({
                    if (isAdded && !intentionalNavigation) startVoiceListening()
                }, 2500)
            }
        }
    }

    // ── HELP / SOS ────────────────────────────────────────────────────────
    private fun sendHelpSMS() {
        audio.speak(
            "Sending help message to all contacts.",
            SpeechPriority.CRITICAL
        )

        CoroutineScope(Dispatchers.IO).launch {
            val contacts = app.contactRepository.getAllContactsSync()

            if (contacts.isEmpty()) {
                withContext(Dispatchers.Main) {
                    audio.speak(
                        "No contacts found. Please add contacts first.",
                        SpeechPriority.HIGH
                    )
                    binding.root.postDelayed({
                        if (isAdded && !intentionalNavigation) startVoiceListening()
                    }, 3000)
                }
                return@launch
            }

            // Get GPS location
            val location = try {
                LocationHelper(requireContext()).getLastKnownLocation()
            } catch (e: Exception) { null }

            // Build SMS message
            val message = if (location != null) {
                "HELP ME! I need assistance. My location: " +
                        "https://maps.google.com/?q=${location.latitude},${location.longitude}"
            } else {
                "HELP ME! I need assistance. Please contact me immediately."
            }

            // Send to all contacts
            var sentCount = 0
            contacts.forEach { contact ->
                try {
                    val smsManager = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        requireContext().getSystemService(SmsManager::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        SmsManager.getDefault()
                    }
                    smsManager.sendTextMessage(
                        contact.phoneNumber, null, message, null, null
                    )
                    sentCount++
                } catch (e: Exception) {
                    Log.e("HomeFragment", "SMS failed to ${contact.phoneNumber}: ${e.message}")
                }
            }

            val finalCount = sentCount
            withContext(Dispatchers.Main) {
                audio.speak(
                    "Help message sent to $finalCount contacts.",
                    SpeechPriority.CRITICAL
                )
                binding.root.postDelayed({
                    if (isAdded && !intentionalNavigation) startVoiceListening()
                }, 3000)
            }
        }
    }

    // ── TIME & DATE ───────────────────────────────────────────────────────
    private fun speakCurrentTime() {
        val calendar = java.util.Calendar.getInstance()
        val hour = calendar.get(java.util.Calendar.HOUR_OF_DAY)
        val minute = calendar.get(java.util.Calendar.MINUTE)
        val amPm = if (hour < 12) "AM" else "PM"
        val hour12 = when {
            hour == 0 -> 12
            hour > 12 -> hour - 12
            else -> hour
        }
        val minuteStr = if (minute < 10) "0$minute" else "$minute"
        audio.speak("It is $hour12 : $minuteStr $amPm.", SpeechPriority.HIGH)
        binding.root.postDelayed({
            if (isAdded && !intentionalNavigation) startVoiceListening()
        }, 3000)
    }

    private fun speakCurrentDate() {
        val calendar = java.util.Calendar.getInstance()
        val dayName = java.text.SimpleDateFormat(
            "EEEE", java.util.Locale.getDefault()
        ).format(calendar.time)
        val day = calendar.get(java.util.Calendar.DAY_OF_MONTH)
        val month = java.text.SimpleDateFormat(
            "MMMM", java.util.Locale.getDefault()
        ).format(calendar.time)
        val year = calendar.get(java.util.Calendar.YEAR)
        audio.speak("Today is $dayName, $day $month $year.", SpeechPriority.HIGH)
        binding.root.postDelayed({
            if (isAdded && !intentionalNavigation) startVoiceListening()
        }, 3000)
    }

    // ── NAVIGATION ────────────────────────────────────────────────────────
    private fun setupButtons() {
        binding.btnNavigate.setOnClickListener {
            stopAndNavigate { goToNavigation() }
        }
        binding.btnFamily.setOnClickListener {
            stopAndNavigate { goToFamily() }
        }
        binding.btnHistory.setOnClickListener {
            stopAndNavigate { goToHistory() }
        }
        binding.btnBattery.setOnClickListener {
            stopAndNavigate { goToBattery() }
        }
        binding.btnReminder.setOnClickListener {       // ← ADD THIS
            stopAndNavigate { goToReminder() }
        }
    }

    private fun stopAndNavigate(action: () -> Unit) {
        intentionalNavigation = true
        speech.stopListening()
        audio.stopSpeaking()
        action()
    }

    private fun goToNavigation() {
        findNavController().navigate(R.id.action_home_to_navigation)
    }

    private fun goToFamily() {
        findNavController().navigate(R.id.action_home_to_family)
    }

    private fun goToHistory() {
        findNavController().navigate(R.id.action_home_to_history)
    }

    private fun goToBattery() {
        findNavController().navigate(R.id.action_home_to_battery)
    }
    private fun goToReminder() {
        findNavController().navigate(R.id.action_home_to_reminder)
    }

    private fun setupCaneStatusObserver() {
        updateCaneStatus(false)
    }

    fun updateCaneStatus(connected: Boolean) {
        if (!isAdded) return
        if (connected) {
            binding.tvCaneStatus.text = "● Connected"
            binding.tvCaneStatus.setTextColor(
                resources.getColor(android.R.color.holo_green_light, null)
            )
            binding.tvCaneStatus.setBackgroundResource(R.drawable.bg_status_pill_green)
        } else {
            binding.tvCaneStatus.text = "● Disconnected"
            binding.tvCaneStatus.setTextColor(
                resources.getColor(R.color.status_danger, null)
            )
            binding.tvCaneStatus.setBackgroundResource(R.drawable.bg_status_pill_red)
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}

private fun String.containsAny(vararg keywords: String): Boolean =
    keywords.any { this.contains(it) }