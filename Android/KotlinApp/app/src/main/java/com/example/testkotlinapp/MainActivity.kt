package com.example.testkotlinapp

import android.Manifest
import android.annotation.SuppressLint
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
import com.parse.ParseUser
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

    private lateinit var drawerLayout: DrawerLayout
    private lateinit var navigationView: NavigationView
    private lateinit var toolbar: Toolbar
    private lateinit var recyclerView: RecyclerView
    private lateinit var connectButton: Button
    private val otherViews = mutableListOf<View>()

    private lateinit var imageUpdater: ImageUpdater
    private var receiveJob: Job? = null
    private val receivedImages = mutableListOf<File>()

    private lateinit var prefs: SharedPreferences
    private val PREF_SAVED_BLE_ADDRESS = "saved_ble_address"
    private val PREF_SAVED_BLE_NAME    = "saved_ble_name"

    private var socket: Socket? = null
    private var isConnected = false
    private val espIp   = "192.168.4.1"
    private val espPort = 12345

    private val ESP32_DEVICE_NAME = "SmartHelmet_Security"
    private val SERVICE_UUID    = UUID.fromString("a1b2c3d4-e5f6-7890-abcd-ef1234567890")
    private val CHAR_ALERT_UUID = UUID.fromString("a1b2c3d4-e5f6-7890-abcd-ef1234567891")
    private val CHAR_CMD_UUID   = UUID.fromString("a1b2c3d4-e5f6-7890-abcd-ef1234567892")
    private val CCCD_UUID       = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    private var bleGatt: BluetoothGatt? = null
    private var cmdCharacteristic: BluetoothGattCharacteristic? = null
    private var isBleConnected = false
    private val REQUEST_ALL_PERMISSIONS = 1010
    private var isPermissionRequestPending = false

    companion object {
        @Volatile
        var isIncidentLogOpen = false
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (ParseUser.getCurrentUser() == null) {
            startActivity(Intent(this, LoginActivity::class.java))
            finish()
            return
        }
        enableEdgeToEdge()
        setContentView(R.layout.activity_main)
        prefs = getSharedPreferences("helmet_prefs", Context.MODE_PRIVATE)
        requestRequiredPermissions()

        recyclerView  = findViewById(R.id.recyclerView)
        connectButton = findViewById(R.id.button)
        recyclerView.layoutManager = GridLayoutManager(this, 3)
        recyclerView.visibility = View.GONE

        val imageView: ImageView = findViewById(R.id.imageView)
        imageView.setImageResource(R.drawable.helemt)
        otherViews.addAll(listOf(connectButton, imageView, findViewById(R.id.textView3)))

        imageUpdater = ImageUpdater(this, recyclerView)
        imageUpdater.loadImages()
        setupToolbarAndDrawer()
        setupNavHeader()

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        connectButton.setOnClickListener {
            if (!isConnected && !isBleConnected) {
                connectButton.text = "Connecting…"
                connectButton.isEnabled = false
                checkBluetoothPermissionAndConnect()
            } else {
                performDisconnect()
            }
        }
    }

    private fun requestRequiredPermissions() {
        if (isPermissionRequestPending) return
        val permissions = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                permissions.add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED) {
                permissions.add(Manifest.permission.BLUETOOTH_SCAN)
            }
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                permissions.add(Manifest.permission.BLUETOOTH_CONNECT)
            }
        } else {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                permissions.add(Manifest.permission.ACCESS_FINE_LOCATION)
            }
        }
        if (permissions.isNotEmpty()) {
            isPermissionRequestPending = true
            ActivityCompat.requestPermissions(this, permissions.toTypedArray(), REQUEST_ALL_PERMISSIONS)
        }
    }

    private fun checkBluetoothPermissionAndConnect() {
        val hasScan = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED else true
        val hasConnect = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED else true
        if (hasScan && hasConnect) startBluetoothConnection() else requestRequiredPermissions()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_ALL_PERMISSIONS) {
            isPermissionRequestPending = false
            val btScanGranted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) permissions.indices.find { permissions[it] == Manifest.permission.BLUETOOTH_SCAN }?.let { grantResults[it] == PackageManager.PERMISSION_GRANTED } ?: true else true
            val btConnectGranted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) permissions.indices.find { permissions[it] == Manifest.permission.BLUETOOTH_CONNECT }?.let { grantResults[it] == PackageManager.PERMISSION_GRANTED } ?: true else true
            if (btScanGranted && btConnectGranted) {
                if (connectButton.text == "Connecting…") startBluetoothConnection()
            } else {
                connectButton.isEnabled = true
                connectButton.text = "Connect"
                Snackbar.make(findViewById(R.id.main), "Permissions denied", Snackbar.LENGTH_SHORT).show()
            }
        }
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    isBleConnected = true
                    val deviceName = if (ActivityCompat.checkSelfPermission(this@MainActivity, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED) gatt.device.name ?: ESP32_DEVICE_NAME else ESP32_DEVICE_NAME
                    saveDeviceAddress(gatt.device.address, deviceName)
                    if (ActivityCompat.checkSelfPermission(this@MainActivity, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED) gatt.discoverServices()
                    runOnUiThread {
                        Snackbar.make(findViewById(R.id.main), "Helmet sensor connected", Snackbar.LENGTH_SHORT).show()
                        startTcpConnection()
                        val serviceIntent = Intent(this@MainActivity, HelmetMonitorService::class.java).apply { action = HelmetMonitorService.ACTION_START }
                        ContextCompat.startForegroundService(this@MainActivity, serviceIntent)
                    }
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    val wasConnected = isBleConnected
                    isBleConnected = false
                    cmdCharacteristic = null
                    if (ActivityCompat.checkSelfPermission(this@MainActivity, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED) bleGatt?.close()
                    bleGatt = null
                    if (wasConnected) {
                        runOnUiThread {
                            connectButton.text = "Connect"
                            connectButton.isEnabled = true
                            receiveJob?.cancel()
                            socket?.close()
                            socket = null
                            isConnected = false
                        }
                        val serviceIntent = Intent(this@MainActivity, HelmetMonitorService::class.java).apply { action = HelmetMonitorService.ACTION_ALERT_DISCONNECT }
                        ContextCompat.startForegroundService(this@MainActivity, serviceIntent)
                    }
                }
            }
        }

        @SuppressLint("MissingPermission")
        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) return
            val service = gatt.getService(SERVICE_UUID) ?: return
            cmdCharacteristic = service.getCharacteristic(CHAR_CMD_UUID)
            val alertChar = service.getCharacteristic(CHAR_ALERT_UUID) ?: return
            if (ActivityCompat.checkSelfPermission(this@MainActivity, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED) {
                gatt.setCharacteristicNotification(alertChar, true)
                alertChar.getDescriptor(CCCD_UUID)?.let {
                    it.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                    gatt.writeDescriptor(it)
                }
            }
        }

        @Deprecated("Deprecated in Java")
        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            val value = characteristic.getStringValue(0)
            runOnUiThread { Snackbar.make(findViewById(R.id.main), "Helmet: $value", Snackbar.LENGTH_SHORT).show() }
        }
    }

    private fun startBluetoothConnection() {
        val bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        val bluetoothAdapter = bluetoothManager.adapter ?: return
        if (!bluetoothAdapter.isEnabled) {
            Snackbar.make(findViewById(R.id.main), "Please enable Bluetooth", Snackbar.LENGTH_SHORT).show()
            connectButton.isEnabled = true
            connectButton.text = "Connect"
            return
        }
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            requestRequiredPermissions()
            return
        }
        val savedAddress = getSavedDeviceAddress()
        if (savedAddress != null) {
            try {
                val device = bluetoothAdapter.getRemoteDevice(savedAddress)
                bleGatt = device.connectGatt(this, false, gattCallback)
                return
            } catch (e: Exception) { }
        }
        val scanner = bluetoothAdapter.bluetoothLeScanner
        val scanCallback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                if (ActivityCompat.checkSelfPermission(this@MainActivity, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) return
                val name = result.device.name
                if (name == ESP32_DEVICE_NAME) {
                    if (ActivityCompat.checkSelfPermission(this@MainActivity, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED) scanner.stopScan(this)
                    bleGatt = result.device.connectGatt(this@MainActivity, false, gattCallback)
                }
            }
            override fun onScanFailed(errorCode: Int) {
                runOnUiThread { connectButton.isEnabled = true; connectButton.text = "Connect" }
            }
        }
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED) scanner.startScan(scanCallback)
    }

    private fun sendBleCommand(command: String) {
        cmdCharacteristic?.let {
            it.value = command.toByteArray()
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED) bleGatt?.writeCharacteristic(it)
        }
    }

    private fun performDisconnect() {
        receiveJob?.cancel()
        receiveJob = null
        socket?.close()
        socket = null
        isConnected = false
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED) {
            sendBleCommand("DISARM")
            bleGatt?.disconnect()
            bleGatt?.close()
        }
        bleGatt = null
        cmdCharacteristic = null
        isBleConnected = false
        connectButton.text = "Connect"
        connectButton.isEnabled = true
        val serviceIntent = Intent(this, HelmetMonitorService::class.java).apply { action = HelmetMonitorService.ACTION_STOP }
        startService(serviceIntent)
        Snackbar.make(findViewById(R.id.main), "Disconnected", Snackbar.LENGTH_SHORT).show()
    }

    private fun saveBitmapToFile(context: Context, bitmap: Bitmap, filename: String): File {
        val file = File(context.filesDir, filename)
        FileOutputStream(file).use { out -> bitmap.compress(Bitmap.CompressFormat.JPEG, 90, out) }
        return file
    }

    private fun readOneImageFromStream(inputStream: InputStream): ByteArray? {
        val sizeBuffer = ByteArray(4); var bytesRead = 0
        while (bytesRead < 4) { val r = inputStream.read(sizeBuffer, bytesRead, 4 - bytesRead); if (r == -1) return null; bytesRead += r }
        val size = ((sizeBuffer[0].toInt() and 0xFF) shl 24) or ((sizeBuffer[1].toInt() and 0xFF) shl 16) or ((sizeBuffer[2].toInt() and 0xFF) shl 8) or (sizeBuffer[3].toInt() and 0xFF)
        if (size <= 0) return null
        val buf = ByteArray(size); bytesRead = 0
        while (bytesRead < size) { val c = inputStream.read(buf, bytesRead, size - bytesRead); if (c == -1) break; bytesRead += c }
        return if (bytesRead == size) buf else null
    }

    private fun saveDeviceAddress(a: String, n: String) {
        prefs.edit().putString(PREF_SAVED_BLE_ADDRESS, a).putString(PREF_SAVED_BLE_NAME, n).apply()
    }

    private fun getSavedDeviceAddress(): String? = prefs.getString(PREF_SAVED_BLE_ADDRESS, null)

    private fun startReceivingImages(b: Button) {
        receiveJob = CoroutineScope(Dispatchers.IO).launch {
            try {
                val isr = socket?.getInputStream() ?: return@launch
                val osw = OutputStreamWriter(socket?.getOutputStream() ?: return@launch)
                osw.write("START_STREAM\n"); osw.flush()
                while (isActive && isConnected) {
                    val bytes = readOneImageFromStream(isr) ?: break
                    val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                    val file = saveBitmapToFile(this@MainActivity, bmp, "img_${System.currentTimeMillis()}.jpg")
                    synchronized(receivedImages) { receivedImages.add(file) }
                    runOnUiThread { if (!isIncidentLogOpen) imageUpdater.addImage(file) }
                }
                withContext(Dispatchers.Main) { isConnected = false; b.text = "Connect" }
            } catch (e: Exception) { withContext(Dispatchers.Main) { isConnected = false; b.text = "Connect" } }
        }
    }

    private fun startTcpConnection() {
        receiveJob?.cancel()
        receiveJob = CoroutineScope(Dispatchers.IO).launch {
            val ok = withTimeoutOrNull(5000L) { try { socket = Socket(); socket?.connect(java.net.InetSocketAddress(espIp, espPort), 5000); socket?.isConnected == true } catch (e: Exception) { false } } ?: false
            withContext(Dispatchers.Main) { connectButton.isEnabled = true; if (ok) { isConnected = true; connectButton.text = "Disconnect"; startReceivingImages(connectButton) } else { connectButton.text = "Connect" } }
        }
    }

    private fun setupToolbarAndDrawer() {
        toolbar = findViewById(R.id.toolbar); drawerLayout = findViewById(R.id.drawerLayout); navigationView = findViewById(R.id.navigationView)
        setSupportActionBar(toolbar); val t = ActionBarDrawerToggle(this, drawerLayout, toolbar, R.string.navigation_drawer_open, R.string.navigation_drawer_close)
        drawerLayout.addDrawerListener(t); t.syncState(); navigationView.setNavigationItemSelectedListener(this); navigationView.setCheckedItem(R.id.nav_home)
    }

    private fun setupNavHeader() { val h = navigationView.getHeaderView(0); h.findViewById<ImageView>(R.id.imgAvatar).setImageResource(R.drawable.ic_launcher_foreground) }

    override fun onNavigationItemSelected(i: MenuItem): Boolean {
        when (i.itemId) {
            R.id.nav_ride_history -> startActivity(Intent(this, RideHistoryActivity::class.java))
            R.id.nav_incident_log -> startActivity(Intent(this, IncidentLogActivity::class.java))
            R.id.nav_logout -> { ParseUser.logOut(); if (isConnected || isBleConnected) performDisconnect(); val intent = Intent(this, LoginActivity::class.java).apply { flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK }; startActivity(intent); finish() }
        }
        drawerLayout.closeDrawer(GravityCompat.START); return true
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() { if (drawerLayout.isDrawerOpen(GravityCompat.START)) drawerLayout.closeDrawer(GravityCompat.START) else super.onBackPressed() }
}