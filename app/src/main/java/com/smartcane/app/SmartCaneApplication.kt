package com.smartcane.app

import android.app.Application
import com.smartcane.app.data.db.AppDatabase
import com.smartcane.app.data.repository.ContactRepository
import com.smartcane.app.data.repository.TripHistoryRepository
import com.smartcane.app.managers.AudioFeedbackManager
import com.smartcane.app.managers.SpeechManager
import com.smartcane.app.managers.TalkBackDetector
import dagger.hilt.android.HiltAndroidApp

@HiltAndroidApp
class SmartCaneApplication : Application() {

    lateinit var talkBackDetector: TalkBackDetector
    lateinit var audioFeedbackManager: AudioFeedbackManager
    lateinit var speechManager: SpeechManager

    // Database
    val database by lazy { AppDatabase.getDatabase(this) }
    val contactRepository by lazy { ContactRepository(database.contactDao()) }
    val tripHistoryRepository by lazy { TripHistoryRepository(database.tripHistoryDao()) }

    override fun onCreate() {
        super.onCreate()
        talkBackDetector = TalkBackDetector(this)
        audioFeedbackManager = AudioFeedbackManager(this, talkBackDetector)
        audioFeedbackManager.setSpeechRate(0.9f)
        speechManager = SpeechManager(this, audioFeedbackManager)
    }
}
