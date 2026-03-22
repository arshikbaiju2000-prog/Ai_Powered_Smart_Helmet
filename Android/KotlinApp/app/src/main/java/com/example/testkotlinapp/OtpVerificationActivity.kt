package com.example.testkotlinapp

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Bundle
import android.os.CountDownTimer
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import com.parse.ParseCloud
import com.parse.ParseUser

class OtpVerificationActivity : AppCompatActivity() {

    private lateinit var tvTimer: TextView
    private lateinit var btnResend: Button
    private var countDownTimer: CountDownTimer? = null
    private var isPasswordReset = false
    private var resetEmail: String? = null
    private lateinit var prefs: SharedPreferences

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_otp_verification)

        prefs = getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
        isPasswordReset = intent.getBooleanExtra("isPasswordReset", false)
        resetEmail = intent.getStringExtra("resetEmail")
        
        val etOtp = findViewById<EditText>(R.id.etOtp)
        val btnVerify = findViewById<Button>(R.id.btnVerify)
        btnResend = findViewById(R.id.btnResend)
        tvTimer = findViewById(R.id.tvTimer)

        sendOtp()
        startResendTimer()

        btnVerify.setOnClickListener {
            val code = etOtp.text.toString().trim()
            if (code.length == 6) {
                verifyOtp(code)
            } else {
                Toast.makeText(this, "Please enter a 6-digit code", Toast.LENGTH_SHORT).show()
            }
        }

        btnResend.setOnClickListener {
            sendOtp()
            startResendTimer()
        }

        // Handle back press to logout user if they haven't verified OTP
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (!isPasswordReset) {
                    ParseUser.logOut()
                    prefs.edit().putBoolean("otp_pending", false).apply()
                }
                finish()
            }
        })
    }

    private fun startResendTimer() {
        btnResend.isEnabled = false
        countDownTimer?.cancel()
        countDownTimer = object : CountDownTimer(30000, 1000) {
            override fun onTick(millisUntilFinished: Long) {
                val secondsRemaining = millisUntilFinished / 1000
                tvTimer.text = "Resend code in ${secondsRemaining}s"
            }
            override fun onFinish() {
                btnResend.isEnabled = true
                tvTimer.text = "You can now resend the code"
            }
        }.start()
    }

    private fun sendOtp() {
        val params = HashMap<String, Any?>()
        params["isPasswordReset"] = isPasswordReset
        params["resetEmail"] = resetEmail
        
        ParseCloud.callFunctionInBackground<Map<String, Any>>("sendOtp", params) { result, e ->
            if (e == null && result != null) {
                val sentToEmail = result["sentToEmail"] as? Boolean ?: false
                val sentToPhone = result["sentToPhone"] as? Boolean ?: false
                val message = when {
                    sentToEmail && sentToPhone -> "OTP sent to your email and phone"
                    sentToEmail -> "OTP sent to your email"
                    sentToPhone -> "OTP sent to your phone"
                    else -> "OTP sent to your registered contact"
                }
                Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "Error sending OTP: ${e?.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun verifyOtp(code: String) {
        val params = HashMap<String, Any?>()
        params["code"] = code
        params["isPasswordReset"] = isPasswordReset
        params["resetEmail"] = resetEmail
        
        ParseCloud.callFunctionInBackground<Boolean>("verifyOtp", params) { success, e ->
            if (e == null && success == true) {
                if (isPasswordReset) {
                    val intent = Intent(this, ResetPasswordActivity::class.java)
                    intent.putExtra("resetEmail", resetEmail)
                    intent.putExtra("otpCode", code) // Pass OTP to reset screen
                    startActivity(intent)
                } else {
                    prefs.edit().putBoolean("otp_pending", false).apply()
                    startActivity(Intent(this, MainActivity::class.java))
                }
                finish()
            } else {
                Toast.makeText(this, "Invalid OTP: ${e?.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        countDownTimer?.cancel()
    }
}
