package com.smartcane.app

import android.app.Application
import com.smartcane.app.data.db.AppDatabase
import com.smartcane.app.data.repository.ContactRepository
import com.smartcane.app.data.repository.ReminderRepository  // ← ADD THIS
import com.smartcane.app.data.repository.TripHistoryRepository
import com.smartcane.app.managers.AppStateManager
import com.smartcane.app.managers.AudioFeedbackManager
import com.smartcane.app.managers.SpeechManager
import com.smartcane.app.managers.TalkBackDetector
import dagger.hilt.android.HiltAndroidApp

@HiltAndroidApp
class SmartCaneApplication : Application() {

    lateinit var talkBackDetector: TalkBackDetector
    lateinit var audioFeedbackManager: AudioFeedbackManager
    lateinit var speechManager: SpeechManager

    val database by lazy { AppDatabase.getDatabase(this) }
    val contactRepository by lazy { ContactRepository(database.contactDao()) }
    val tripHistoryRepository by lazy { TripHistoryRepository(database.tripHistoryDao()) }
    val reminderRepository by lazy { ReminderRepository(database.reminderDao()) }  // ← already correct
    val appStateManager by lazy { AppStateManager(this) }

    override fun onCreate() {
        super.onCreate()
        talkBackDetector = TalkBackDetector(this)
        audioFeedbackManager = AudioFeedbackManager(this, talkBackDetector)
        audioFeedbackManager.setSpeechRate(0.9f)
        speechManager = SpeechManager(this, audioFeedbackManager)
    }

    fun reinitIfNeeded() {
        if (!audioFeedbackManager.isReady()) {
            audioFeedbackManager.reinitialize()
        }
    }
}