package com.example.testkotlinapp
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import androidx.appcompat.app.AppCompatActivity

class SplashActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_splash)  // Link to splash screen layout

        // Handler to delay execution for 2 seconds (2000 ms)
        Handler().postDelayed({
            // After delay, start MainActivity
            startActivity(Intent(this, MainActivity::class.java))
            finish() // Close SplashActivity so user cannot return to it
        }, 2000)
    }
}