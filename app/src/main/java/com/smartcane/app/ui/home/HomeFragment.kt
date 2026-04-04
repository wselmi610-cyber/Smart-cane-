package com.smartcane.app.ui.home

import android.os.Bundle
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

class HomeFragment : Fragment() {

    private var _binding: FragmentHomeBinding? = null
    private val binding get() = _binding!!

    private val app by lazy { requireActivity().application as SmartCaneApplication }
    private val audio by lazy { app.audioFeedbackManager }
    private val speech by lazy { app.speechManager }

    // Flag to prevent error callback from firing after intentional navigation
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
        // Delay listening slightly to let TTS start first
        binding.root.postDelayed({
            if (isAdded && !intentionalNavigation) {
                startVoiceListening()
            }
        }, 1500)
    }

    override fun onPause() {
        super.onPause()
        // Stop everything cleanly when leaving screen
        speech.stopListening()
        audio.stopSpeaking()
    }

    // ─────────────────────────────────────────────────────────────────────
    // Voice Listening
    // ─────────────────────────────────────────────────────────────────────

    private fun startVoiceListening() {
        if (intentionalNavigation) return
        speech.startListening(
            onResult = { result ->
                if (!intentionalNavigation) handleVoiceCommand(result)
            },
            onError = {
                // Only announce "returning to home" if we didn't navigate on purpose
                if (!intentionalNavigation && isAdded) {
                    // Already on home screen — just restart listening silently
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
            command.containsAny("navigate", "navigation", "go to", "directions") ->
                goToNavigation()
            command.containsAny("family", "call", "contact", "contacts", "phone") ->
                goToFamily()
            command.containsAny("history", "trips", "previous", "past") ->
                goToHistory()
            command.containsAny("battery", "power", "charge", "level") ->
                goToBattery()
            else -> {
                audio.speak(
                    "I didn't understand. Say Navigate, Call family, History, or Battery.",
                    SpeechPriority.NORMAL
                )
                binding.root.postDelayed({
                    if (isAdded && !intentionalNavigation) startVoiceListening()
                }, 2500)
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // Button Setup
    // ─────────────────────────────────────────────────────────────────────

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
    }

    /**
     * Cleanly stops speech + listening before any navigation.
     * Prevents the error callback from firing after tap.
     */
    private fun stopAndNavigate(action: () -> Unit) {
        intentionalNavigation = true
        speech.stopListening()
        audio.stopSpeaking()
        action()
    }

    // ─────────────────────────────────────────────────────────────────────
    // Navigation
    // ─────────────────────────────────────────────────────────────────────

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

    // ─────────────────────────────────────────────────────────────────────
    // Cane Status UI
    // ─────────────────────────────────────────────────────────────────────

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
