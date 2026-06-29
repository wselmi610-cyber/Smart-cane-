package com.smartcane.app.service
import kotlinx.coroutines.withContext
import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.util.Log
import com.smartcane.app.SmartCaneApplication
import com.smartcane.app.managers.AudioFeedbackManager
import com.smartcane.app.managers.SpeechPriority
import com.smartcane.app.managers.SpeechManager
import kotlinx.coroutines.*
import java.util.Calendar

class ReminderService : Service() {

    private val TAG = "ReminderService"
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private lateinit var audio: AudioFeedbackManager
    private lateinit var speech: SpeechManager

    // Reminder currently being checked
    private var pendingReminderId: Int = -1
    private var pendingReminderTask: String = ""

    override fun onCreate() {
        super.onCreate()
        val app = application as SmartCaneApplication
        audio = app.audioFeedbackManager
        speech = app.speechManager
        startChecking()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    override fun onBind(p0: Intent?): IBinder? = null

    // ── Check reminders every 30 seconds ─────────────────────────────────
    private fun startChecking() {
        serviceScope.launch {
            while (true) {
                checkReminders()
                delay(30_000) // check every 30 seconds
            }
        }
    }

    private fun checkReminders() {
        serviceScope.launch(Dispatchers.IO) {
            val app = application as SmartCaneApplication
            val reminders = app.reminderRepository.getAllActiveSync()

            val calendar = Calendar.getInstance()
            val currentHour = calendar.get(Calendar.HOUR_OF_DAY)
            val currentMinute = calendar.get(Calendar.MINUTE)

            reminders.forEach { reminder ->
                if (reminder.hour == currentHour && reminder.minute == currentMinute) {
                    Log.d(TAG, "Firing reminder: ${reminder.task}")
                    withContext(Dispatchers.Main) {
                        fireReminder(reminder.id, reminder.task)
                    }
                }
            }
        }
    }

    // ── Fire reminder notification ────────────────────────────────────────
    private fun fireReminder(reminderId: Int, task: String) {
        pendingReminderId = reminderId
        pendingReminderTask = task

        serviceScope.launch(Dispatchers.Main) {
            // First notification
            audio.speak(
                "Reminder! $task.",
                SpeechPriority.CRITICAL
            )

            // After 2 minutes — ask if done
            delay(120_000)
            askIfDone(reminderId, task)
        }
    }

    // ── Ask user if task is done ──────────────────────────────────────────
    private fun askIfDone(reminderId: Int, task: String) {
        serviceScope.launch(Dispatchers.Main) {
            audio.speak(
                "Did you $task? Say YES if done, or NO to remind you again in 15 minutes.",
                SpeechPriority.CRITICAL
            )

            delay(1500)

            speech.startListening(
                onResult = { result ->
                    val answer = result.text.lowercase().trim()
                    when {
                        answer.containsAny("yes", "done", "ok", "oui", "fait") -> {
                            serviceScope.launch {
                                val app = application as SmartCaneApplication
                                app.reminderRepository.markDone(reminderId)
                            }
                            audio.speak(
                                "Great! $task marked as done.",
                                SpeechPriority.HIGH
                            )
                        }
                        answer.containsAny("no", "not yet", "non", "pas encore") -> {
                            audio.speak(
                                "Ok, I will remind you again in 15 minutes.",
                                SpeechPriority.HIGH
                            )
                            // Reschedule in 15 minutes
                            serviceScope.launch(Dispatchers.Main) {
                                delay(900_000) // 15 minutes
                                fireReminder(reminderId, task)
                            }
                        }
                        else -> {
                            // Did not understand — remind again in 15 min
                            audio.speak(
                                "I did not understand. I will remind you again in 15 minutes.",
                                SpeechPriority.HIGH
                            )
                            serviceScope.launch(Dispatchers.Main) {
                                delay(900_000)
                                fireReminder(reminderId, task)
                            }
                        }
                    }
                },
                onError = {
                    // Could not hear — remind again in 15 min
                    serviceScope.launch(Dispatchers.Main) {
                        delay(900_000)
                        fireReminder(reminderId, task)
                    }
                }
            )
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
    }
}

private fun String.containsAny(vararg keywords: String): Boolean =
    keywords.any { this.contains(it) }
