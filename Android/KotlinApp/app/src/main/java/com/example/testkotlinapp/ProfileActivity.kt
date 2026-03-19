package com.example.testkotlinapp

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.view.View
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.parse.ParseFile
import com.parse.ParseUser
import com.parse.SaveCallback
import java.io.ByteArrayOutputStream

class ProfileActivity : AppCompatActivity() {

    private lateinit var imgProfile: ImageView
    private lateinit var etName: EditText
    private lateinit var tvEmail: TextView
    private lateinit var etPhone: EditText
    private lateinit var etBloodGroup: EditText
    private lateinit var etEmergencyContact: EditText
    private lateinit var btnEditProfile: Button
    private lateinit var btnSave: Button
    private lateinit var btnEditPhoto: ImageButton

    private var profileImageFile: ParseFile? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_profile)

        imgProfile = findViewById(R.id.imgProfile)
        etName = findViewById(R.id.etProfileName)
        tvEmail = findViewById(R.id.tvProfileEmail)
        etPhone = findViewById(R.id.etProfilePhone)
        etBloodGroup = findViewById(R.id.etBloodGroup)
        etEmergencyContact = findViewById(R.id.etEmergencyContact)
        btnEditProfile = findViewById(R.id.btnEditProfile)
        btnSave = findViewById(R.id.btnSaveProfile)
        btnEditPhoto = findViewById(R.id.btnEditPhoto)

        loadUserData()

        btnEditProfile.setOnClickListener {
            toggleEditMode(true)
        }

        btnEditPhoto.setOnClickListener {
            val intent = Intent(Intent.ACTION_PICK, MediaStore.Images.Media.EXTERNAL_CONTENT_URI)
            resultLauncher.launch(intent)
        }

        btnSave.setOnClickListener {
            saveUserData()
        }
    }

    private fun toggleEditMode(enabled: Boolean) {
        etName.isEnabled = enabled
        etPhone.isEnabled = enabled
        etBloodGroup.isEnabled = enabled
        etEmergencyContact.isEnabled = enabled
        
        btnEditPhoto.visibility = if (enabled) View.VISIBLE else View.GONE
        btnSave.visibility = if (enabled) View.VISIBLE else View.GONE
        btnEditProfile.visibility = if (enabled) View.GONE else View.VISIBLE
    }

    private val resultLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val data: Intent? = result.data
            val imageUri: Uri? = data?.data
            if (imageUri != null) {
                val inputStream = contentResolver.openInputStream(imageUri)
                val bitmap = BitmapFactory.decodeStream(inputStream)
                imgProfile.setImageBitmap(bitmap)
                uploadImage(bitmap)
            }
        }
    }

    private fun loadUserData() {
        val user = ParseUser.getCurrentUser() ?: return
        etName.setText(user.get("fullName") as? String ?: "")
        tvEmail.text = user.email
        etPhone.setText(user.get("phoneNumber") as? String ?: "")
        etBloodGroup.setText(user.get("bloodGroup") as? String ?: "")
        etEmergencyContact.setText(user.get("emergencyContact") as? String ?: "")

        val imageFile = user.getParseFile("profileImage")
        if (imageFile != null) {
            imageFile.getDataInBackground { data, e ->
                if (e == null && data != null) {
                    val bitmap = BitmapFactory.decodeByteArray(data, 0, data.size)
                    imgProfile.setImageBitmap(bitmap)
                }
            }
        }
    }

    private fun uploadImage(bitmap: Bitmap) {
        val bos = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 50, bos)
        val data = bos.toByteArray()
        profileImageFile = ParseFile("profile.jpg", data)
        profileImageFile?.saveInBackground(SaveCallback { e ->
            if (e != null) {
                Toast.makeText(this, "Image upload failed: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        })
    }

    private fun saveUserData() {
        val user = ParseUser.getCurrentUser() ?: return
        val name = etName.text.toString().trim()
        val phone = etPhone.text.toString().trim()
        val bloodGroup = etBloodGroup.text.toString().trim()
        val emergency = etEmergencyContact.text.toString().trim()

        if (name.isEmpty() || phone.isEmpty()) {
            Toast.makeText(this, "Name and Phone are required", Toast.LENGTH_SHORT).show()
            return
        }

        user.put("fullName", name)
        user.put("phoneNumber", phone)
        user.put("bloodGroup", bloodGroup)
        user.put("emergencyContact", emergency)
        
        if (profileImageFile != null) {
            user.put("profileImage", profileImageFile!!)
        }

        user.saveInBackground { e ->
            if (e == null) {
                Toast.makeText(this, "Profile Updated Successfully", Toast.LENGTH_SHORT).show()
                toggleEditMode(false)
            } else {
                Toast.makeText(this, "Update Failed: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }
}