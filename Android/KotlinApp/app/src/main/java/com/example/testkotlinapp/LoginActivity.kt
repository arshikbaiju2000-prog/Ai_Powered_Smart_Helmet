package com.example.testkotlinapp

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.parse.ParseCloud
import com.parse.ParseUser

class LoginActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        if (ParseUser.getCurrentUser() != null) {
            startActivity(Intent(this, MainActivity::class.java))
            finish()
            return
        }

        setContentView(R.layout.activity_login)

        val etIdentifier = findViewById<EditText>(R.id.etIdentifier)
        val etPassword = findViewById<EditText>(R.id.etPassword)
        val btnLogin = findViewById<Button>(R.id.btnLogin)
        val tvSignup = findViewById<TextView>(R.id.tvSignup)
        val tvForgotPassword = findViewById<TextView>(R.id.tvForgotPassword)

        btnLogin.setOnClickListener {
            val identifier = etIdentifier.text.toString().trim()
            val password = etPassword.text.toString().trim()

            if (identifier.isNotEmpty() && password.isNotEmpty()) {
                if (identifier.contains("@")) {
                    performLogin(identifier, password)
                } else {
                    findUsernameByPhoneAndLogin(identifier, password)
                }
            } else {
                Toast.makeText(this, "Please fill all fields", Toast.LENGTH_SHORT).show()
            }
        }

        tvSignup.setOnClickListener {
            startActivity(Intent(this, SignupActivity::class.java))
        }

        tvForgotPassword.setOnClickListener {
            val identifier = etIdentifier.text.toString().trim()
            if (identifier.isEmpty()) {
                Toast.makeText(this, "Please enter your Email or Phone first", Toast.LENGTH_SHORT).show()
            } else {
                if (identifier.contains("@")) {
                    goToOtpForReset(identifier)
                } else {
                    findUsernameForReset(identifier)
                }
            }
        }
    }

    private fun findUsernameByPhoneAndLogin(phone: String, pass: String) {
        val params = HashMap<String, String>()
        params["phone"] = phone
        ParseCloud.callFunctionInBackground<String>("getUsernameByPhone", params) { username, e ->
            if (e == null && username != null) {
                performLogin(username, pass)
            } else {
                Toast.makeText(this, "User not found: ${e?.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun findUsernameForReset(phone: String) {
        val params = HashMap<String, String>()
        params["phone"] = phone
        ParseCloud.callFunctionInBackground<String>("getUsernameByPhone", params) { username, e ->
            if (e == null && username != null) {
                // Log in with temporary session to allow OTP trigger
                // Note: Standard Parse password reset usually happens via email link,
                // but we are doing it via custom OTP flow.
                // We'll pass the email to OTP screen.
                goToOtpForReset(username)
            } else {
                Toast.makeText(this, "User not found: ${e?.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun goToOtpForReset(email: String) {
        // We'll use a special login or just pass info to Otp activity.
        // For security, Parse requires a user to be logged in to call most cloud functions.
        // If it's a "forgot password" flow, we might need a bypass.
        val intent = Intent(this, OtpVerificationActivity::class.java)
        intent.putExtra("isPasswordReset", true)
        intent.putExtra("resetEmail", email)
        startActivity(intent)
    }

    private fun performLogin(username: String, password: String) {
        ParseUser.logInInBackground(username, password) { _, e ->
            if (e == null) {
                val intent = Intent(this, OtpVerificationActivity::class.java)
                startActivity(intent)
                finish()
            } else {
                Toast.makeText(this, "Login Failed: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }
}