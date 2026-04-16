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
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.telephony.SmsManager
import android.util.Log
import android.view.MenuItem
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.ActionBarDrawerToggle
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.appcompat.widget.Toolbar
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.core.view.GravityCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.drawerlayout.widget.DrawerLayout
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
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
    private lateinit var fabVoice: com.google.android.material.floatingactionbutton.FloatingActionButton
    private val otherViews = mutableListOf<View>()

    enum class VoiceState { IDLE, RECORDING, WAITING, RESPONSE }
    private var voiceState = VoiceState.IDLE
    private val audioHandler = AudioHandler()
    private val geminiRepository = GeminiRepository()

    private lateinit var imageUpdater: ImageUpdater
    private var receiveJob: Job? = null
    private val receivedImages = mutableListOf<File>()

    private lateinit var prefs: SharedPreferences
    private val prefSavedBleAddress = "saved_ble_address"
    private val prefSavedBleName    = "saved_ble_name"

    private var socket: Socket? = null
    private var isConnected = false
    private val espIp   = "192.168.4.1"
    private val espPort = 12345

    private val esp32DeviceName = "SmartHelmet_Security"
    private val serviceUuid    = UUID.fromString("a1b2c3d4-e5f6-7890-abcd-ef1234567890")
    private val charAlertUuid = UUID.fromString("a1b2c3d4-e5f6-7890-abcd-ef1234567891")
    private val charCmdUuid   = UUID.fromString("a1b2c3d4-e5f6-7890-abcd-ef1234567892")
    private val cccdUuid       = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    private var bleGatt: BluetoothGatt? = null
    private var cmdCharacteristic: BluetoothGattCharacteristic? = null
    private var isBleConnected = false
    private val requestAllPermissions = 1010
    private var isPermissionRequestPending = false

    // SOS & Accident Detection Variables
    private var accidentHandled = false
    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private val emergencyNumber = "+916238009232"
    private val TAG = "HelmetBLE"

    private val noiseCancelReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == "com.example.testkotlinapp.NOISE_CANCEL_CHANGED") {
                val enabled = intent.getBooleanExtra("enabled", false)
                if (isBleConnected) {
                    sendBleCommand(if (enabled) "ANC_ON" else "ANC_OFF")
                }
            }
        }
    }

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
        
        // Apply saved theme preference
        val appPrefs = getSharedPreferences("app_prefs", MODE_PRIVATE)
        val themeMode = appPrefs.getInt("theme_mode", AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
        AppCompatDelegate.setDefaultNightMode(themeMode)

        enableEdgeToEdge()
        setContentView(R.layout.activity_main)

        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        prefs = getSharedPreferences("helmet_prefs", MODE_PRIVATE)
        requestRequiredPermissions()

        recyclerView  = findViewById(R.id.recyclerView)
        connectButton = findViewById(R.id.button)
        recyclerView.layoutManager = GridLayoutManager(this, 3)
        recyclerView.visibility = View.GONE

        val imageView: ImageView = findViewById(R.id.imageView)
        imageView.setImageResource(R.drawable.helemt)
        fabVoice = findViewById(R.id.fab_voice)
        otherViews.addAll(listOf(connectButton, imageView, findViewById(R.id.textView3), fabVoice))

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
                connectButton.text = getString(R.string.connecting)
                connectButton.isEnabled = false
                checkBluetoothPermissionAndConnect()
            } else {
                performDisconnect()
            }
        }

        fabVoice.setOnClickListener {
            handleVoiceButtonClicked()
        }

        val filter = IntentFilter("com.example.testkotlinapp.NOISE_CANCEL_CHANGED")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(noiseCancelReceiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(noiseCancelReceiver, filter)
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (drawerLayout.isDrawerOpen(GravityCompat.START)) {
                    drawerLayout.closeDrawer(GravityCompat.START)
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        })
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(noiseCancelReceiver)
    }

    private fun requestRequiredPermissions() {
        if (isPermissionRequestPending) return
        val permissions = mutableListOf<String>()
        
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.SEND_SMS) != PackageManager.PERMISSION_GRANTED) {
            permissions.add(Manifest.permission.SEND_SMS)
        }
        
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
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            permissions.add(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            permissions.add(Manifest.permission.ACCESS_COARSE_LOCATION)
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            permissions.add(Manifest.permission.RECORD_AUDIO)
        }
        if (permissions.isNotEmpty()) {
            isPermissionRequestPending = true
            ActivityCompat.requestPermissions(this, permissions.toTypedArray(), requestAllPermissions)
        }
    }

    private fun checkBluetoothPermissionAndConnect() {
        val hasScan = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED else true
        val hasConnect = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED else true
        if (hasScan && hasConnect) startBluetoothConnection() else requestRequiredPermissions()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == requestAllPermissions) {
            isPermissionRequestPending = false
            val btScanGranted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) permissions.indices.find { permissions[it] == Manifest.permission.BLUETOOTH_SCAN }?.let { grantResults[it] == PackageManager.PERMISSION_GRANTED } ?: true else true
            val btConnectGranted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) permissions.indices.find { permissions[it] == Manifest.permission.BLUETOOTH_CONNECT }?.let { grantResults[it] == PackageManager.PERMISSION_GRANTED } ?: true else true
            if (btScanGranted && btConnectGranted) {
                if (connectButton.text == getString(R.string.connecting)) startBluetoothConnection()
            } else {
                connectButton.isEnabled = true
                connectButton.text = getString(R.string.connect)
                Snackbar.make(findViewById(R.id.main), "Permissions denied", Snackbar.LENGTH_SHORT).show()
            }
        }
    }

    private val gattCallback = object : BluetoothGattCallback() {
        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    isBleConnected = true
                    val deviceName = gatt.device.name ?: esp32DeviceName
                    saveDeviceAddress(gatt.device.address, deviceName)
                    gatt.discoverServices()
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
                    bleGatt?.close()
                    bleGatt = null
                    if (wasConnected) {
                        runOnUiThread {
                            connectButton.text = getString(R.string.connect)
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
            val service = gatt.getService(serviceUuid) ?: return
            cmdCharacteristic = service.getCharacteristic(charCmdUuid)
            
            // Check if noise cancellation was already enabled in prefs and send command
            val appPrefs = getSharedPreferences("app_prefs", MODE_PRIVATE)
            val noiseCancelEnabled = appPrefs.getBoolean("noise_cancellation", false)
            if (noiseCancelEnabled) {
                sendBleCommand("ANC_ON")
            }

            val alertChar = service.getCharacteristic(charAlertUuid) ?: return
            gatt.setCharacteristicNotification(alertChar, true)
            alertChar.getDescriptor(cccdUuid)?.let {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    gatt.writeDescriptor(it, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
                } else {
                    @Suppress("DEPRECATION")
                    it.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                    @Suppress("DEPRECATION")
                    gatt.writeDescriptor(it)
                }
            }
        }

        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) {
            val stringValue = String(value)
            Log.d(TAG, "Signal received from helmet: $stringValue")
            runOnUiThread { 
                Snackbar.make(findViewById(R.id.main), "Helmet: $stringValue", Snackbar.LENGTH_SHORT).show() 
                if (stringValue.contains("ACCIDENT") && !accidentHandled) {
                    accidentHandled = true
                    Log.e(TAG, "ACCIDENT SIGNAL RECEIVED! Triggering SOS...")
                    sendSOS()
                }
            }
        }

        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            val value = characteristic.value ?: return
            onCharacteristicChanged(gatt, characteristic, value)
        }
    }

    private fun startBluetoothConnection() {
        val bluetoothManager = getSystemService(BLUETOOTH_SERVICE) as BluetoothManager
        val bluetoothAdapter = bluetoothManager.adapter ?: return
        if (!bluetoothAdapter.isEnabled) {
            Snackbar.make(findViewById(R.id.main), "Please enable Bluetooth", Snackbar.LENGTH_SHORT).show()
            connectButton.isEnabled = true
            connectButton.text = getString(R.string.connect)
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
                if (name == esp32DeviceName) {
                    if (ActivityCompat.checkSelfPermission(this@MainActivity, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED) scanner.stopScan(this)
                    bleGatt = result.device.connectGatt(this@MainActivity, false, gattCallback)
                }
            }
            override fun onScanFailed(errorCode: Int) {
                runOnUiThread { connectButton.isEnabled = true; connectButton.text = getString(R.string.connect) }
            }
        }
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED) scanner.startScan(scanCallback)
    }

    private fun sendBleCommand(command: String) {
        cmdCharacteristic?.let {
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    bleGatt?.writeCharacteristic(it, command.toByteArray(), BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT)
                } else {
                    @Suppress("DEPRECATION")
                    it.value = command.toByteArray()
                    @Suppress("DEPRECATION")
                    bleGatt?.writeCharacteristic(it)
                }
            }
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
        connectButton.text = getString(R.string.connect)
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
        prefs.edit {
            putString(prefSavedBleAddress, a)
            putString(prefSavedBleName, n)
        }
    }

    private fun getSavedDeviceAddress(): String? = prefs.getString(prefSavedBleAddress, null)

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
                withContext(Dispatchers.Main) { isConnected = false; b.text = getString(R.string.connect) }
            } catch (e: Exception) { withContext(Dispatchers.Main) { isConnected = false; b.text = getString(R.string.connect) } }
        }
    }

    private fun startTcpConnection() {
        receiveJob?.cancel()
        receiveJob = CoroutineScope(Dispatchers.IO).launch {
            val ok = withTimeoutOrNull(5000L) { try { socket = Socket(); socket?.connect(java.net.InetSocketAddress(espIp, espPort), 5000); socket?.isConnected == true } catch (e: Exception) { false } } ?: false
            withContext(Dispatchers.Main) { connectButton.isEnabled = true; if (ok) { isConnected = true; connectButton.text = getString(R.string.disconnect); startReceivingImages(connectButton) } else { connectButton.text = getString(R.string.connect) } }
        }
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

    private fun setupToolbarAndDrawer() {
        toolbar = findViewById(R.id.toolbar); drawerLayout = findViewById(R.id.drawerLayout); navigationView = findViewById(R.id.navigationView)
        setSupportActionBar(toolbar); val t = ActionBarDrawerToggle(this, drawerLayout, toolbar, R.string.navigation_drawer_open, R.string.navigation_drawer_close)
        drawerLayout.addDrawerListener(t); t.syncState(); navigationView.setNavigationItemSelectedListener(this); navigationView.setCheckedItem(R.id.nav_home)
    }

    private fun handleVoiceButtonClicked() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestRequiredPermissions()
            return
        }

        when (voiceState) {
            VoiceState.IDLE, VoiceState.RESPONSE -> startVoiceRecording()
            VoiceState.RECORDING -> stopVoiceRecordingAndSend()
            VoiceState.WAITING -> { /* Do nothing while waiting */ }
        }
    }

    private fun startVoiceRecording() {
        voiceState = VoiceState.RECORDING
        fabVoice.setImageResource(android.R.drawable.ic_media_pause) // Stop icon
        audioHandler.startRecording(CoroutineScope(Dispatchers.IO))
        Snackbar.make(findViewById(R.id.main), "Recording started...", Snackbar.LENGTH_SHORT).show()
    }

    private fun stopVoiceRecordingAndSend() {
        voiceState = VoiceState.WAITING
        fabVoice.setImageResource(android.R.drawable.ic_popup_sync) // Spinner/sync icon
        Snackbar.make(findViewById(R.id.main), "Sending to Gemini...", Snackbar.LENGTH_SHORT).show()
        
        CoroutineScope(Dispatchers.Main).launch {
            try {
                val pcmData = audioHandler.stopRecordingAndGetPCM()
                if (pcmData.isEmpty()) {
                    resetVoiceState()
                    return@launch
                }
                
                val result = geminiRepository.generateContent(pcmData)
                if (result.isSuccess) {
                    val response = result.getOrNull()
                    if (response != null) {
                        voiceState = VoiceState.RESPONSE
                        fabVoice.setImageResource(android.R.drawable.ic_lock_silent_mode_off) // Speaker icon
                        
                        if (!response.text.isNullOrEmpty()) {
                            Snackbar.make(findViewById(R.id.main), response.text, Snackbar.LENGTH_LONG).show()
                        }
                        
                        if (!response.audioPcmBase64.isNullOrEmpty()) {
                            val audioBytes = android.util.Base64.decode(response.audioPcmBase64, android.util.Base64.NO_WRAP)
                            withContext(Dispatchers.IO) {
                                audioHandler.playAudio(audioBytes)
                                withContext(Dispatchers.Main) {
                                    resetVoiceState()
                                }
                            }
                        } else {
                            resetVoiceState()
                        }
                    } else {
                        resetVoiceState()
                    }
                } else {
                    Snackbar.make(findViewById(R.id.main), "Error: ${result.exceptionOrNull()?.message}", Snackbar.LENGTH_LONG).show()
                    resetVoiceState()
                }
            } catch (e: Exception) {
                Snackbar.make(findViewById(R.id.main), "Error: ${e.message}", Snackbar.LENGTH_LONG).show()
                resetVoiceState()
            }
        }
    }

    private fun resetVoiceState() {
        voiceState = VoiceState.IDLE
        fabVoice.setImageResource(android.R.drawable.ic_btn_speak_now)
    }

    private fun setupNavHeader() { val h = navigationView.getHeaderView(0); h.findViewById<ImageView>(R.id.imgAvatar).setImageResource(R.drawable.ic_launcher_foreground) }

    override fun onNavigationItemSelected(i: MenuItem): Boolean {
        when (i.itemId) {
            R.id.nav_sos -> {
                AlertDialog.Builder(this)
                    .setTitle("Emergency SOS")
                    .setMessage("Manually send emergency alert?")
                    .setPositiveButton("YES") { _, _ -> sendSOS() }
                    .setNegativeButton("NO", null)
                    .show()
            }
            R.id.nav_ride_history -> startActivity(Intent(this, RideHistoryActivity::class.java))
            R.id.nav_incident_log -> startActivity(Intent(this, IncidentLogActivity::class.java))
            R.id.nav_profile -> startActivity(Intent(this, ProfileActivity::class.java))
            R.id.nav_settings -> startActivity(Intent(this, SettingsActivity::class.java))
            R.id.nav_find_my_device -> startActivity(Intent(this, FindMyDeviceActivity::class.java))
            R.id.nav_issue_status -> startActivity(Intent(this, IssueStatusActivity::class.java))
            R.id.nav_report_issue -> startActivity(Intent(this, ReportIssueActivity::class.java))
            R.id.nav_gemini -> handleVoiceButtonClicked()
            R.id.nav_logout -> { ParseUser.logOut(); if (isConnected || isBleConnected) performDisconnect(); val intent = Intent(this, LoginActivity::class.java).apply { flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK }; startActivity(intent); finish() }
        }
        drawerLayout.closeDrawer(GravityCompat.START); return true
    }
}
