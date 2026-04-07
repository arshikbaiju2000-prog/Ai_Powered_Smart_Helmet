package com.example.testkotlinapp

import android.content.Intent
import android.os.Bundle
import android.widget.LinearLayout
import androidx.appcompat.app.AppCompatActivity

class SettingsActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        findViewById<LinearLayout>(R.id.llPreferences).setOnClickListener {
            startActivity(Intent(this, PreferencesActivity::class.java))
        }

        findViewById<LinearLayout>(R.id.llAppearance).setOnClickListener {
            startActivity(Intent(this, AppearanceActivity::class.java))
        }

        findViewById<LinearLayout>(R.id.llPrivacy).setOnClickListener {
            startActivity(Intent(this, PrivacyActivity::class.java))
        }
    }
}
