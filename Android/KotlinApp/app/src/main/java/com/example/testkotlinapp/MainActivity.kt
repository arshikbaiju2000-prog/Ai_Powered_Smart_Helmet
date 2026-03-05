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
import android.view.MenuItem
import android.view.View
import android.widget.Button
import android.widget.ImageView
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.ActionBarDrawerToggle
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.GravityCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.drawerlayout.widget.DrawerLayout
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.navigation.NavigationView
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

class MainActivity : AppCompatActivity(), NavigationView.OnNavigationItemSelectedListener {

    // ── UI ──────────────────────────────────────────────────────────────────
    private lateinit var drawerLayout: DrawerLayout
    private lateinit var navigationView: NavigationView
    private lateinit var toolbar: Toolbar
    private lateinit var recyclerView: RecyclerView
    private lateinit var connectButton: Button
    private lateinit var incidentLogButton: Button
    private val otherViews = mutableListOf<View>()

    // ── Image handling ──────────────────────────────────────────────────────
    private lateinit var imageUpdater: ImageUpdater
    private var receiveJob: Job? = null
    private val receivedImages = mutableListOf<File>()

    // ── SharedPreferences ───────────────────────────────────────────────────
    private lateinit var prefs: SharedPreferences
    private val PREF_SAVED_BLE_ADDRESS = "saved_ble_address"
    private val PREF_SAVED_BLE_NAME    = "saved_ble_name"

    // ── TCP ─────────────────────────────────────────────────────────────────
    private var socket: Socket? = null
    private var isConnected = false
    private val espIp   = "192.168.4.1"
    private val espPort = 12345

    // ── BLE ─────────────────────────────────────────────────────────────────
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

    // ════════════════════════════════════════════════════════════════════════
    //  onCreate
    // ════════════════════════════════════════════════════════════════════════

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_main)

        checkNotificationPermission()
        checkBluetoothPermissionOnLaunch()

        prefs = getSharedPreferences("helmet_prefs", Context.MODE_PRIVATE)

        // ── Bind views ──────────────────────────────────────────────────────
        recyclerView      = findViewById(R.id.recyclerView)
        connectButton     = findViewById(R.id.button)
//        incidentLogButton = findViewById(R.id.button3)

        recyclerView.layoutManager = GridLayoutManager(this, 3)
        recyclerView.visibility = View.GONE

        val imageView: ImageView = findViewById(R.id.imageView)
        imageView.setImageResource(R.drawable.helemt)

        otherViews.addAll(listOf(
            connectButton,
            imageView,
//            findViewById(R.id.button2),
//            findViewById(R.id.button4),
//            findViewById(R.id.button5),
//            findViewById(R.id.textView2),
            findViewById(R.id.textView3)
        ))

        imageUpdater = ImageUpdater(this, recyclerView)
        imageUpdater.loadImages()

        // ── Toolbar + Navigation Drawer ─────────────────────────────────────
        setupToolbarAndDrawer()
        setupNavHeader()

        // ── Window insets ───────────────────────────────────────────────────
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        // ── Button click listeners ──────────────────────────────────────────

        connectButton.setOnClickListener {
            if (!isConnected && !isBleConnected) {
                connectButton.text     = "Connecting…"
                connectButton.isEnabled = false
                checkBluetoothPermissionAndConnect()
            } else {
                performDisconnect()
            }
        }

//        incidentLogButton.setOnClickListener {
//            startActivity(Intent(this, IncidentLogActivity::class.java))
//        }

//        findViewById<Button>(R.id.button2).setOnClickListener {
//            startActivity(Intent(this, RideHistoryActivity::class.java))
//        }
//
//        findViewById<Button>(R.id.button4).setOnClickListener {
//            startActivity(Intent(this, IssueStatusActivity::class.java))
//        }
//
//        findViewById<Button>(R.id.button5).setOnClickListener {
//            startActivity(Intent(this, ReportIssueActivity::class.java))
//        }

        // Uncomment when you add a Forget button to your layout:
        // findViewById<Button>(R.id.buttonForget)?.setOnClickListener { forgetDevice() }
    }

    // ════════════════════════════════════════════════════════════════════════
    //  Navigation Drawer setup
    // ════════════════════════════════════════════════════════════════════════

    private fun setupToolbarAndDrawer() {
        toolbar        = findViewById(R.id.toolbar)
        drawerLayout   = findViewById(R.id.drawerLayout)
        navigationView = findViewById(R.id.navigationView)

        setSupportActionBar(toolbar)

        val toggle = ActionBarDrawerToggle(
            this,
            drawerLayout,
            toolbar,
            R.string.navigation_drawer_open,
            R.string.navigation_drawer_close
        )
        drawerLayout.addDrawerListener(toggle)
        toggle.syncState()

        navigationView.setNavigationItemSelectedListener(this)
        navigationView.setCheckedItem(R.id.nav_home)
    }

    private fun setupNavHeader() {
        val headerView = navigationView.getHeaderView(0)
        val imgAvatar  = headerView.findViewById<ImageView>(R.id.imgAvatar)

        imgAvatar.setImageResource(R.drawable.ic_launcher_foreground)

        // Load a real user photo with Glide when ready:
        // Glide.with(this).load(userPhotoUrl).placeholder(R.drawable.ic_default_avatar)
        //     .circleCrop().into(imgAvatar)
    }

    // ── Sidebar item clicks ─────────────────────────────────────────────────

    override fun onNavigationItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.nav_home          -> { /* already here */ }
            R.id.nav_ride_history  -> startActivity(Intent(this, RideHistoryActivity::class.java))
            R.id.nav_incident_log  -> startActivity(Intent(this, IncidentLogActivity::class.java))
            R.id.nav_issue_status  -> startActivity(Intent(this, IssueStatusActivity::class.java))
            R.id.nav_report_issue  -> startActivity(Intent(this, ReportIssueActivity::class.java))
            R.id.nav_find_my_device -> startActivity(Intent(this, FindMyDeviceActivity::class.java))
        }
        drawerLayout.closeDrawer(GravityCompat.START)
        return true
    }

    // ── Back press closes drawer first if open ──────────────────────────────

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (drawerLayout.isDrawerOpen(GravityCompat.START)) {
            drawerLayout.closeDrawer(GravityCompat.START)
        } else {
            super.onBackPressed()
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    //  Helpers
    // ════════════════════════════════════════════════════════════════════════

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
                ((sizeBuffer[2].toInt() and 0xFF) shl 8)  or
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

    // ════════════════════════════════════════════════════════════════════════
    //  Saved device helpers
    // ════════════════════════════════════════════════════════════════════════

    private fun saveDeviceAddress(address: String, name: String) {
        prefs.edit()
            .putString(PREF_SAVED_BLE_ADDRESS, address)
            .putString(PREF_SAVED_BLE_NAME, name)
            .apply()
    }

    private fun getSavedDeviceAddress(): String? = prefs.getString(PREF_SAVED_BLE_ADDRESS, null)

    fun forgetDevice() {
        prefs.edit()
            .remove(PREF_SAVED_BLE_ADDRESS)
            .remove(PREF_SAVED_BLE_NAME)
            .apply()
        if (isConnected || isBleConnected) performDisconnect()
        Snackbar.make(
            findViewById(R.id.main),
            "Saved helmet forgotten. Tap Connect to pair again.",
            Snackbar.LENGTH_SHORT
        ).show()
    }

    // ════════════════════════════════════════════════════════════════════════
    //  TCP image streaming
    // ════════════════════════════════════════════════════════════════════════

    private fun startReceivingImages(button: Button) {
        receiveJob = CoroutineScope(Dispatchers.IO).launch {
            try {
                val inputStream = socket?.getInputStream() ?: return@launch
                val output = OutputStreamWriter(socket?.getOutputStream() ?: return@launch)
                output.write("START_STREAM\n")
                output.flush()

                while (isActive && isConnected) {
                    val imageBytes = readOneImageFromStream(inputStream) ?: break
                    val bitmap     = BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size)
                    val file       = saveBitmapToFile(this@MainActivity, bitmap, "img_${System.currentTimeMillis()}.jpg")

                    synchronized(receivedImages) { receivedImages.add(file) }

                    runOnUiThread {
                        if (!isIncidentLogOpen) imageUpdater.addImage(file)
                    }
                }

                withContext(Dispatchers.Main) {
                    isConnected  = false
                    button.text  = "Connect"
                    Snackbar.make(findViewById(R.id.main), "Connection closed", Snackbar.LENGTH_SHORT).show()
                }
                socket?.close()
                socket = null

            } catch (ex: Exception) {
                withContext(Dispatchers.Main) {
                    isConnected  = false
                    button.text  = "Connect"
                    Snackbar.make(findViewById(R.id.main), "Connection error", Snackbar.LENGTH_SHORT).show()
                }
                ex.printStackTrace()
            }
        }
    }

    private fun startTcpConnection() {
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
                    isConnected       = true
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

    // ════════════════════════════════════════════════════════════════════════
    //  BLE GATT callback
    // ════════════════════════════════════════════════════════════════════════

    private val gattCallback = object : BluetoothGattCallback() {

        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    isBleConnected = true
                    val deviceName = if (ActivityCompat.checkSelfPermission(
                            this@MainActivity, Manifest.permission.BLUETOOTH_CONNECT
                        ) == PackageManager.PERMISSION_GRANTED
                    ) gatt.device.name ?: ESP32_DEVICE_NAME else ESP32_DEVICE_NAME

                    saveDeviceAddress(gatt.device.address, deviceName)
                    gatt.discoverServices()

                    runOnUiThread {
                        Snackbar.make(
                            findViewById(R.id.main), "Helmet sensor connected", Snackbar.LENGTH_SHORT
                        ).show()
                        startTcpConnection()

                        val serviceIntent = Intent(this@MainActivity, HelmetMonitorService::class.java)
                        serviceIntent.action = HelmetMonitorService.ACTION_START
                        ContextCompat.startForegroundService(this@MainActivity, serviceIntent)
                    }
                }

                BluetoothProfile.STATE_DISCONNECTED -> {
                    val wasConnected = isBleConnected
                    isBleConnected    = false
                    cmdCharacteristic = null
                    bleGatt?.close()
                    bleGatt = null

                    if (wasConnected) {
                        runOnUiThread {
                            connectButton.text      = "Connect"
                            connectButton.isEnabled = true
                            receiveJob?.cancel()
                            socket?.close()
                            socket      = null
                            isConnected = false
                            Snackbar.make(
                                findViewById(R.id.main),
                                "⚠️ Helmet disconnected! You may have left it behind.",
                                Snackbar.LENGTH_LONG
                            ).show()
                        }

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
            alertChar.getDescriptor(CCCD_UUID)?.let {
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
                    findViewById(R.id.main), "Helmet: $value", Snackbar.LENGTH_SHORT
                ).show()
            }
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    //  BLE scan / connect
    // ════════════════════════════════════════════════════════════════════════

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
            connectButton.text      = "Connect"
            return
        }

        // Try saved address first
        val savedAddress = getSavedDeviceAddress()
        if (savedAddress != null) {
            try {
                val device = bluetoothAdapter.getRemoteDevice(savedAddress)
                Snackbar.make(
                    findViewById(R.id.main), "Reconnecting to saved helmet…", Snackbar.LENGTH_SHORT
                ).show()
                bleGatt = device.connectGatt(this, false, gattCallback)
                return
            } catch (e: Exception) { /* fall through to scan */ }
        }

        // Scan by name
        val scanner = bluetoothAdapter.bluetoothLeScanner
        val scanCallback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val name = if (ActivityCompat.checkSelfPermission(
                        this@MainActivity, Manifest.permission.BLUETOOTH_CONNECT
                    ) == PackageManager.PERMISSION_GRANTED
                ) result.device.name else null

                if (name == ESP32_DEVICE_NAME) {
                    scanner.stopScan(this)
                    bleGatt = result.device.connectGatt(this@MainActivity, false, gattCallback)
                }
            }

            override fun onScanFailed(errorCode: Int) {
                runOnUiThread {
                    connectButton.isEnabled = true
                    connectButton.text      = "Connect"
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

    // ════════════════════════════════════════════════════════════════════════
    //  BLE command + disconnect
    // ════════════════════════════════════════════════════════════════════════

    private fun sendBleCommand(command: String) {
        cmdCharacteristic?.let {
            it.value = command.toByteArray()
            bleGatt?.writeCharacteristic(it)
        }
    }

    private fun performDisconnect() {
        receiveJob?.cancel()
        receiveJob  = null
        socket?.close()
        socket      = null
        isConnected = false

        sendBleCommand("DISARM")
        bleGatt?.disconnect()
        bleGatt?.close()
        bleGatt           = null
        cmdCharacteristic = null
        isBleConnected    = false

        connectButton.text      = "Connect"
        connectButton.isEnabled = true

        val serviceIntent = Intent(this, HelmetMonitorService::class.java)
        serviceIntent.action = HelmetMonitorService.ACTION_STOP
        startService(serviceIntent)

        Snackbar.make(
            findViewById(R.id.main), "Disconnected", Snackbar.LENGTH_SHORT
        ).show()
    }

    // ════════════════════════════════════════════════════════════════════════
    //  Permissions
    // ════════════════════════════════════════════════════════════════════════

    private fun checkBluetoothPermissionAndConnect() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val missing = arrayOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT
            ).filter {
                ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
            }
            if (missing.isNotEmpty()) {
                ActivityCompat.requestPermissions(this, missing.toTypedArray(), REQUEST_BLUETOOTH_PERMISSION)
            } else {
                startBluetoothConnection()
            }
        } else {
            startBluetoothConnection()
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
                ActivityCompat.requestPermissions(this, missing.toTypedArray(), REQUEST_BLUETOOTH_PERMISSION)
            }
        }
    }

    private fun checkNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                ActivityCompat.requestPermissions(
                    this,
                    arrayOf(android.Manifest.permission.POST_NOTIFICATIONS),
                    101
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
        } else if (requestCode == REQUEST_BLUETOOTH_PERMISSION) {
            connectButton.isEnabled = true
            connectButton.text      = "Connect"
            Snackbar.make(
                findViewById(R.id.main),
                "Bluetooth permission denied — helmet alert disabled",
                Snackbar.LENGTH_SHORT
            ).show()
        }
    }
}