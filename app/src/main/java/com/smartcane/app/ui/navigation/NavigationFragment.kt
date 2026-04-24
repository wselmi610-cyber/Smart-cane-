package com.smartcane.app.ui.navigation

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import com.smartcane.app.R
import com.smartcane.app.SmartCaneApplication
import com.smartcane.app.databinding.FragmentNavigationBinding
import com.smartcane.app.managers.AppStateManager
import com.smartcane.app.managers.SpeechPriority
import com.smartcane.app.managers.SpeechResult
import com.smartcane.app.service.LocationHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class NavigationFragment : Fragment() {

    private var _binding: FragmentNavigationBinding? = null
    private val binding get() = _binding!!

    private val app by lazy { requireActivity().application as SmartCaneApplication }
    private val audio by lazy { app.audioFeedbackManager }
    private val speech by lazy { app.speechManager }

    // Flag so onResume doesn't restart after Maps returns
    private var mapsLaunched = false

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentNavigationBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.btnBackHome.setOnClickListener {
            goBackHome()
        }
    }

    override fun onResume() {
        super.onResume()
        audio.stopSpeaking() // Fix C

        if (mapsLaunched) {
            handleReturnFromMaps()
        } else {
            startNavigationFlow()
        }
    }

    override fun onPause() {
        super.onPause()
        if (!mapsLaunched) {
            speech.stopListening()
            audio.stopSpeaking()
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // Navigation Flow
    // ─────────────────────────────────────────────────────────────────────

    private fun startNavigationFlow() {
        mapsLaunched = false
        showListeningIndicator(false)

        val state = app.appStateManager
        val visits = state.getVisitCount(AppStateManager.Screen.NAVIGATION)
        state.recordVisit(AppStateManager.Screen.NAVIGATION)

        val prompt = when (visits) {
            0    -> "Where do you want to go?"  // First time
            1    -> "Where to?"                  // Second time
            else -> null                         // Third time+ — silent
        }

        val delay = if (prompt != null) {
            prompt.let { audio.speak(it, SpeechPriority.NORMAL) }
            1500L
        } else {
            500L
        }

        setStatus(
            if (prompt != null) "Listening..." else "🎤",
            "#FFFF00"
        )

        binding.root.postDelayed({
            if (isAdded && !mapsLaunched) {
                showListeningIndicator(true)
                startListeningForDestination()
            }
        }, delay)
    }

    private fun startListeningForDestination() {
        speech.startListening(
            onResult = { result -> handleDestinationResult(result) },
            onError = {
                // Max retries reached — go back home
                if (isAdded) {
                    showListeningIndicator(false)
                    setStatus("Returning to home...", "#FF4444")
                    binding.root.postDelayed({ goBackHome() }, 1500)
                }
            }
        )
    }

    private fun handleDestinationResult(result: SpeechResult) {
        if (!isAdded) return
        showListeningIndicator(false)

        val destination = result.text.trim()
        showDestination(destination)
        setStatus("Starting navigation...", "#00FF00")

        // Confirm then launch Maps
        audio.speak(
            "Going to $destination. Starting navigation.",
            SpeechPriority.HIGH
        )

        // Save trip to history
        CoroutineScope(Dispatchers.IO).launch {
            app.tripHistoryRepository.saveTrip(destination)
        }

        // Small delay to let TTS speak before launching Maps
        binding.root.postDelayed({
            if (isAdded) launchGoogleMaps(destination)
        }, 2500)
    }

    // ─────────────────────────────────────────────────────────────────────
    // Google Maps Launch
    // ─────────────────────────────────────────────────────────────────────

    private fun launchGoogleMaps(destination: String) {
        CoroutineScope(Dispatchers.Main).launch {
            // Try to get current GPS position
            val locationHelper = LocationHelper(requireContext())
            val location = try {
                locationHelper.getLastKnownLocation()
            } catch (e: Exception) {
                null
            }

            val mapsUri = if (location != null) {
                // With current position → most accurate routing
                Uri.parse(
                    "https://www.google.com/maps/dir/?api=1" +
                            "&origin=${location.latitude},${location.longitude}" +
                            "&destination=${Uri.encode(destination)}" +
                            "&travelmode=walking"
                )
            } else {
                // Without position → Maps uses device location automatically
                Uri.parse(
                    "https://www.google.com/maps/dir/?api=1" +
                            "&destination=${Uri.encode(destination)}" +
                            "&travelmode=walking"
                )
            }

            val mapsIntent = Intent(Intent.ACTION_VIEW, mapsUri).apply {
                setPackage("com.google.android.apps.maps")
            }

            // Check if Google Maps is installed
            val packageManager = requireActivity().packageManager
            if (mapsIntent.resolveActivity(packageManager) != null) {
                mapsLaunched = true
                startActivity(mapsIntent)
            } else {
                // Google Maps not installed — open in browser
                mapsLaunched = true
                val browserIntent = Intent(Intent.ACTION_VIEW, mapsUri)
                startActivity(browserIntent)
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // Return from Maps
    // ─────────────────────────────────────────────────────────────────────

    private fun handleReturnFromMaps() {
        mapsLaunched = false
        setStatus("Welcome back", "#00FF00")
        showListeningIndicator(false)

        val visits = app.appStateManager
            .getVisitCount(AppStateManager.Screen.NAVIGATION)

        when {
            visits <= 1 -> audio.speak("Welcome back.", SpeechPriority.NORMAL)
            visits == 2 -> audio.speak("Home.", SpeechPriority.NORMAL)
            else        -> { /* silent */ }
        }

        binding.root.postDelayed({
            if (isAdded) goBackHome()
        }, if (visits <= 2) 1500L else 500L)
    }

    // ─────────────────────────────────────────────────────────────────────
    // Go Back Home
    // ─────────────────────────────────────────────────────────────────────

    private fun goBackHome() {
        speech.stopListening()
        audio.stopSpeaking()
        findNavController().navigate(R.id.homeFragment)
    }

    // ─────────────────────────────────────────────────────────────────────
    // UI Helpers
    // ─────────────────────────────────────────────────────────────────────

    private fun setStatus(message: String, colorHex: String) {
        if (!isAdded) return
        binding.tvNavStatus.text = message
        binding.tvNavStatus.setTextColor(android.graphics.Color.parseColor(colorHex))
    }

    private fun showDestination(destination: String) {
        if (!isAdded) return
        binding.tvDestination.text = "📍 $destination"
        binding.tvDestination.visibility = View.VISIBLE
    }

    private fun hideDestination() {
        if (!isAdded) return
        binding.tvDestination.visibility = View.GONE
    }

    private fun showListeningIndicator(show: Boolean) {
        if (!isAdded) return
        binding.tvListeningIndicator.visibility =
            if (show) View.VISIBLE else View.GONE
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
