package com.example.testkotlinapp

import android.content.Context
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

    private lateinit var btnLogin: Button

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
        btnLogin = findViewById<Button>(R.id.btnLogin)
        val tvSignup = findViewById<TextView>(R.id.tvSignup)
        val tvForgotPassword = findViewById<TextView>(R.id.tvForgotPassword)

        btnLogin.setOnClickListener {
            val identifier = etIdentifier.text.toString().trim()
            val password = etPassword.text.toString().trim()

            if (identifier.isNotEmpty() && password.isNotEmpty()) {
                // Disable button to prevent multiple clicks
                btnLogin.isEnabled = false
                btnLogin.text = "Logging in..."
                
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
                btnLogin.isEnabled = true
                btnLogin.text = "Login"
                Toast.makeText(this, "User not found: ${e?.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun findUsernameForReset(phone: String) {
        val params = HashMap<String, String>()
        params["phone"] = phone
        ParseCloud.callFunctionInBackground<String>("getUsernameByPhone", params) { username, e ->
            if (e == null && username != null) {
                goToOtpForReset(username)
            } else {
                Toast.makeText(this, "User not found: ${e?.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun goToOtpForReset(email: String) {
        val intent = Intent(this, OtpVerificationActivity::class.java)
        intent.putExtra("isPasswordReset", true)
        intent.putExtra("resetEmail", email)
        startActivity(intent)
    }

    private fun performLogin(username: String, password: String) {
        ParseUser.logInInBackground(username, password) { _, e ->
            if (e == null) {
                // Set OTP pending flag
                val prefs = getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
                prefs.edit().putBoolean("otp_pending", true).apply()

                val intent = Intent(this, OtpVerificationActivity::class.java)
                startActivity(intent)
                finish()
            } else {
                btnLogin.isEnabled = true
                btnLogin.text = "Login"
                Toast.makeText(this, "Login Failed: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }
}
