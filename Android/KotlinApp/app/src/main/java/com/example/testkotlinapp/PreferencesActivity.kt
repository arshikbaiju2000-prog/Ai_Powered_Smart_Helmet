package com.example.testkotlinapp

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat

class PreferencesActivity : AppCompatActivity() {

    private lateinit var swNoiseCancellation: SwitchCompat
    private lateinit var prefs: SharedPreferences

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_preferences)

        prefs = getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
        swNoiseCancellation = findViewById(R.id.swNoiseCancellation)

        // Load saved state
        val isEnabled = prefs.getBoolean("noise_cancellation", false)
        swNoiseCancellation.isChecked = isEnabled

        swNoiseCancellation.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean("noise_cancellation", isChecked).apply()
            
            // Broadcast the change so MainActivity or HelmetMonitorService can act on it
            val intent = Intent("com.example.testkotlinapp.NOISE_CANCEL_CHANGED")
            intent.putExtra("enabled", isChecked)
            sendBroadcast(intent)
        }
    }
}
