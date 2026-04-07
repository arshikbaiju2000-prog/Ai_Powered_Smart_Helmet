package com.example.testkotlinapp

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.parse.ParseCloud

class ResetPasswordActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_reset_password)

        val resetEmail = intent.getStringExtra("resetEmail")
        val otpCode = intent.getStringExtra("otpCode")

        val etNewPassword = findViewById<EditText>(R.id.etNewPassword)
        val etConfirmNewPassword = findViewById<EditText>(R.id.etConfirmNewPassword)
        val btnReset = findViewById<Button>(R.id.btnResetPassword)

        btnReset.setOnClickListener {
            val newPass = etNewPassword.text.toString().trim()
            val confirmPass = etConfirmNewPassword.text.toString().trim()

            if (newPass.length < 6) {
                Toast.makeText(this, "Password must be at least 6 characters", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            if (newPass != confirmPass) {
                Toast.makeText(this, "Passwords do not match", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            val params = HashMap<String, String?>()
            params["newPassword"] = newPass
            params["resetEmail"] = resetEmail
            params["otpCode"] = otpCode
            
            // Calling the updated cloud function that doesn't require a session
            ParseCloud.callFunctionInBackground<Boolean>("updateUserPasswordSecurely", params) { success, e ->
                if (e == null && success == true) {
                    Toast.makeText(this, "Password updated successfully. Please login.", Toast.LENGTH_LONG).show()
                    val intent = Intent(this, LoginActivity::class.java)
                    intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                    startActivity(intent)
                    finish()
                } else {
                    Toast.makeText(this, "Error: ${e?.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }
}