package com.example.testkotlinapp

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.parse.ParseCloud
import com.parse.ParseUser

class OtpVerificationActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_otp_verification)

        val etOtp = findViewById<EditText>(R.id.etOtp)
        val btnVerify = findViewById<Button>(R.id.btnVerify)
        val btnResend = findViewById<Button>(R.id.btnResend)

        // Trigger OTP send on start
        sendOtp()

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
        }
    }

    private fun sendOtp() {
        val params = HashMap<String, String>()
        // Cloud code will use ParseUser.getCurrentUser() to get email/phone
        ParseCloud.callFunctionInBackground<String>("sendOtp", params) { result, e ->
            if (e == null) {
                Toast.makeText(this, "OTP sent to your registered contact", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "Error sending OTP: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun verifyOtp(code: String) {
        val params = HashMap<String, String>()
        params["code"] = code
        
        ParseCloud.callFunctionInBackground<Boolean>("verifyOtp", params) { success, e ->
            if (e == null && success == true) {
                // Verification successful, proceed to Main
                startActivity(Intent(this, MainActivity::class.java))
                finish()
            } else {
                Toast.makeText(this, "Invalid OTP. Please try again.", Toast.LENGTH_SHORT).show()
            }
        }
    }
}