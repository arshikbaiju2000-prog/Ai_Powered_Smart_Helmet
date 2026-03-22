package com.example.testkotlinapp

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import androidx.appcompat.app.AppCompatActivity
import com.parse.ParseUser

class SplashActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Theme preference is now applied in MyApplication.kt
        setContentView(R.layout.activity_splash)

        val logo = findViewById<View>(R.id.ivSplashLogo)
        val title = findViewById<View>(R.id.tvAppName)

        // Classic fade-in animation
        logo.animate()
            .alpha(1f)
            .setDuration(1200)
            .setInterpolator(AccelerateDecelerateInterpolator())
            .start()

        title.animate()
            .alpha(1f)
            .setDuration(1200)
            .setStartDelay(400)
            .setInterpolator(AccelerateDecelerateInterpolator())
            .start()

        Handler(Looper.getMainLooper()).postDelayed({
            checkAuthAndNavigate()
        }, 2500)
    }

    private fun checkAuthAndNavigate() {
        val prefs = getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
        val isOtpPending = prefs.getBoolean("otp_pending", false)
        val currentUser = ParseUser.getCurrentUser()

        if (currentUser != null) {
            if (isOtpPending) {
                ParseUser.logOut()
                prefs.edit().putBoolean("otp_pending", false).apply()
                startActivity(Intent(this, LoginActivity::class.java))
            } else {
                startActivity(Intent(this, MainActivity::class.java))
            }
        } else {
            startActivity(Intent(this, LoginActivity::class.java))
        }
        finish()
    }
}
