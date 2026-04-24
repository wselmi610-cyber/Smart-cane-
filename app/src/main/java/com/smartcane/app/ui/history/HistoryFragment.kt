package com.smartcane.app.ui.history

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import com.smartcane.app.R
import com.smartcane.app.SmartCaneApplication
import com.smartcane.app.data.model.TripHistory
import com.smartcane.app.databinding.FragmentHistoryBinding
import com.smartcane.app.managers.AppStateManager
import com.smartcane.app.managers.SpeechPriority
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class HistoryFragment : Fragment() {

    private var _binding: FragmentHistoryBinding? = null
    private val binding get() = _binding!!

    private val app by lazy { requireActivity().application as SmartCaneApplication }
    private val audio by lazy { app.audioFeedbackManager }
    private val speech by lazy { app.speechManager }

    private var trips = listOf<TripHistory>()
    private var currentIndex = 0
    private var isConfirmingClearAll = false

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentHistoryBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupButtons()
    }

    override fun onResume() {
        super.onResume()
        isConfirmingClearAll = false
        currentIndex = 0
        loadTripsThenAnnounce()
    }

    override fun onPause() {
        super.onPause()
        speech.stopListening()
        audio.stopSpeaking()
    }

    // ─────────────────────────────────────────────────────────────────────
    // Load & Announce
    // ─────────────────────────────────────────────────────────────────────

    private fun loadTripsThenAnnounce() {
        viewLifecycleOwner.lifecycleScope.launch {
            trips = app.tripHistoryRepository.getRecentTrips()
            currentIndex = 0
            updateUI()
            announceCurrentState()
        }
    }

    private fun announceCurrentState() {
        val state = app.appStateManager
        val visits = state.getVisitCount(AppStateManager.Screen.HISTORY)
        state.recordVisit(AppStateManager.Screen.HISTORY)

        if (trips.isEmpty()) {
            if (visits == 0) audio.speak("No trips yet.", SpeechPriority.NORMAL)
            listenForCommands()
            return
        }

        val trip = trips[currentIndex]

        val message = when (visits) {
            0 -> {
                // First time — full
                "Trip history. ${trips.size} trip${if (trips.size > 1) "s" else ""}. " +
                        "Trip ${currentIndex + 1}: ${trip.destination}. " +
                        "${formatDate(trip.timestamp)}. " +
                        "Say next, previous, navigate again, delete, or clear all."
            }
            1 -> {
                // Second time — medium
                "Trip ${currentIndex + 1} of ${trips.size}. ${trip.destination}."
            }
            else -> {
                // Third time+ — destination only
                trip.destination
            }
        }

        audio.speak(message, SpeechPriority.NORMAL)
        listenForCommands()
    }
    // ─────────────────────────────────────────────────────────────────────
    // Voice Commands
    // ─────────────────────────────────────────────────────────────────────

    private fun listenForCommands() {
        if (!isAdded) return
        showListening(true)
        speech.startListening(
            onResult = { result ->
                showListening(false)
                handleVoiceCommand(result.text.lowercase().trim())
            },
            onError = {
                showListening(false)
                if (isAdded) listenForCommands()
            }
        )
    }

    private fun handleVoiceCommand(command: String) {
        if (isConfirmingClearAll) {
            handleClearAllConfirmation(command)
            return
        }

        when {
            command.containsAny("next", "suivant", "التالي") -> {
                goToNextTrip()
            }
            command.containsAny("previous", "prev", "précédent", "السابق", "back trip") -> {
                goToPreviousTrip()
            }
            command.containsAny("navigate again", "navigate", "go again", "go there") -> {
                navigateToCurrentTrip()
            }
            command.containsAny("delete", "remove", "supprimer") -> {
                deleteCurrentTrip()
            }
            command.containsAny("clear all", "delete all", "tout supprimer", "clear history") -> {
                askClearAllConfirmation()
            }
            command.containsAny("back", "home", "cancel") -> {
                goBackHome()
            }
            else -> {
                audio.speak(
                    "Say next, previous, navigate again, delete, clear all, or back.",
                    SpeechPriority.NORMAL
                )
                listenForCommands()
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // Trip Navigation
    // ─────────────────────────────────────────────────────────────────────

    private fun goToNextTrip() {
        if (trips.isEmpty()) return
        if (currentIndex < trips.size - 1) {
            currentIndex++
            updateUI()
            val trip = trips[currentIndex]
            audio.speak(
                "Trip ${currentIndex + 1} of ${trips.size}. " +
                        "${trip.destination}. ${formatDate(trip.timestamp)}.",
                SpeechPriority.NORMAL
            )
        } else {
            audio.speak("This is the last trip.", SpeechPriority.NORMAL)
        }
        listenForCommands()
    }

    private fun goToPreviousTrip() {
        if (trips.isEmpty()) return
        if (currentIndex > 0) {
            currentIndex--
            updateUI()
            val trip = trips[currentIndex]
            audio.speak(
                "Trip ${currentIndex + 1} of ${trips.size}. " +
                        "${trip.destination}. ${formatDate(trip.timestamp)}.",
                SpeechPriority.NORMAL
            )
        } else {
            audio.speak("This is the first trip.", SpeechPriority.NORMAL)
        }
        listenForCommands()
    }

    // ─────────────────────────────────────────────────────────────────────
    // Navigate Again
    // ─────────────────────────────────────────────────────────────────────

    private fun navigateToCurrentTrip() {
        if (trips.isEmpty()) return
        val destination = trips[currentIndex].destination
        speech.stopListening()
        audio.speak("Starting navigation to $destination.", SpeechPriority.HIGH)

        // Save to history again
        viewLifecycleOwner.lifecycleScope.launch {
            app.tripHistoryRepository.saveTrip(destination)
        }

        binding.root.postDelayed({
            if (!isAdded) return@postDelayed
            val mapsUri = Uri.parse(
                "https://www.google.com/maps/dir/?api=1" +
                        "&destination=${Uri.encode(destination)}" +
                        "&travelmode=walking"
            )
            val mapsIntent = Intent(Intent.ACTION_VIEW, mapsUri).apply {
                setPackage("com.google.android.apps.maps")
            }
            try {
                startActivity(mapsIntent)
            } catch (e: Exception) {
                startActivity(Intent(Intent.ACTION_VIEW, mapsUri))
            }
        }, 2000)
    }

    // ─────────────────────────────────────────────────────────────────────
    // Delete Current Trip
    // ─────────────────────────────────────────────────────────────────────

    private fun deleteCurrentTrip() {
        if (trips.isEmpty()) return
        val trip = trips[currentIndex]
        audio.speak(
            "Delete trip to ${trip.destination}? Say yes or no.",
            SpeechPriority.NORMAL
        )
        showListening(true)
        speech.startListening(
            onResult = { result ->
                showListening(false)
                val answer = result.text.lowercase().trim()
                when {
                    answer.containsAny("yes", "yeah", "delete", "correct") -> {
                        viewLifecycleOwner.lifecycleScope.launch {
                            app.tripHistoryRepository.deleteTrip(trip)
                            trips = app.tripHistoryRepository.getRecentTrips()
                            if (currentIndex >= trips.size) {
                                currentIndex = (trips.size - 1).coerceAtLeast(0)
                            }
                            updateUI()
                            if (trips.isEmpty()) {
                                audio.speak("Trip deleted. No more trips.", SpeechPriority.NORMAL)
                            } else {
                                val current = trips[currentIndex]
                                audio.speak(
                                    "Deleted. Now showing trip ${currentIndex + 1} " +
                                            "of ${trips.size}: ${current.destination}.",
                                    SpeechPriority.NORMAL
                                )
                            }
                            listenForCommands()
                        }
                    }
                    answer.containsAny("no", "cancel", "keep") -> {
                        audio.speak("Cancelled. Trip kept.", SpeechPriority.NORMAL)
                        listenForCommands()
                    }
                    else -> {
                        audio.speak("Please say yes or no.", SpeechPriority.NORMAL)
                        deleteCurrentTrip()
                    }
                }
            },
            onError = {
                showListening(false)
                audio.speak("No answer heard. Trip kept.", SpeechPriority.NORMAL)
                listenForCommands()
            }
        )
    }

    // ─────────────────────────────────────────────────────────────────────
    // Clear All
    // ─────────────────────────────────────────────────────────────────────

    private fun askClearAllConfirmation() {
        isConfirmingClearAll = true
        audio.speak(
            "Delete all ${trips.size} trips? This cannot be undone. Say yes or no.",
            SpeechPriority.NORMAL
        )
        listenForCommands()
    }

    private fun handleClearAllConfirmation(answer: String) {
        when {
            answer.containsAny("yes", "yeah", "clear", "delete", "correct") -> {
                isConfirmingClearAll = false
                viewLifecycleOwner.lifecycleScope.launch {
                    app.tripHistoryRepository.clearAllTrips()
                    trips = emptyList()
                    currentIndex = 0
                    updateUI()
                    audio.speak("All trips cleared.", SpeechPriority.NORMAL)
                    listenForCommands()
                }
            }
            answer.containsAny("no", "cancel", "keep") -> {
                isConfirmingClearAll = false
                audio.speak("Cancelled. History kept.", SpeechPriority.NORMAL)
                listenForCommands()
            }
            else -> {
                audio.speak("Please say yes or no.", SpeechPriority.NORMAL)
                listenForCommands()
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // UI Update
    // ─────────────────────────────────────────────────────────────────────

    private fun updateUI() {
        if (!isAdded) return

        if (trips.isEmpty()) {
            binding.layoutTripCard.visibility = View.GONE
            binding.tvEmptyHistory.visibility = View.VISIBLE
            binding.btnPrevTrip.isEnabled = false
            binding.btnNextTrip.isEnabled = false
            binding.btnClearAll.isEnabled = false
            binding.btnDeleteTrip.isEnabled = false
            binding.btnNavigateAgain.isEnabled = false
        } else {
            val trip = trips[currentIndex]
            binding.layoutTripCard.visibility = View.VISIBLE
            binding.tvEmptyHistory.visibility = View.GONE
            binding.tvTripIndex.text = "Trip ${currentIndex + 1} of ${trips.size}"
            binding.tvTripDestination.text = trip.destination
            binding.tvTripDate.text = formatDate(trip.timestamp)
            binding.btnPrevTrip.isEnabled = currentIndex > 0
            binding.btnNextTrip.isEnabled = currentIndex < trips.size - 1
            binding.btnClearAll.isEnabled = true
            binding.btnDeleteTrip.isEnabled = true
            binding.btnNavigateAgain.isEnabled = true
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // Buttons
    // ─────────────────────────────────────────────────────────────────────

    private fun setupButtons() {
        binding.btnNextTrip.setOnClickListener { goToNextTrip() }
        binding.btnPrevTrip.setOnClickListener { goToPreviousTrip() }
        binding.btnNavigateAgain.setOnClickListener { navigateToCurrentTrip() }
        binding.btnDeleteTrip.setOnClickListener { deleteCurrentTrip() }
        binding.btnClearAll.setOnClickListener { askClearAllConfirmation() }
        binding.btnHistoryBack.setOnClickListener { goBackHome() }
    }

    // ─────────────────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────────────────

    private fun formatDate(timestamp: Long): String {
        val now = System.currentTimeMillis()
        val diff = now - timestamp
        val date = Date(timestamp)

        return when {
            diff < 60_000 -> "Just now"
            diff < 3_600_000 -> "${diff / 60_000} minutes ago"
            diff < 86_400_000 -> "Today at ${
                SimpleDateFormat("HH:mm", Locale.getDefault()).format(date)}"
            diff < 172_800_000 -> "Yesterday at ${
                SimpleDateFormat("HH:mm", Locale.getDefault()).format(date)}"
            else -> SimpleDateFormat("dd MMM, HH:mm", Locale.getDefault()).format(date)
        }
    }

    private fun showListening(show: Boolean) {
        if (!isAdded) return
        binding.tvHistoryListening.visibility =
            if (show) View.VISIBLE else View.GONE
    }

    private fun goBackHome() {
        speech.stopListening()
        audio.stopSpeaking()
        findNavController().navigate(R.id.homeFragment)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}

private fun String.containsAny(vararg keywords: String): Boolean =
    keywords.any { this.contains(it) }
