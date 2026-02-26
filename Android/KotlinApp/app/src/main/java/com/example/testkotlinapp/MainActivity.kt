package com.example.testkotlinapp

import android.Manifest
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.ImageView
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStreamWriter
import java.net.Socket
import java.util.UUID


class MainActivity : AppCompatActivity() {

    private lateinit var imageUpdater: ImageUpdater
    private var receiveJob: Job? = null
    private val receivedImages = mutableListOf<File>()
    private lateinit var recyclerView: RecyclerView
    private lateinit var incidentLogButton: Button
    private val otherViews = mutableListOf<View>()
    private lateinit var connectButton: Button

    // ── SharedPreferences for saved BLE device ──
    private lateinit var prefs: SharedPreferences
    private val PREF_SAVED_BLE_ADDRESS = "saved_ble_address"
    private val PREF_SAVED_BLE_NAME    = "saved_ble_name"

    // ── TCP for Wi-Fi camera / traffic violation images ──
    private var socket: Socket? = null
    private var isConnected = false
    private val espIp = "192.168.4.1"
    private val espPort = 12345

    // ── BLE for anti-theft / helmet alert ──
    private val ESP32_DEVICE_NAME = "SmartHelmet_Security"
    private val SERVICE_UUID    = UUID.fromString("a1b2c3d4-e5f6-7890-abcd-ef1234567890")
    private val CHAR_ALERT_UUID = UUID.fromString("a1b2c3d4-e5f6-7890-abcd-ef1234567891")
    private val CHAR_CMD_UUID   = UUID.fromString("a1b2c3d4-e5f6-7890-abcd-ef1234567892")
    private val CCCD_UUID       = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    private var bleGatt: BluetoothGatt? = null
    private var cmdCharacteristic: BluetoothGattCharacteristic? = null
    private var isBleConnected = false
    private val REQUEST_BLUETOOTH_PERMISSION = 1001

    companion object {
        @Volatile
        var isIncidentLogOpen = false
    }

    // ════════════════════════════════════════════════════
    //  HELPERS
    // ════════════════════════════════════════════════════

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

    // ════════════════════════════════════════════════════
    //  Saved Device helpers
    // ════════════════════════════════════════════════════

    /** Persist the paired device address so we can auto-reconnect next launch. */
    private fun saveDeviceAddress(address: String, name: String) {
        prefs.edit()
            .putString(PREF_SAVED_BLE_ADDRESS, address)
            .putString(PREF_SAVED_BLE_NAME, name)
            .apply()
    }

    /** Returns the saved BLE MAC address, or null if none saved. */
    private fun getSavedDeviceAddress(): String? = prefs.getString(PREF_SAVED_BLE_ADDRESS, null)

    /**
     * Forget the paired device.
     * Call this from your Forget button: forgetDevice()
     */
    fun forgetDevice() {
        prefs.edit()
            .remove(PREF_SAVED_BLE_ADDRESS)
            .remove(PREF_SAVED_BLE_NAME)
            .apply()

        // Also disconnect if currently connected
        if (isConnected || isBleConnected) {
            performDisconnect()
        }

        Snackbar.make(
            findViewById(R.id.main),
            "Saved helmet forgotten. Tap Connect to pair again.",
            Snackbar.LENGTH_SHORT
        ).show()
    }

    // ════════════════════════════════════════════════════
    //  TCP image streaming
    // ════════════════════════════════════════════════════

    private fun startReceivingImages(button: Button) {
        receiveJob = CoroutineScope(Dispatchers.IO).launch {
            try {
                val inputStream = socket?.getInputStream() ?: return@launch
                val output = OutputStreamWriter(socket?.getOutputStream() ?: return@launch)

                output.write("START_STREAM\n")
                output.flush()

                while (isActive && isConnected) {
                    Snackbar.make(
                        findViewById(R.id.main),
                        "Start streaming images",
                        Snackbar.LENGTH_SHORT
                    ).show()
                    val imageBytes = readOneImageFromStream(inputStream) ?: break
                    Snackbar.make(
                        findViewById(R.id.main),
                        "Received image bytes",
                        Snackbar.LENGTH_SHORT
                    ).show()
                    val bitmap = BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size)
                    val filename = "img_${System.currentTimeMillis()}.jpg"
                    val file = saveBitmapToFile(this@MainActivity, bitmap, filename)
                    Snackbar.make(
                        findViewById(R.id.main),
                        "Image received in a file",
                        Snackbar.LENGTH_SHORT
                    ).show()

                    synchronized(receivedImages) {
                        receivedImages.add(file)
                    }
                    runOnUiThread {
                        Snackbar.make(
                            findViewById(R.id.main),
                            "Image receive Thread",
                            Snackbar.LENGTH_SHORT
                        ).show()
                        if (!isIncidentLogOpen) {
                            imageUpdater.addImage(file)
                            Snackbar.make(
                                findViewById(R.id.main),
                                "Image received",
                                Snackbar.LENGTH_SHORT
                            ).show()
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

    // ════════════════════════════════════════════════════
    //  TCP connect — called AFTER BLE is confirmed connected
    // ════════════════════════════════════════════════════

    private fun startTcpConnection() {
        receiveJob?.cancel()
        receiveJob = CoroutineScope(Dispatchers.IO).launch {
            val connected = withTimeoutOrNull(5000L) {
                try {
                    socket = Socket()
                    socket?.connect(java.net.InetSocketAddress(espIp, espPort), 5000)
                    socket?.isConnected == true
                } catch (e: Exception) {
                    false
                }
            } ?: false

            withContext(Dispatchers.Main) {
                connectButton.isEnabled = true
                if (connected) {
                    isConnected = true
                    connectButton.text = "Disconnect"   // ← button label updated
                    startReceivingImages(connectButton)
                } else {
                    connectButton.text = "Connect"
                    Snackbar.make(
                        findViewById(R.id.main),
                        "Camera connection failed",
                        Snackbar.LENGTH_SHORT
                    ).show()
                }
            }
        }
    }

    // ════════════════════════════════════════════════════
    //  BLE GATT callback
    // ════════════════════════════════════════════════════

    private val gattCallback = object : BluetoothGattCallback() {

        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    isBleConnected = true

                    // Save this device for future auto-reconnect
                    val deviceName = if (ActivityCompat.checkSelfPermission(
                            this@MainActivity,
                            Manifest.permission.BLUETOOTH_CONNECT
                        ) == PackageManager.PERMISSION_GRANTED
                    ) gatt.device.name ?: ESP32_DEVICE_NAME else ESP32_DEVICE_NAME

                    saveDeviceAddress(gatt.device.address, deviceName)

                    gatt.discoverServices()
                    runOnUiThread {
                        Snackbar.make(
                            findViewById(R.id.main),
                            "Helmet sensor connected",
                            Snackbar.LENGTH_SHORT
                        ).show()
                        // ── BLE connected → now start TCP camera connection ──
                        startTcpConnection()

                        // Start foreground service so BLE is monitored even in background
                        val serviceIntent = Intent(this@MainActivity, HelmetMonitorService::class.java)
                        serviceIntent.action = HelmetMonitorService.ACTION_START
                        ContextCompat.startForegroundService(this@MainActivity, serviceIntent)
                    }
                }

                BluetoothProfile.STATE_DISCONNECTED -> {
                    val wasConnected = isBleConnected
                    isBleConnected = false
                    cmdCharacteristic = null
                    bleGatt?.close()
                    bleGatt = null

                    if (wasConnected) {
                        runOnUiThread {
                            connectButton.text = "Connect"
                            connectButton.isEnabled = true

                            // Stop TCP as well
                            receiveJob?.cancel()
                            socket?.close()
                            socket = null
                            isConnected = false

                            Snackbar.make(
                                findViewById(R.id.main),
                                "⚠️ Helmet disconnected! You may have left it behind.",
                                Snackbar.LENGTH_LONG
                            ).show()
                        }

                        // Tell the foreground service to fire the disconnect notification + sound
                        val serviceIntent = Intent(this@MainActivity, HelmetMonitorService::class.java)
                        serviceIntent.action = HelmetMonitorService.ACTION_ALERT_DISCONNECT
                        ContextCompat.startForegroundService(this@MainActivity, serviceIntent)
                    }
                }
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) return

            val service = gatt.getService(SERVICE_UUID) ?: return

            cmdCharacteristic = service.getCharacteristic(CHAR_CMD_UUID)

            val alertChar = service.getCharacteristic(CHAR_ALERT_UUID) ?: return
            gatt.setCharacteristicNotification(alertChar, true)
            val descriptor = alertChar.getDescriptor(CCCD_UUID)
            descriptor?.let {
                it.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                gatt.writeDescriptor(it)
            }
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic
        ) {
            val value = characteristic.getStringValue(0)
            runOnUiThread {
                Snackbar.make(
                    findViewById(R.id.main),
                    "Helmet: $value",
                    Snackbar.LENGTH_SHORT
                ).show()
            }
        }
    }

    // ════════════════════════════════════════════════════
    //  BLE scan by name → connect GATT
    // ════════════════════════════════════════════════════

    /**
     * If we have a saved device address, connect directly without scanning.
     * Otherwise fall back to scanning by device name.
     */
    private fun startBluetoothConnection() {
        val bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        val bluetoothAdapter = bluetoothManager.adapter ?: return

        if (!bluetoothAdapter.isEnabled) {
            Snackbar.make(
                findViewById(R.id.main),
                "Please enable Bluetooth for helmet sensor",
                Snackbar.LENGTH_SHORT
            ).show()
            connectButton.isEnabled = true
            connectButton.text = "Connect"
            return
        }

        // ── Try saved device first (no scan needed) ──
        val savedAddress = getSavedDeviceAddress()
        if (savedAddress != null) {
            try {
                val device = bluetoothAdapter.getRemoteDevice(savedAddress)
                Snackbar.make(
                    findViewById(R.id.main),
                    "Reconnecting to saved helmet…",
                    Snackbar.LENGTH_SHORT
                ).show()
                bleGatt = device.connectGatt(this, false, gattCallback)
                return
            } catch (e: Exception) {
                // Saved address invalid — fall through to scan
            }
        }

        // ── No saved device — scan by name ──
        val scanner = bluetoothAdapter.bluetoothLeScanner
        val scanCallback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val name = if (ActivityCompat.checkSelfPermission(
                        this@MainActivity,
                        Manifest.permission.BLUETOOTH_CONNECT
                    ) == PackageManager.PERMISSION_GRANTED
                ) result.device.name else null

                if (name == ESP32_DEVICE_NAME) {
                    scanner.stopScan(this)
                    bleGatt = result.device.connectGatt(
                        this@MainActivity,
                        false,
                        gattCallback
                    )
                }
            }

            override fun onScanFailed(errorCode: Int) {
                runOnUiThread {
                    connectButton.isEnabled = true
                    connectButton.text = "Connect"
                    Snackbar.make(
                        findViewById(R.id.main),
                        "BLE scan failed (code $errorCode)",
                        Snackbar.LENGTH_SHORT
                    ).show()
                }
            }
        }

        scanner.startScan(scanCallback)
    }

    // ════════════════════════════════════════════════════
    //  Send ARM / DISARM command to ESP32
    // ════════════════════════════════════════════════════

    private fun sendBleCommand(command: String) {
        cmdCharacteristic?.let {
            it.value = command.toByteArray()
            bleGatt?.writeCharacteristic(it)
        }
    }

    // ════════════════════════════════════════════════════
    //  Shared disconnect logic (used by button + BLE drop)
    // ════════════════════════════════════════════════════

    private fun performDisconnect() {
        receiveJob?.cancel()
        receiveJob = null
        socket?.close()
        socket = null
        isConnected = false

        sendBleCommand("DISARM")
        bleGatt?.disconnect()
        bleGatt?.close()
        bleGatt = null
        cmdCharacteristic = null
        isBleConnected = false

        connectButton.text = "Connect"
        connectButton.isEnabled = true

        // Stop the foreground monitor service
        val serviceIntent = Intent(this, HelmetMonitorService::class.java)
        serviceIntent.action = HelmetMonitorService.ACTION_STOP
        startService(serviceIntent)

        Snackbar.make(
            findViewById(R.id.main),
            "Disconnected",
            Snackbar.LENGTH_SHORT
        ).show()
    }

    // ════════════════════════════════════════════════════
    //  Runtime BLE permission check (Android 12+)
    // ════════════════════════════════════════════════════

    private fun checkBluetoothPermissionAndConnect() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val missing = arrayOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT
            ).filter {
                ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
            }
            if (missing.isNotEmpty()) {
                ActivityCompat.requestPermissions(
                    this,
                    missing.toTypedArray(),
                    REQUEST_BLUETOOTH_PERMISSION
                )
            } else {
                startBluetoothConnection()
            }
        } else {
            startBluetoothConnection()
        }
    }

    private fun checkNotificationPermission() {
        // Only Android 13 (API 33) and above needs this runtime permission
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val permissionState = ContextCompat.checkSelfPermission(
                this,
                android.Manifest.permission.POST_NOTIFICATIONS
            )

            // If we don't have permission, ask for it
            if (permissionState != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(
                    this,
                    arrayOf(android.Manifest.permission.POST_NOTIFICATIONS),
                    101 // This is a "Request Code" you define to identify this specific request
                )
            }
        }
    }

    private fun checkBluetoothPermissionOnLaunch() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val missing = arrayOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT
            ).filter {
                ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
            }
            if (missing.isNotEmpty()) {
                ActivityCompat.requestPermissions(
                    this,
                    missing.toTypedArray(),
                    REQUEST_BLUETOOTH_PERMISSION
                )
            }
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_BLUETOOTH_PERMISSION &&
            grantResults.all { it == PackageManager.PERMISSION_GRANTED }
        ) {
            startBluetoothConnection()
        } else {
            connectButton.isEnabled = true
            connectButton.text = "Connect"
            Snackbar.make(
                findViewById(R.id.main),
                "Bluetooth permission denied — helmet alert disabled",
                Snackbar.LENGTH_SHORT
            ).show()
        }
    }

    // ════════════════════════════════════════════════════
    //  onCreate
    // ════════════════════════════════════════════════════

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_main)

        checkNotificationPermission()
        checkBluetoothPermissionOnLaunch()

        prefs = getSharedPreferences("helmet_prefs", Context.MODE_PRIVATE)

        recyclerView = findViewById(R.id.recyclerView)
        recyclerView.layoutManager = GridLayoutManager(this, 3)

        val imageView: ImageView = findViewById(R.id.imageView)
        imageView.setImageResource(R.drawable.helemt)

        connectButton = findViewById(R.id.button)
        incidentLogButton = findViewById(R.id.button3)

        otherViews.add(connectButton)
        otherViews.add(imageView)
        otherViews.add(findViewById(R.id.button2))
        otherViews.add(findViewById(R.id.button4))
        otherViews.add(findViewById(R.id.button5))
        otherViews.add(findViewById(R.id.textView2))
        otherViews.add(findViewById(R.id.textView3))

        recyclerView.visibility = View.GONE

        imageUpdater = ImageUpdater(this, recyclerView)
        imageUpdater.loadImages()

        // ── Forget button — wire up your layout button here ──
        // Replace R.id.buttonForget with whatever ID you gave it in your XML
        // If the view doesn't exist yet it won't crash; just uncomment when ready:
        //
        // findViewById<Button>(R.id.buttonForget)?.setOnClickListener {
        //     forgetDevice()
        // }

        incidentLogButton.setOnClickListener {
            val intent = Intent(this, IncidentLogActivity::class.java)
            startActivity(intent)
        }

        // ── Connect / Disconnect toggle ──
        connectButton.setOnClickListener {
            if (!isConnected && !isBleConnected) {
                connectButton.text = "Connecting…"
                connectButton.isEnabled = false
                checkBluetoothPermissionAndConnect()
            } else {
                performDisconnect()
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

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }
    }
}