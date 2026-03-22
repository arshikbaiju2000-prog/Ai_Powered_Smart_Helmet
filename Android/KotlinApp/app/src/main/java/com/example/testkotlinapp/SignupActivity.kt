package com.example.testkotlinapp

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Patterns
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.parse.ParseUser

class SignupActivity : AppCompatActivity() {

    private lateinit var btnSignup: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_signup)

        val etName = findViewById<EditText>(R.id.etName)
        val etEmail = findViewById<EditText>(R.id.etEmail)
        val etPhone = findViewById<EditText>(R.id.etPhone)
        val etEmergencyContact = findViewById<EditText>(R.id.etEmergencyContact)
        val etPassword = findViewById<EditText>(R.id.etPassword)
        val etConfirmPassword = findViewById<EditText>(R.id.etConfirmPassword)
        btnSignup = findViewById<Button>(R.id.btnSignup)
        val tvLogin = findViewById<TextView>(R.id.tvLogin)

        btnSignup.setOnClickListener {
            val name = etName.text.toString().trim()
            val email = etEmail.text.toString().trim()
            val phone = etPhone.text.toString().trim()
            val emergency = etEmergencyContact.text.toString().trim()
            val password = etPassword.text.toString().trim()
            val confirmPassword = etConfirmPassword.text.toString().trim()

            if (validateInput(name, email, phone, emergency, password, confirmPassword)) {
                // Disable button to prevent multiple clicks
                btnSignup.isEnabled = false
                btnSignup.text = "Creating Account..."

                val formattedPhone = "+91$phone"
                val formattedEmergency = "+91$emergency"
                
                val user = ParseUser()
                user.username = email
                user.setPassword(password)
                user.email = email
                user.put("fullName", name)
                user.put("phoneNumber", formattedPhone)
                user.put("emergencyContact", formattedEmergency)

                user.signUpInBackground { e ->
                    if (e == null) {
                        // Registration successful, now MUST verify OTP
                        val prefs = getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
                        prefs.edit().putBoolean("otp_pending", true).apply()

                        val intent = Intent(this, OtpVerificationActivity::class.java)
                        startActivity(intent)
                        finish()
                    } else {
                        btnSignup.isEnabled = true
                        btnSignup.text = "Sign Up"
                        Toast.makeText(this, "Registration Failed: ${e.message}", Toast.LENGTH_LONG).show()
                    }
                }
            }
        }

        tvLogin.setOnClickListener {
            finish()
        }
    }

    private fun validateInput(name: String, email: String, phone: String, emergency: String, pass: String, confirmPass: String): Boolean {
        if (name.isEmpty() || email.isEmpty() || phone.isEmpty() || emergency.isEmpty() || pass.isEmpty()) {
            Toast.makeText(this, "All fields are required", Toast.LENGTH_SHORT).show()
            return false
        }
        if (!Patterns.EMAIL_ADDRESS.matcher(email).matches()) {
            Toast.makeText(this, "Invalid email address", Toast.LENGTH_SHORT).show()
            return false
        }
        if (phone.length != 10) {
            Toast.makeText(this, "Phone number must be 10 digits", Toast.LENGTH_SHORT).show()
            return false
        }
        if (emergency.length != 10) {
            Toast.makeText(this, "Emergency contact must be 10 digits", Toast.LENGTH_SHORT).show()
            return false
        }
        if (phone == emergency) {
            Toast.makeText(this, "Emergency contact cannot be your own number", Toast.LENGTH_SHORT).show()
            return false
        }
        if (pass.length < 6) {
            Toast.makeText(this, "Password must be at least 6 characters", Toast.LENGTH_SHORT).show()
            return false
        }
        if (pass != confirmPass) {
            Toast.makeText(this, "Passwords do not match", Toast.LENGTH_SHORT).show()
            return false
        }
        return true
    }
}
