package com.smartcane.app.ui.family

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import com.smartcane.app.R
import com.smartcane.app.SmartCaneApplication
import com.smartcane.app.data.model.Contact
import com.smartcane.app.databinding.FragmentFamilyBinding
import com.smartcane.app.managers.SpeechPriority
import kotlinx.coroutines.launch

enum class FamilyFlowState {
    IDLE,
    WAITING_NAME,
    WAITING_NUMBER,
    CONFIRMING_SAVE,    // NEW: confirm before saving
    WAITING_DELETE_NAME // NEW: waiting for name to delete
}

class FamilyFragment : Fragment() {

    private var _binding: FragmentFamilyBinding? = null
    private val binding get() = _binding!!

    private val app by lazy { requireActivity().application as SmartCaneApplication }
    private val audio by lazy { app.audioFeedbackManager }
    private val speech by lazy { app.speechManager }

    private lateinit var contactAdapter: ContactAdapter
    private var currentContacts = listOf<Contact>()
    private var flowState = FamilyFlowState.IDLE
    private var pendingContactName = ""
    private var pendingContactNumber = ""

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentFamilyBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupRecyclerView()
        setupButtons()
        observeContacts()
    }

    override fun onResume() {
        super.onResume()
        flowState = FamilyFlowState.IDLE
        pendingContactName = ""
        pendingContactNumber = ""
        // Don't announce immediately — wait for DB to emit first
        loadContactsThenAnnounce()
    }

    private fun loadContactsThenAnnounce() {
        viewLifecycleOwner.lifecycleScope.launch {
            // Get contacts directly (one-shot) then announce
            val contacts = app.contactRepository.getAllContactsOnce()
            currentContacts = contacts
            contactAdapter.submitList(contacts)
            updateEmptyState(contacts.isEmpty())
            announceScreenAndListen()
        }
    }

    override fun onPause() {
        super.onPause()
        speech.stopListening()
        audio.stopSpeaking()
    }

    // ─────────────────────────────────────────────────────────────────────
    // Setup
    // ─────────────────────────────────────────────────────────────────────

    private fun setupRecyclerView() {
        contactAdapter = ContactAdapter(
            onCall = { contact -> makeCall(contact) },
            onDelete = { contact -> confirmDeleteContact(contact) }
        )
        binding.rvContacts.apply {
            layoutManager = androidx.recyclerview.widget.LinearLayoutManager(requireContext())
            adapter = contactAdapter
        }
    }

    private fun setupButtons() {
        binding.btnAddContact.setOnClickListener {
            speech.stopListening()
            startAddContactFlow()
        }
        binding.btnFamilyBack.setOnClickListener {
            goBackHome()
        }
    }

    private fun observeContacts() {
        viewLifecycleOwner.lifecycleScope.launch {
            app.contactRepository.allContacts.collect { contacts ->
                currentContacts = contacts
                contactAdapter.submitList(contacts)
                updateEmptyState(contacts.isEmpty())
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // Announce Screen
    // ─────────────────────────────────────────────────────────────────────

    private fun announceScreenAndListen() {
        val message = if (currentContacts.isEmpty()) {
            "Family contacts. No contacts saved. Say Add contact."
        } else {
            val names = currentContacts.joinToString(", ") { it.name }
            "Family contacts. ${currentContacts.size} contact" +
                    "${if (currentContacts.size > 1) "s" else ""}: $names. " +
                    "Say a name to call, Add contact to add, " +
                    "or Delete and a name to remove."
        }
        audio.speak(message, SpeechPriority.NORMAL)
        listenForCommand()
    }

    // ─────────────────────────────────────────────────────────────────────
    // Central Listener — routes based on flowState
    // ─────────────────────────────────────────────────────────────────────

    private fun listenForCommand() {
        if (!isAdded) return
        showListening(true)
        speech.startListening(
            onResult = { result ->
                showListening(false)
                when (flowState) {
                    FamilyFlowState.IDLE              -> handleCommandInput(result.text)
                    FamilyFlowState.WAITING_NAME      -> handleNameInput(result.text)
                    FamilyFlowState.WAITING_NUMBER    -> handleNumberInput(result.text)
                    FamilyFlowState.CONFIRMING_SAVE   -> handleSaveConfirmation(result.text)
                    FamilyFlowState.WAITING_DELETE_NAME -> handleDeleteNameInput(result.text)
                }
            },
            onError = {
                showListening(false)
                if (flowState == FamilyFlowState.IDLE && isAdded) {
                    listenForCommand()
                }
            }
        )
    }

    // ─────────────────────────────────────────────────────────────────────
    // IDLE — Command Handler
    // ─────────────────────────────────────────────────────────────────────

    private fun handleCommandInput(command: String) {
        val lower = command.lowercase().trim()
        when {
            lower.containsAny("add", "new", "create") -> {
                startAddContactFlow()
            }
            lower.containsAny("delete", "remove", "erase") -> {
                startDeleteContactFlow(lower)
            }
            lower.containsAny("back", "home", "cancel") -> {
                goBackHome()
            }
            else -> {
                val matched = currentContacts.firstOrNull { contact ->
                    lower.contains(contact.name.lowercase())
                }
                if (matched != null) {
                    makeCall(matched)
                } else {
                    audio.speak(
                        "I didn't find that contact. " +
                                "Say a name to call, Add contact, or Delete and a name.",
                        SpeechPriority.NORMAL
                    )
                    listenForCommand()
                }
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // Add Contact Flow
    // ─────────────────────────────────────────────────────────────────────

    private fun startAddContactFlow() {
        flowState = FamilyFlowState.WAITING_NAME
        setStatus("Say the contact name...")
        audio.speak("Say the contact name.", SpeechPriority.NORMAL)
        listenForCommand()
    }

    private fun handleNameInput(spokenName: String) {
        val name = spokenName.trim().replaceFirstChar { it.uppercase() }
        if (name.length < 2) {
            audio.speak("Name too short. Please say the name again.", SpeechPriority.NORMAL)
            listenForCommand()
            return
        }
        pendingContactName = name
        flowState = FamilyFlowState.WAITING_NUMBER
        setStatus("Say the phone number...")
        audio.speak(
            "Name: $name. Now say the phone number digit by digit.",
            SpeechPriority.NORMAL
        )
        listenForCommand()
    }

    private fun handleNumberInput(spokenNumber: String) {
        val digitsOnly = convertSpokenNumberToDigits(spokenNumber)

        if (digitsOnly.length < 6) {
            audio.speak(
                "I heard: $spokenNumber. " +
                        "Got ${digitsOnly.length} digit${if (digitsOnly.length == 1) "" else "s"}. " +
                        "Need at least 6. Please say the number again.",
                SpeechPriority.NORMAL
            )
            listenForCommand()
            return
        }

        pendingContactNumber = digitsOnly
        flowState = FamilyFlowState.CONFIRMING_SAVE
        setStatus("Confirm: $pendingContactName — $digitsOnly")

        audio.speak(
            "Save ${pendingContactName} with number ${digitsOnly.toSpokenDigits()}? " +
                    "Say yes to save, or no to re-enter.",
            SpeechPriority.NORMAL
        )
        listenForCommand()
    }


    // ─────────────────────────────────────────────────────────────────────
    // Save Confirmation
    // ─────────────────────────────────────────────────────────────────────

    private fun handleSaveConfirmation(answer: String) {
        val lower = answer.lowercase().trim()
        when {
            lower.containsAny("yes", "yeah", "correct", "save", "ok") -> {
                saveContact(pendingContactName, pendingContactNumber)
            }
            lower.containsAny("no", "nope", "wrong", "cancel", "retry") -> {
                // Go back to number entry
                flowState = FamilyFlowState.WAITING_NUMBER
                setStatus("Say the phone number again...")
                audio.speak(
                    "Okay. Please say the phone number again.",
                    SpeechPriority.NORMAL
                )
                listenForCommand()
            }
            else -> {
                audio.speak(
                    "Please say yes to save, or no to re-enter.",
                    SpeechPriority.NORMAL
                )
                listenForCommand()
            }
        }
    }

    private fun saveContact(name: String, number: String) {
        viewLifecycleOwner.lifecycleScope.launch {
            app.contactRepository.addContact(name, number)
            flowState = FamilyFlowState.IDLE
            pendingContactName = ""
            pendingContactNumber = ""
            setStatus("Say a name to call, or say Add contact")
            audio.speak(
                "$name saved. Number: ${number.toSpokenDigits()}. " +
                        "Say Delete $name if you want to remove it.",
                SpeechPriority.NORMAL
            )
            listenForCommand()
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // Delete Contact Flow
    // ─────────────────────────────────────────────────────────────────────

    private fun startDeleteContactFlow(command: String) {
        if (currentContacts.isEmpty()) {
            audio.speak("No contacts to delete.", SpeechPriority.NORMAL)
            listenForCommand()
            return
        }

        // Check if name already in command e.g. "delete mom"
        val matchedInline = currentContacts.firstOrNull { contact ->
            command.contains(contact.name.lowercase())
        }

        if (matchedInline != null) {
            confirmDeleteContact(matchedInline)
        } else {
            // Ask which contact to delete
            flowState = FamilyFlowState.WAITING_DELETE_NAME
            val names = currentContacts.joinToString(", ") { it.name }
            audio.speak(
                "Which contact do you want to delete? You have: $names.",
                SpeechPriority.NORMAL
            )
            listenForCommand()
        }
    }

    private fun handleDeleteNameInput(spokenName: String) {
        val lower = spokenName.lowercase().trim()
        val matched = currentContacts.firstOrNull { contact ->
            lower.contains(contact.name.lowercase())
        }
        if (matched != null) {
            confirmDeleteContact(matched)
        } else {
            flowState = FamilyFlowState.IDLE
            audio.speak(
                "I didn't find that contact. Returning to contacts.",
                SpeechPriority.NORMAL
            )
            listenForCommand()
        }
    }

    private fun confirmDeleteContact(contact: Contact) {
        speech.stopListening()
        flowState = FamilyFlowState.IDLE
        setStatus("Confirm delete: ${contact.name}?")

        audio.speak(
            "Delete ${contact.name} with number ${contact.phoneNumber.toSpokenDigits()}? " +
                    "Say yes to delete, or no to cancel.",
            SpeechPriority.NORMAL
        )

        // Listen for yes/no after TTS finishes
        binding.root.postDelayed({
            if (!isAdded) return@postDelayed
            showListening(true)
            speech.startListening(
                onResult = { result ->
                    showListening(false)
                    val answer = result.text.lowercase().trim()
                    when {
                        answer.containsAny("yes", "yeah", "correct", "delete", "ok") -> {
                            deleteContact(contact)
                        }
                        answer.containsAny("no", "nope", "cancel", "keep") -> {
                            setStatus("Say a name to call, or say Add contact")
                            audio.speak("Cancelled. ${contact.name} kept.", SpeechPriority.NORMAL)
                            listenForCommand()
                        }
                        else -> {
                            // Unclear — ask again
                            audio.speak(
                                "Please say yes to delete, or no to cancel.",
                                SpeechPriority.NORMAL
                            )
                            binding.root.postDelayed({
                                if (isAdded) confirmDeleteContact(contact)
                            }, 100)
                        }
                    }
                },
                onError = {
                    showListening(false)
                    // Timeout — cancel delete for safety
                    setStatus("Say a name to call, or say Add contact")
                    audio.speak("No answer heard. ${contact.name} kept.", SpeechPriority.NORMAL)
                    listenForCommand()
                }
            )
        }, 500)
    }
    private fun deleteContact(contact: Contact) {
        viewLifecycleOwner.lifecycleScope.launch {
            app.contactRepository.deleteContact(contact)
            setStatus("Say a name to call, or say Add contact")
            audio.speak("${contact.name} deleted.", SpeechPriority.NORMAL)
            listenForCommand()
        }
    }
    // ─────────────────────────────────────────────────────────────────────
    // Call
    // ─────────────────────────────────────────────────────────────────────

    private fun makeCall(contact: Contact) {
        speech.stopListening()
        flowState = FamilyFlowState.IDLE
        audio.speak(
            "Calling ${contact.name}. " +
                    "Number: ${contact.phoneNumber.toSpokenDigits()}.",
            SpeechPriority.HIGH
        )
        binding.root.postDelayed({
            if (!isAdded) return@postDelayed
            try {
                val intent = Intent(Intent.ACTION_CALL).apply {
                    data = Uri.parse("tel:${contact.phoneNumber}")
                }
                startActivity(intent)
            } catch (e: Exception) {
                audio.speak(
                    "Could not make call. Check phone permissions.",
                    SpeechPriority.HIGH
                )
            }
        }, 2000)
    }

    // ─────────────────────────────────────────────────────────────────────
    // Number Word → Digit Converter
    // ─────────────────────────────────────────────────────────────────────

    private fun convertSpokenNumberToDigits(spoken: String): String {
        var result = spoken.lowercase().trim()

        // ── English ───────────────────────────────────────────────────────
        result = result.replace(Regex("\\bzero\\b"), "0")
        result = result.replace(Regex("\\bone\\b"), "1")
        result = result.replace(Regex("\\btwo\\b"), "2")
        result = result.replace(Regex("\\bthree\\b"), "3")
        result = result.replace(Regex("\\bfour\\b"), "4")
        result = result.replace(Regex("\\bfive\\b"), "5")
        result = result.replace(Regex("\\bsix\\b"), "6")
        result = result.replace(Regex("\\bseven\\b"), "7")
        result = result.replace(Regex("\\beight\\b"), "8")
        result = result.replace(Regex("\\bnine\\b"), "9")
        result = result.replace(Regex("\\boh\\b"), "0")

        // ── French ────────────────────────────────────────────────────────
        result = result.replace(Regex("\\bzéro\\b"), "0")
        result = result.replace(Regex("\\bzero\\b"), "0")
        result = result.replace(Regex("\\bun\\b"), "1")
        result = result.replace(Regex("\\bune\\b"), "1")
        result = result.replace(Regex("\\bdeux\\b"), "2")
        result = result.replace(Regex("\\btrois\\b"), "3")
        result = result.replace(Regex("\\bquatre\\b"), "4")
        result = result.replace(Regex("\\bcinq\\b"), "5")
        result = result.replace(Regex("\\bsix\\b"), "6")
        result = result.replace(Regex("\\bsept\\b"), "7")
        result = result.replace(Regex("\\bhuit\\b"), "8")
        result = result.replace(Regex("\\bneuf\\b"), "9")

        // ── French teens & tens (common in Tunisian phone numbers) ────────
        result = result.replace(Regex("\\bvingt\\b"), "20")
        result = result.replace(Regex("\\btrente\\b"), "30")
        result = result.replace(Regex("\\bquarante\\b"), "40")
        result = result.replace(Regex("\\bcinquante\\b"), "50")
        result = result.replace(Regex("\\bsoixante\\b"), "60")
        result = result.replace(Regex("\\bsoixante-dix\\b"), "70")
        result = result.replace(Regex("\\bsoixante dix\\b"), "70")
        result = result.replace(Regex("\\bquatre-vingt\\b"), "80")
        result = result.replace(Regex("\\bquatre vingt\\b"), "80")
        result = result.replace(Regex("\\bquatre-vingt-dix\\b"), "90")
        result = result.replace(Regex("\\bquatre vingt dix\\b"), "90")
        result = result.replace(Regex("\\bdix\\b"), "10")
        result = result.replace(Regex("\\bonze\\b"), "11")
        result = result.replace(Regex("\\bdouze\\b"), "12")
        result = result.replace(Regex("\\btreize\\b"), "13")
        result = result.replace(Regex("\\bquatorze\\b"), "14")
        result = result.replace(Regex("\\bquinze\\b"), "15")
        result = result.replace(Regex("\\bseize\\b"), "16")

        // ── Arabic (transliterated) ───────────────────────────────────────
        result = result.replace(Regex("\\bsifr\\b"), "0")
        result = result.replace(Regex("\\bwahid\\b"), "1")
        result = result.replace(Regex("\\bwahed\\b"), "1")
        result = result.replace(Regex("\\bwahd\\b"), "1")
        result = result.replace(Regex("\\bithnan\\b"), "2")
        result = result.replace(Regex("\\bitnin\\b"), "2")
        result = result.replace(Regex("\\bzouz\\b"), "2")  // Tunisian dialect
        result = result.replace(Regex("\\bthalatha\\b"), "3")
        result = result.replace(Regex("\\btlatha\\b"), "3") // Tunisian dialect
        result = result.replace(Regex("\\barba\\b"), "4")
        result = result.replace(Regex("\\barba'a\\b"), "4")
        result = result.replace(Regex("\\bkhamsa\\b"), "5")
        result = result.replace(Regex("\\bkhamseh\\b"), "5")
        result = result.replace(Regex("\\bsitta\\b"), "6")
        result = result.replace(Regex("\\bsetta\\b"), "6")
        result = result.replace(Regex("\\bsab'a\\b"), "7")
        result = result.replace(Regex("\\bsab3a\\b"), "7")
        result = result.replace(Regex("\\bsba3\\b"), "7")   // Tunisian dialect
        result = result.replace(Regex("\\bthamanya\\b"), "8")
        result = result.replace(Regex("\\btmanya\\b"), "8") // Tunisian dialect
        result = result.replace(Regex("\\btis'a\\b"), "9")
        result = result.replace(Regex("\\btis3a\\b"), "9")
        result = result.replace(Regex("\\btes3a\\b"), "9")  // Tunisian dialect

        // ── Arabic native digits (in case recognizer returns them) ────────
        result = result.replace("٠", "0")
        result = result.replace("١", "1")
        result = result.replace("٢", "2")
        result = result.replace("٣", "3")
        result = result.replace("٤", "4")
        result = result.replace("٥", "5")
        result = result.replace("٦", "6")
        result = result.replace("٧", "7")
        result = result.replace("٨", "8")
        result = result.replace("٩", "9")

        // ── Keep only digits and + ─────────────────────────────────────────
        val digitsOnly = result.replace(Regex("[^0-9+]"), "")

        Log.d("NumberConvert", "Input: '$spoken' → Output: '$digitsOnly'")
        return digitsOnly
    }

    // ─────────────────────────────────────────────────────────────────────
    // UI Helpers
    // ─────────────────────────────────────────────────────────────────────

    private fun updateEmptyState(isEmpty: Boolean) {
        if (!isAdded) return
        binding.rvContacts.visibility = if (isEmpty) View.GONE else View.VISIBLE
        binding.tvEmptyContacts.visibility = if (isEmpty) View.VISIBLE else View.GONE
    }

    private fun setStatus(message: String) {
        if (!isAdded) return
        binding.tvFamilyStatus.text = message
    }

    private fun showListening(show: Boolean) {
        if (!isAdded) return
        binding.tvFamilyListening.visibility =
            if (show) View.VISIBLE else View.GONE
    }

    private fun goBackHome() {
        speech.stopListening()
        audio.stopSpeaking()
        flowState = FamilyFlowState.IDLE
        findNavController().navigate(R.id.homeFragment)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}

private fun String.containsAny(vararg keywords: String): Boolean =
    keywords.any { this.contains(it) }

private fun String.toSpokenDigits(): String =
    this.toCharArray().joinToString(" ")
