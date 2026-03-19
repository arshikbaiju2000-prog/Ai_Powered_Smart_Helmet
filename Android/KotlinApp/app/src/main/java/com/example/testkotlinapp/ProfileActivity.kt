package com.example.testkotlinapp

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.view.View
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.parse.ParseFile
import com.parse.ParseUser
import com.parse.SaveCallback
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.*

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
    private var currentPhotoPath: String? = null

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
            showImageSourceDialog()
        }

        btnSave.setOnClickListener {
            saveUserData()
        }
    }

    private fun showImageSourceDialog() {
        val options = arrayOf("Take Photo", "Choose from Gallery", "Cancel")
        AlertDialog.Builder(this)
            .setTitle("Profile Photo")
            .setItems(options) { dialog, which ->
                when (which) {
                    0 -> checkCameraPermissionAndLaunch()
                    1 -> {
                        val intent = Intent(Intent.ACTION_PICK, MediaStore.Images.Media.EXTERNAL_CONTENT_URI)
                        galleryLauncher.launch(intent)
                    }
                    2 -> dialog.dismiss()
                }
            }
            .show()
    }

    private fun checkCameraPermissionAndLaunch() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            launchCamera()
        } else {
            requestPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    private val requestPermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted ->
        if (isGranted) {
            launchCamera()
        } else {
            Toast.makeText(this, "Camera permission is required to take photos", Toast.LENGTH_SHORT).show()
        }
    }

    private fun launchCamera() {
        val photoFile: File? = try {
            createImageFile()
        } catch (ex: IOException) {
            null
        }
        photoFile?.also {
            val photoURI: Uri = FileProvider.getUriForFile(this, "${packageName}.fileprovider", it)
            val intent = Intent(MediaStore.ACTION_IMAGE_CAPTURE).apply {
                putExtra(MediaStore.EXTRA_OUTPUT, photoURI)
            }
            cameraLauncher.launch(intent)
        }
    }

    @Throws(IOException::class)
    private fun createImageFile(): File {
        val timeStamp: String = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val storageDir: File? = getExternalFilesDir(Environment.DIRECTORY_PICTURES)
        return File.createTempFile("JPEG_${timeStamp}_", ".jpg", storageDir).apply {
            currentPhotoPath = absolutePath
        }
    }

    private val cameraLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            currentPhotoPath?.let { path ->
                val bitmap = BitmapFactory.decodeFile(path)
                val rotatedBitmap = rotateImageIfRequired(bitmap, path)
                imgProfile.setImageBitmap(rotatedBitmap)
                uploadImage(rotatedBitmap)
            }
        }
    }

    private val galleryLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val imageUri: Uri? = result.data?.data
            if (imageUri != null) {
                val inputStream = contentResolver.openInputStream(imageUri)
                val bitmap = BitmapFactory.decodeStream(inputStream)
                
                // For gallery images, we might need a different way to handle rotation if it occurs
                val rotatedBitmap = rotateImageIfRequiredFromUri(bitmap, imageUri)
                imgProfile.setImageBitmap(rotatedBitmap)
                uploadImage(rotatedBitmap)
            }
        }
    }

    private fun rotateImageIfRequired(img: Bitmap, path: String): Bitmap {
        val ei = ExifInterface(path)
        val orientation = ei.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)

        return when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> rotateImage(img, 90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> rotateImage(img, 180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> rotateImage(img, 270f)
            else -> img
        }
    }

    private fun rotateImageIfRequiredFromUri(img: Bitmap, uri: Uri): Bitmap {
        val inputStream = contentResolver.openInputStream(uri) ?: return img
        val ei = ExifInterface(inputStream)
        val orientation = ei.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)

        return when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> rotateImage(img, 90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> rotateImage(img, 180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> rotateImage(img, 270f)
            else -> img
        }
    }

    private fun rotateImage(img: Bitmap, degree: Float): Bitmap {
        val matrix = Matrix()
        matrix.postRotate(degree)
        val rotatedImg = Bitmap.createBitmap(img, 0, 0, img.width, img.height, matrix, true)
        img.recycle()
        return rotatedImg
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
        bitmap.compress(Bitmap.CompressFormat.JPEG, 70, bos)
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
