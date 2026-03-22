package com.example.testkotlinapp

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.appcompat.app.AppCompatActivity
import com.parse.ParseUser

class SplashActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_splash)

        Handler(Looper.getMainLooper()).postDelayed({
            val prefs = getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
            val isOtpPending = prefs.getBoolean("otp_pending", false)
            val currentUser = ParseUser.getCurrentUser()

            if (currentUser != null) {
                if (isOtpPending) {
                    // User closed the app during OTP verification. 
                    // Log them out and send back to Login to be safe.
                    ParseUser.logOut()
                    prefs.edit().putBoolean("otp_pending", false).apply()
                    startActivity(Intent(this, LoginActivity::class.java))
                } else {
                    // User is logged in and verified, go to MainActivity
                    startActivity(Intent(this, MainActivity::class.java))
                }
            } else {
                // User not logged in, go to LoginActivity
                startActivity(Intent(this, LoginActivity::class.java))
            }
            finish()
        }, 2000)
    }
}
