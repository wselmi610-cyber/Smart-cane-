package com.smartcane.app.ui.reminder
import kotlinx.coroutines.Dispatchers
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import com.smartcane.app.R
import com.smartcane.app.SmartCaneApplication
import com.smartcane.app.data.model.Reminder
import com.smartcane.app.databinding.FragmentReminderBinding
import com.smartcane.app.managers.SpeechPriority
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
enum class ReminderFlowState {
    IDLE,
    WAITING_TASK,
    WAITING_TIME,
    CONFIRMING_SAVE,
    WAITING_DELETE_TASK
}

class ReminderFragment : Fragment() {

    private var _binding: FragmentReminderBinding? = null
    private val binding get() = _binding!!

    private val app by lazy { requireActivity().application as SmartCaneApplication }
    private val audio by lazy { app.audioFeedbackManager }
    private val speech by lazy { app.speechManager }

    private lateinit var reminderAdapter: ReminderAdapter
    private var currentReminders = listOf<Reminder>()
    private var flowState = ReminderFlowState.IDLE
    private var pendingTask = ""
    private var pendingHour = 0
    private var pendingMinute = 0
    private var isConfirming = false  // ← add this with other properties

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentReminderBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupRecyclerView()
        setupButtons()
        observeReminders()
    }

    override fun onResume() {
        super.onResume()
        flowState = ReminderFlowState.IDLE
        pendingTask = ""
        loadRemindersThenAnnounce()
    }

    override fun onPause() {
        super.onPause()
        speech.stopListening()
        audio.stopSpeaking()
    }

    // ── Setup ─────────────────────────────────────────────────────────────
    private fun setupRecyclerView() {
        reminderAdapter = ReminderAdapter(
            onDelete = { reminder -> confirmDeleteReminder(reminder) }
        )
        binding.rvReminders.apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = reminderAdapter
        }
    }

    private fun setupButtons() {
        binding.btnReminderBack.setOnClickListener { goBackHome() }
        binding.btnAddReminder.setOnClickListener {
            speech.stopListening()
            startAddReminderFlow()
        }
    }

    private fun observeReminders() {
        viewLifecycleOwner.lifecycleScope.launch {
            app.reminderRepository.getAllActive().collect { reminders ->
                currentReminders = reminders
                reminderAdapter.submitList(reminders)
                updateEmptyState(reminders.isEmpty())
                updateCount(reminders.size)
            }
        }
    }

    private fun loadRemindersThenAnnounce() {
        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {  // ← add Dispatchers.IO
            val reminders = app.reminderRepository.getAllActiveSync()
            withContext(Dispatchers.Main) {  // ← switch to Main for UI
                currentReminders = reminders
                reminderAdapter.submitList(reminders)
                updateEmptyState(reminders.isEmpty())
                updateCount(reminders.size)
                announceScreenAndListen()
            }
        }
    }

    // ── Announce ──────────────────────────────────────────────────────────
    private fun announceScreenAndListen() {
        val message = if (currentReminders.isEmpty()) {
            "Reminders. No reminders saved. Say Add to create one."
        } else {
            val tasks = currentReminders.joinToString(", ") { reminder ->
                val amPm = if (reminder.hour < 12) "AM" else "PM"
                val h = if (reminder.hour > 12) reminder.hour - 12 else reminder.hour
                "${reminder.task} at $h $amPm"
            }
            "Reminders. ${currentReminders.size} reminder" +
                    "${if (currentReminders.size > 1) "s" else ""}: $tasks. " +
                    "Say ADD to add, DELETE to remove, or BACK to go home."
        }
        audio.speak(message, SpeechPriority.NORMAL)
        listenForCommand()
    }

    // ── Central Listener ──────────────────────────────────────────────────
    private fun listenForCommand() {
        if (!isAdded) return
        showListening(true)
        speech.startListening(
            onResult = { result ->
                showListening(false)
                when (flowState) {
                    ReminderFlowState.IDLE               -> handleCommandInput(result.text)
                    ReminderFlowState.WAITING_TASK       -> handleTaskInput(result.text)
                    ReminderFlowState.WAITING_TIME       -> handleTimeInput(result.text)
                    ReminderFlowState.CONFIRMING_SAVE    -> handleSaveConfirmation(result.text)
                    ReminderFlowState.WAITING_DELETE_TASK -> handleDeleteTaskInput(result.text)
                }
            },
            onError = {
                showListening(false)
                if (flowState == ReminderFlowState.IDLE && isAdded) {
                    listenForCommand()
                }
            }
        )
    }

    // ── IDLE Commands ─────────────────────────────────────────────────────
    private fun handleCommandInput(command: String) {
        val lower = command.lowercase().trim()
        when {
            lower.containsAny("add", "new", "create",
                "ajouter", "nouveau", "rappel") -> startAddReminderFlow()

            lower.containsAny("delete", "remove", "erase",
                "supprimer", "effacer") -> startDeleteReminderFlow(lower)

            lower.containsAny("list", "show", "read",
                "liste", "lire") -> listReminders()

            lower.containsAny("back", "home", "cancel",
                "retour", "annuler") -> goBackHome()

            else -> {
                audio.speak(
                    "Say ADD to add a reminder, DELETE to remove one, or BACK to go home.",
                    SpeechPriority.NORMAL
                )
                listenForCommand()
            }
        }
    }

    // ── Add Reminder Flow ─────────────────────────────────────────────────
    private fun startAddReminderFlow() {
        flowState = ReminderFlowState.WAITING_TASK
        setStatus("Say what to remind you about...")
        audio.speak(
            "What should I remind you about? For example: take Doliprane.",
            SpeechPriority.NORMAL
        )
        listenForCommand()
    }

    private fun handleTaskInput(spoken: String) {
        val task = spoken.trim()
        if (task.length < 2) {
            audio.speak("Too short. Please say the reminder again.", SpeechPriority.NORMAL)
            listenForCommand()
            return
        }
        pendingTask = task
        flowState = ReminderFlowState.WAITING_TIME
        setStatus("Say the time...")
        audio.speak(
            "Reminder: $task. At what time? For example: 8 PM or 14 30.",
            SpeechPriority.NORMAL
        )
        listenForCommand()
    }

    private fun handleTimeInput(spoken: String) {
        val parsed = parseTime(spoken)
        if (parsed == null) {
            audio.speak(
                "I did not understand the time. Try again. Say something like 8 PM or 14 30.",
                SpeechPriority.NORMAL
            )
            listenForCommand()
            return
        }

        pendingHour = parsed.first
        pendingMinute = parsed.second
        flowState = ReminderFlowState.CONFIRMING_SAVE

        val amPm = if (pendingHour < 12) "AM" else "PM"
        val h = when {
            pendingHour == 0 -> 12
            pendingHour > 12 -> pendingHour - 12
            else -> pendingHour
        }
        val min = if (pendingMinute < 10) "0$pendingMinute" else "$pendingMinute"

        setStatus("Confirm: $pendingTask at $h:$min $amPm")
        audio.speak(
            "Save reminder: $pendingTask at $h $min $amPm? Say YES to save or NO to re-enter.",
            SpeechPriority.NORMAL
        )
        listenForCommand()
    }

    private fun handleSaveConfirmation(answer: String) {
        val lower = answer.lowercase().trim()
        when {
            lower.containsAny("yes", "yeah", "ok", "save", "oui") -> {
                saveReminder()
            }
            lower.containsAny("no", "nope", "cancel", "non") -> {
                flowState = ReminderFlowState.WAITING_TIME
                setStatus("Say the time again...")
                audio.speak("Ok. Please say the time again.", SpeechPriority.NORMAL)
                listenForCommand()
            }
            else -> {
                audio.speak("Say YES to save or NO to re-enter.", SpeechPriority.NORMAL)
                listenForCommand()
            }
        }
    }

    private fun saveReminder() {
        viewLifecycleOwner.lifecycleScope.launch {
            app.reminderRepository.insert(
                Reminder(
                    task = pendingTask,
                    hour = pendingHour,
                    minute = pendingMinute
                )
            )
            flowState = ReminderFlowState.IDLE
            val amPm = if (pendingHour < 12) "AM" else "PM"
            val h = if (pendingHour > 12) pendingHour - 12 else pendingHour
            setStatus("Say ADD, DELETE, or BACK")
            audio.speak(
                "Reminder saved. I will remind you to $pendingTask at $h $amPm.",
                SpeechPriority.NORMAL
            )
            pendingTask = ""
            listenForCommand()
        }
    }

    // ── Delete Reminder Flow ──────────────────────────────────────────────
    private fun startDeleteReminderFlow(command: String) {
        if (currentReminders.isEmpty()) {
            audio.speak("No reminders to delete.", SpeechPriority.NORMAL)
            listenForCommand()
            return
        }

        val matchedInline = currentReminders.firstOrNull { reminder ->
            command.contains(reminder.task.lowercase())
        }

        if (matchedInline != null) {
            confirmDeleteReminder(matchedInline)
        } else {
            flowState = ReminderFlowState.WAITING_DELETE_TASK
            val tasks = currentReminders.joinToString(", ") { it.task }
            audio.speak(
                "Which reminder do you want to delete? You have: $tasks.",
                SpeechPriority.NORMAL
            )
            listenForCommand()
        }
    }

    private fun handleDeleteTaskInput(spoken: String) {
        val lower = spoken.lowercase().trim()
        val matched = currentReminders.firstOrNull { reminder ->
            lower.contains(reminder.task.lowercase())
        }
        if (matched != null) {
            confirmDeleteReminder(matched)
        } else {
            flowState = ReminderFlowState.IDLE
            audio.speak("I did not find that reminder.", SpeechPriority.NORMAL)
            listenForCommand()
        }
    }

    private fun confirmDeleteReminder(reminder: Reminder) {
        if (isConfirming) return  // ← prevent double trigger
        isConfirming = true

        speech.stopListening()
        flowState = ReminderFlowState.IDLE

        val amPm = if (reminder.hour < 12) "AM" else "PM"
        val h = if (reminder.hour > 12) reminder.hour - 12 else reminder.hour

        audio.speak(
            "Delete reminder: ${reminder.task} at $h $amPm? Say YES to delete or NO to cancel.",
            SpeechPriority.NORMAL
        )

        binding.root.postDelayed({
            if (!isAdded) {
                isConfirming = false
                return@postDelayed
            }
            showListening(true)
            speech.startListening(
                onResult = { result ->
                    showListening(false)
                    isConfirming = false  // ← reset flag
                    val answer = result.text.lowercase().trim()
                    when {
                        answer.containsAny("yes", "yeah", "ok", "delete", "oui") -> {
                            deleteReminder(reminder)
                        }
                        answer.containsAny("no", "nope", "cancel", "non") -> {
                            setStatus("Say ADD, DELETE, or BACK")
                            audio.speak("Cancelled. Reminder kept.", SpeechPriority.NORMAL)
                            binding.root.postDelayed({
                                if (isAdded) listenForCommand()
                            }, 2000)
                        }
                        else -> {
                            audio.speak("Say YES to delete or NO to cancel.", SpeechPriority.NORMAL)
                            binding.root.postDelayed({
                                if (isAdded) confirmDeleteReminder(reminder)
                            }, 2500)
                        }
                    }
                },
                onError = {
                    showListening(false)
                    isConfirming = false  // ← reset flag
                    audio.speak("No answer heard. Reminder kept.", SpeechPriority.NORMAL)
                    binding.root.postDelayed({
                        if (isAdded) listenForCommand()
                    }, 2000)
                }
            )
        }, 2000)
    }

    private fun deleteReminder(reminder: Reminder) {
        viewLifecycleOwner.lifecycleScope.launch {
            app.reminderRepository.delete(reminder)
            setStatus("Say ADD, DELETE, or BACK")
            audio.speak("Reminder deleted.", SpeechPriority.NORMAL)
            listenForCommand()
        }
    }

    // ── List Reminders ────────────────────────────────────────────────────
    private fun listReminders() {
        if (currentReminders.isEmpty()) {
            audio.speak("You have no active reminders.", SpeechPriority.HIGH)
            binding.root.postDelayed({ if (isAdded) listenForCommand() }, 2000)
            return
        }
        val sb = StringBuilder("You have ${currentReminders.size} reminders. ")
        currentReminders.forEachIndexed { index, reminder ->
            val amPm = if (reminder.hour < 12) "AM" else "PM"
            val h = if (reminder.hour > 12) reminder.hour - 12 else reminder.hour
            val min = if (reminder.minute < 10) "0${reminder.minute}" else "${reminder.minute}"
            sb.append("${index + 1}. ${reminder.task} at $h $min $amPm. ")
        }
        audio.speak(sb.toString(), SpeechPriority.HIGH)
        binding.root.postDelayed({
            if (isAdded) listenForCommand()
        }, currentReminders.size * 3000L + 2000)
    }

    // ── Time Parser ───────────────────────────────────────────────────────
    private fun parseTime(text: String): Pair<Int, Int>? {
        val lower = text.lowercase().trim()
        var hour = -1
        var minute = 0
        val isPM = lower.contains("pm") || lower.contains("p.m")
        val isAM = lower.contains("am") || lower.contains("a.m")

        val clean = lower
            .replace("pm", "").replace("am", "")
            .replace("p.m", "").replace("a.m", "")
            .replace("o'clock", "").trim()

        val colonPattern = Regex("(\\d{1,2})[: ](\\d{2})")
        val colonMatch = colonPattern.find(clean)
        if (colonMatch != null) {
            hour = colonMatch.groupValues[1].toInt()
            minute = colonMatch.groupValues[2].toInt()
        } else {
            val numPattern = Regex("(\\d{1,2})")
            val numMatch = numPattern.find(clean)
            if (numMatch != null) hour = numMatch.groupValues[1].toInt()
        }

        if (hour == -1 || hour > 23 || minute > 59) return null
        if (isPM && hour < 12) hour += 12
        if (isAM && hour == 12) hour = 0

        return Pair(hour, minute)
    }

    // ── UI Helpers ────────────────────────────────────────────────────────
    private fun updateEmptyState(isEmpty: Boolean) {
        if (!isAdded) return
        binding.rvReminders.visibility = if (isEmpty) View.GONE else View.VISIBLE
        binding.layoutEmptyReminders.visibility = if (isEmpty) View.VISIBLE else View.GONE
    }

    private fun updateCount(count: Int) {
        if (!isAdded) return
        binding.tvReminderCount.text = when (count) {
            0 -> "No reminders"
            1 -> "1 reminder"
            else -> "$count reminders"
        }
    }

    private fun setStatus(message: String) {
        if (!isAdded) return
        binding.tvReminderStatus.text = message
    }

    private fun showListening(show: Boolean) {
        if (!isAdded) return
        binding.tvReminderListening.visibility =
            if (show) View.VISIBLE else View.GONE
    }

    private fun goBackHome() {
        speech.stopListening()
        audio.stopSpeaking()
        flowState = ReminderFlowState.IDLE
        findNavController().navigate(R.id.homeFragment)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}

private fun String.containsAny(vararg keywords: String): Boolean =
    keywords.any { this.contains(it) }