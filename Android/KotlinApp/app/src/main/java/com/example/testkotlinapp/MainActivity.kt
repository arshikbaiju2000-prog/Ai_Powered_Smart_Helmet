package com.example.testkotlinapp

import android.Manifest
import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.telephony.SmsManager
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStreamWriter
import java.net.Socket
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull



class MainActivity : AppCompatActivity() {

    private lateinit var imageUpdater: ImageUpdater
    private var receiveJob: Job? = null
    private val receivedImages = mutableListOf<File>()
    private lateinit var recyclerView: RecyclerView
    private lateinit var incidentLogButton: Button
    private val otherViews = mutableListOf<View>()
    private var socket: Socket? = null
    private var isConnected = false

    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private val emergencyNumber = "+916238009232"

    private val espIp = "192.168.4.1"   // Replace with your ESP32 IP
    private val espPort = 12345          // Replace with your ESP32 port

    companion object {
        @Volatile
        var isIncidentLogOpen = false
        private const val PERMISSION_REQUEST_CODE = 100
    }


    private fun saveBitmapToFile(context: Context, bitmap: Bitmap, filename: String): File {
        val file = File(context.filesDir, filename)
        FileOutputStream(file).use { out ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, 90, out)
        }
        return file
    }

    private fun readOneImageFromStream(inputStream: InputStream): ByteArray? {
        val sizeBuffer = ByteArray(4)
        var bytesRead = 0
        while (bytesRead < 4) {
            val result = inputStream.read(sizeBuffer, bytesRead, 4 - bytesRead)
            if (result == -1) return null
            bytesRead += result
        }

        val size = ((sizeBuffer[0].toInt() and 0xFF) shl 24) or
                ((sizeBuffer[1].toInt() and 0xFF) shl 16) or
                ((sizeBuffer[2].toInt() and 0xFF) shl 8) or
                (sizeBuffer[3].toInt() and 0xFF)

        if (size <= 0) return null

        val imageBuffer = ByteArray(size)
        bytesRead = 0
        while (bytesRead < size) {
            val count = inputStream.read(imageBuffer, bytesRead, size - bytesRead)
            if (count == -1) break
            bytesRead += count
        }

        return if (bytesRead == size) imageBuffer else null
    }

    private fun startReceivingImages(button: Button) {
        receiveJob = CoroutineScope(Dispatchers.IO).launch {
            try {
                val inputStream = socket?.getInputStream() ?: return@launch
                val output = OutputStreamWriter(socket?.getOutputStream() ?: return@launch)

                output.write("START_STREAM\n")
                output.flush()

                while (isActive && isConnected) {
                    val imageBytes = readOneImageFromStream(inputStream) ?: break
                    val bitmap = BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size)
                    val filename = "img_${System.currentTimeMillis()}.jpg"
                    val file = saveBitmapToFile(this@MainActivity, bitmap, filename)

                    synchronized(receivedImages) {
                        receivedImages.add(file)
                    }
                    runOnUiThread {
                        if (!isIncidentLogOpen) {
                            imageUpdater.addImage(file)
                        }

                    }
                }

                withContext(Dispatchers.Main) {
                    isConnected = false
                    button.text = "Connect"
                    Snackbar.make(findViewById(R.id.main), "Connection closed", Snackbar.LENGTH_SHORT).show()
                }

                socket?.close()
                socket = null
            } catch (ex: Exception) {
                withContext(Dispatchers.Main) {
                    isConnected = false
                    button.text = "Connect"
                    Snackbar.make(findViewById(R.id.main), "Connection error", Snackbar.LENGTH_SHORT).show()
                }
                ex.printStackTrace()
            }
        }
    }

    private fun checkPermissions(): Boolean {
        val sendSms = ContextCompat.checkSelfPermission(this, Manifest.permission.SEND_SMS)
        val fineLocation = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
        val coarseLocation = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION)
        return sendSms == PackageManager.PERMISSION_GRANTED &&
                fineLocation == PackageManager.PERMISSION_GRANTED &&
                coarseLocation == PackageManager.PERMISSION_GRANTED
    }

    private fun requestPermissions() {
        ActivityCompat.requestPermissions(
            this,
            arrayOf(
                Manifest.permission.SEND_SMS,
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION
            ),
            PERMISSION_REQUEST_CODE
        )
    }

    private fun isLocationEnabled(): Boolean {
        val locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        return locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER) ||
                locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
    }

    private fun showLocationSettingsDialog() {
        AlertDialog.Builder(this)
            .setTitle("Location Disabled")
            .setMessage("Please enable Location Services to send your coordinates in the SOS message.")
            .setPositiveButton("Settings") { _, _ ->
                startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    @SuppressLint("MissingPermission")
    private fun sendSOS() {
        if (!checkPermissions()) {
            requestPermissions()
            return
        }

        if (!isLocationEnabled()) {
            showLocationSettingsDialog()
            // Send message without location if the user won't enable it
            sendSMS(emergencyNumber, "EMERGENCY! I need help! (Location services disabled on device)")
            return
        }

        // Use getCurrentLocation for a fresh and accurate fix
        val cancellationTokenSource = CancellationTokenSource()
        fusedLocationClient.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, cancellationTokenSource.token)
            .addOnSuccessListener { location: Location? ->
                if (location != null) {
                    sendSOSWithMessage(location)
                } else {
                    // Fallback to last known location if getCurrentLocation fails
                    fusedLocationClient.lastLocation.addOnSuccessListener { lastLoc ->
                        if (lastLoc != null) {
                            sendSOSWithMessage(lastLoc)
                        } else {
                            sendSMS(emergencyNumber, "EMERGENCY! I need help! Location unavailable.")
                        }
                    }
                }
            }
            .addOnFailureListener {
                sendSMS(emergencyNumber, "EMERGENCY! I need help! Location unavailable.")
            }
    }

    private fun sendSOSWithMessage(location: Location) {
        val locationMsg = " My location: https://www.google.com/maps/search/?api=1&query=${location.latitude},${location.longitude}"
        val message = "EMERGENCY! I need help! $locationMsg"
        sendSMS(emergencyNumber, message)
    }

    private fun sendSMS(phoneNumber: String, message: String) {
        try {
            val smsManager: SmsManager = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                this.getSystemService(SmsManager::class.java)
            } else {
                @Suppress("DEPRECATION")
                SmsManager.getDefault()
            }
            
            val sentPI = PendingIntent.getBroadcast(this, 0, Intent("SMS_SENT"), PendingIntent.FLAG_IMMUTABLE)
            val deliveredPI = PendingIntent.getBroadcast(this, 0, Intent("SMS_DELIVERED"), PendingIntent.FLAG_IMMUTABLE)

            smsManager.sendTextMessage(phoneNumber, null, message, sentPI, deliveredPI)
            Toast.makeText(this, "SOS Message Sent", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, "Failed to send SOS: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_main)

        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)

        recyclerView = findViewById(R.id.recyclerView)
        recyclerView.layoutManager = GridLayoutManager(this, 3)

        val imageView: ImageView = findViewById(R.id.imageView)
        imageView.setImageResource(R.drawable.helemt)

        val connectButton: Button = findViewById(R.id.button)
        incidentLogButton = findViewById(R.id.button3)
        val sosButton: Button = findViewById(R.id.button6)

        // Add views to toggle visibility with RecyclerView
        otherViews.add(connectButton)
        otherViews.add(imageView)
        otherViews.add(findViewById(R.id.button2))
        otherViews.add(findViewById(R.id.button4))
        otherViews.add(findViewById(R.id.button5))
        otherViews.add(findViewById(R.id.textView2))
        otherViews.add(findViewById(R.id.textView3))
        otherViews.add(sosButton)

        recyclerView.visibility = View.GONE

        imageUpdater = ImageUpdater(this, recyclerView)
        imageUpdater.loadImages()

        incidentLogButton.setOnClickListener {
            val intent = Intent(this, IncidentLogActivity::class.java)
            startActivity(intent)
        }

        connectButton.setOnClickListener {
            val button = it as Button
            if (!isConnected) {
                button.text = "Connecting..."
                button.isEnabled = false

                receiveJob?.cancel()
                receiveJob = CoroutineScope(Dispatchers.IO).launch {
                    val connected = withTimeoutOrNull(5000L) { // 5-second timeout
                        try {
//                            socket = Socket(espIp, espPort)
                            socket = Socket()
                            socket?.connect(java.net.InetSocketAddress(espIp, espPort), 5000)
                            socket?.isConnected == true
                        } catch (e: Exception) {
                            false
                        }
                    } ?: false

                    withContext(Dispatchers.Main) {
                        button.isEnabled = true
                        if (connected) {
                            isConnected = true
                            button.text = "Disconnect"
                            startReceivingImages(button)
                        } else {
                            isConnected = false
                            button.text = "Connect"
                            Snackbar.make(findViewById(R.id.main), "Connection failed", Snackbar.LENGTH_SHORT).show()
                        }
                    }
                }
            } else {
                // Disconnect
                receiveJob?.cancel()
                receiveJob = null
                socket?.close()
                socket = null
                isConnected = false
                button.text = "Connect"
                Snackbar.make(findViewById(R.id.main), "Disconnected", Snackbar.LENGTH_SHORT).show()
            }
        }
        findViewById<Button>(R.id.button2).setOnClickListener {
            startActivity(Intent(this, RideHistoryActivity::class.java))
        }

        findViewById<Button>(R.id.button4).setOnClickListener {
            startActivity(Intent(this, IssueStatusActivity::class.java))
        }

        findViewById<Button>(R.id.button5).setOnClickListener {
            startActivity(Intent(this, ReportIssueActivity::class.java))
        }

        sosButton.setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("Emergency SOS")
                .setMessage("Are you sure you want to send an emergency alert?")
                .setPositiveButton("YES") { _, _ ->
                    sendSOS()
                }
                .setNegativeButton("NO", null)
                .show()
        }

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERMISSION_REQUEST_CODE) {
            if (grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
                sendSOS()
            } else {
                Toast.makeText(this, "Permissions are required for SOS feature", Toast.LENGTH_SHORT).show()
            }
        }
    }
}
