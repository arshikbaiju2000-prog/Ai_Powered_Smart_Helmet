package com.example.testkotlinapp

import android.Manifest
import android.annotation.SuppressLint
import android.app.PendingIntent
import android.bluetooth.*
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.telephony.SmsManager
import android.util.Log
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
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.*
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStreamWriter
import java.net.Socket
import java.util.*

class MainActivity : AppCompatActivity() {
    private var accidentHandled = false
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

    private val espIp = "192.168.4.1"
    private val espPort = 12345

    // BLE Variables
    private var bluetoothGatt: BluetoothGatt? = null
    private val SERVICE_UUID = UUID.fromString("00001234-0000-1000-8000-00805f9b34fb")
    private val CHAR_UUID = UUID.fromString("00005678-0000-1000-8000-00805f9b34fb")
    private val NOTIFICATION_DESCRIPTOR_UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    private val bluetoothAdapter: BluetoothAdapter? by lazy {
        val bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        bluetoothManager.adapter
    }

    companion object {
        @Volatile
        var isIncidentLogOpen = false
        private const val PERMISSION_REQUEST_CODE = 100
        private const val TAG = "HelmetBLE"
    }

    private val gattCallback = object : BluetoothGattCallback() {
        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                Log.i(TAG, "Connected to GATT server. Discovering services...")
                gatt.discoverServices()
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                Log.i(TAG, "Disconnected from GATT server.")
                bluetoothGatt = null
            }
        }

        @SuppressLint("MissingPermission")
        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {

                val service = gatt.getService(SERVICE_UUID)
                val characteristic = service?.getCharacteristic(CHAR_UUID)

                if (characteristic != null) {

                    // Step 1: Enable notification
                    gatt.setCharacteristicNotification(characteristic, true)


                    Handler(Looper.getMainLooper()).postDelayed({

                        val descriptor = characteristic.getDescriptor(NOTIFICATION_DESCRIPTOR_UUID)

                        if (descriptor != null) {
                            descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                            gatt.writeDescriptor(descriptor)

                            Log.i(TAG, "Accident monitoring active via BLE (after delay).")
                        }

                    }, 500)
                }
            }
        }

        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            val data = characteristic.getStringValue(0)
            Log.d(TAG, "Signal received from helmet: $data")
            if (data.contains("ACCIDENT") && !accidentHandled) {
                accidentHandled = true
                Log.e(TAG, "ACCIDENT SIGNAL RECEIVED! Triggering SOS...")
                runOnUiThread {
                    sendSOS()
                }

            }

        }
    }

    private val scanCallback = object : ScanCallback() {
        @SuppressLint("MissingPermission")
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val device = result.device
            if (device != null && device.name == "HelmetDetector") {
                Log.d(TAG, "Helmet found! Connecting...")
                bluetoothAdapter?.bluetoothLeScanner?.stopScan(this)
                bluetoothGatt = device.connectGatt(this@MainActivity, false, gattCallback)
            }
        }
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
                    synchronized(receivedImages) { receivedImages.add(file) }
                    runOnUiThread { if (!isIncidentLogOpen) imageUpdater.addImage(file) }
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
        val permissions = mutableListOf(
            Manifest.permission.SEND_SMS,
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissions.add(Manifest.permission.BLUETOOTH_SCAN)
            permissions.add(Manifest.permission.BLUETOOTH_CONNECT)
        }
        return permissions.all { ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED }
    }

    private fun requestPermissions() {
        val permissions = mutableListOf(
            Manifest.permission.SEND_SMS,
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissions.add(Manifest.permission.BLUETOOTH_SCAN)
            permissions.add(Manifest.permission.BLUETOOTH_CONNECT)
        }
        ActivityCompat.requestPermissions(this, permissions.toTypedArray(), PERMISSION_REQUEST_CODE)
    }

    private fun isLocationEnabled(): Boolean {
        val locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        return locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER) ||
                locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
    }

    @SuppressLint("MissingPermission")
    private fun sendSOS() {
        if (!isLocationEnabled()) {
            sendSMS(emergencyNumber, "EMERGENCY! Accident detected! (GPS is OFF)")
            return
        }
        val cancellationTokenSource = CancellationTokenSource()
        fusedLocationClient.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, cancellationTokenSource.token)
            .addOnSuccessListener { location: Location? ->
                if (location != null) {
                    val msg = "EMERGENCY! Accident detected! My location: https://www.google.com/maps/search/?api=1&query=${location.latitude},${location.longitude}"
                    sendSMS(emergencyNumber, msg)
                } else {
                    fusedLocationClient.lastLocation.addOnSuccessListener { lastLoc ->
                        if (lastLoc != null) {
                            val msg = "EMERGENCY! Accident detected! My location: https://www.google.com/maps/search/?api=1&query=${lastLoc.latitude},${lastLoc.longitude}"
                            sendSMS(emergencyNumber, msg)
                        } else {
                            sendSMS(emergencyNumber, "EMERGENCY! Accident detected! Location unavailable.")
                        }
                    }
                }
            }
            .addOnFailureListener {
                sendSMS(emergencyNumber, "EMERGENCY! Accident detected! Location unavailable.")
            }
    }

    private fun sendSMS(phoneNumber: String, message: String) {
        try {
            val smsManager: SmsManager = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                this.getSystemService(SmsManager::class.java)
            } else {
                @Suppress("DEPRECATION") SmsManager.getDefault()
            }
            smsManager.sendTextMessage(phoneNumber, null, message, null, null)
            runOnUiThread { Toast.makeText(this, "SOS Message Sent", Toast.LENGTH_SHORT).show() }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send SOS: ${e.message}")
        }
    }

    @SuppressLint("MissingPermission")
    private fun startBLEScan() {
        if (!checkPermissions()) {
            requestPermissions()
            return
        }
        Log.d(TAG, "Starting BLE Scan...")
        val scanner = bluetoothAdapter?.bluetoothLeScanner
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        val filters = listOf(ScanFilter.Builder().setDeviceName("HelmetDetector").build())
        scanner?.startScan(filters, settings, scanCallback)
        Handler(Looper.getMainLooper()).postDelayed({ 
            try { scanner?.stopScan(scanCallback) } catch (e: Exception) {}
        }, 10000)
    }

    @SuppressLint("MissingPermission")
    private fun disconnectBLE() {
        bluetoothGatt?.disconnect()
        bluetoothGatt?.close()
        bluetoothGatt = null
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

        otherViews.addAll(listOf(connectButton, imageView, findViewById(R.id.button2), 
            findViewById(R.id.button4), findViewById(R.id.button5), 
            findViewById(R.id.textView2), findViewById(R.id.textView3), sosButton))

        imageUpdater = ImageUpdater(this, recyclerView)
        imageUpdater.loadImages()

        incidentLogButton.setOnClickListener {
            startActivity(Intent(this, IncidentLogActivity::class.java))
        }

        connectButton.setOnClickListener {
            if (!isConnected) {
                // Connect BLE & Wi-Fi
                startBLEScan()
                
                connectButton.isEnabled = false
                connectButton.text = "Connecting..."
                receiveJob?.cancel()
                receiveJob = CoroutineScope(Dispatchers.IO).launch {
                    val connected = withTimeoutOrNull(5000L) {
                        try {
                            socket = Socket()
                            socket?.connect(java.net.InetSocketAddress(espIp, espPort), 5000)
                            socket?.isConnected == true
                        } catch (e: Exception) { false }
                    } ?: false
                    withContext(Dispatchers.Main) {
                        connectButton.isEnabled = true
                        if (connected) {
                            isConnected = true
                            connectButton.text = "Disconnect"
                            startReceivingImages(connectButton)
                        } else {
                            connectButton.text = "Connect"
                            Snackbar.make(findViewById(R.id.main), "Wi-Fi failed", Snackbar.LENGTH_SHORT).show()
                        }
                    }
                }
            } else {
                // Disconnect
                receiveJob?.cancel()
                try { socket?.close() } catch (e: Exception) {}
                disconnectBLE()
                isConnected = false
                connectButton.text = "Connect"
            }
        }

        sosButton.setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("Emergency SOS")
                .setMessage("Manually send emergency alert?")
                .setPositiveButton("YES") { _, _ -> sendSOS() }
                .setNegativeButton("NO", null)
                .show()
        }

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        disconnectBLE()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERMISSION_REQUEST_CODE) {
            if (grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
                // Permissions granted
            } else {
                Toast.makeText(this, "Permissions required", Toast.LENGTH_SHORT).show()
            }
        }
    }
}
