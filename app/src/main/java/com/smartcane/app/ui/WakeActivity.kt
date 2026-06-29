package com.smartcane.app.ui

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.appcompat.app.AppCompatActivity
import com.smartcane.app.MainActivity
import com.smartcane.app.R
import com.smartcane.app.SmartCaneApplication
import com.smartcane.app.managers.SpeechPriority

class WakeActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Show over lock screen — full takeover like a phone call
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                        WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                        WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
            )
        }

        setContentView(R.layout.activity_wake)

        // Speak instruction
        try {
            val app = application as SmartCaneApplication
            app.audioFeedbackManager.speak(
                "Smart Cane. Tap anywhere to open.",
                SpeechPriority.CRITICAL
            )
        } catch (e: Exception) { }

        // Tap anywhere opens MainActivity
        findViewById<android.view.View>(R.id.wakeRootLayout)
            .setOnClickListener {
                openMainApp()
            }

        // Auto open after 3 seconds even without tap
        window.decorView.postDelayed({
            if (!isFinishing) openMainApp()
        }, 3000L)
    }

    private fun openMainApp() {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra("WAKE_WORD_TRIGGERED", true)
        }
        startActivity(intent)
        finish()
    }
}