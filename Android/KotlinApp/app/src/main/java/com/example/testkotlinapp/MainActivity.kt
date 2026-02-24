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
                    connectButton.text = "Disconnect"
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
                    gatt.discoverServices()
                    runOnUiThread {
                        Snackbar.make(
                            findViewById(R.id.main),
                            "Helmet sensor connected",
                            Snackbar.LENGTH_SHORT
                        ).show()
                        // ── BLE connected → now start TCP camera connection ──
                        startTcpConnection()
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
                            Snackbar.make(
                                findViewById(R.id.main),
                                "⚠️ Helmet disconnected! You may have left it behind.",
                                Snackbar.LENGTH_LONG
                            ).show()
                        }
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

        val scanner = bluetoothAdapter.bluetoothLeScanner
        val scanCallback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                if (result.device.name == ESP32_DEVICE_NAME) {
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

        incidentLogButton.setOnClickListener {
            val intent = android.content.Intent(this, IncidentLogActivity::class.java)
            startActivity(intent)
        }

        connectButton.setOnClickListener {
            val button = it as Button
            if (!isConnected) {
                // ── Step 1: Disable button and start BLE scan ──
                // TCP will automatically start inside gattCallback once BLE connects
                button.text = "Connecting..."
                button.isEnabled = false
                checkBluetoothPermissionAndConnect()
            } else {
                // ── Disconnect both BLE and TCP ──
                receiveJob?.cancel()
                receiveJob = null
                socket?.close()
                socket = null
                isConnected = false
                button.text = "Connect"

                sendBleCommand("DISARM")
                bleGatt?.disconnect()
                bleGatt?.close()
                bleGatt = null
                cmdCharacteristic = null
                isBleConnected = false

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

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }
    }
}