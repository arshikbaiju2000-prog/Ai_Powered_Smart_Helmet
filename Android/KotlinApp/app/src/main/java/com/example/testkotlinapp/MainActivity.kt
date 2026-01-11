package com.example.testkotlinapp

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.ImageView
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
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

    private val espIp = "192.168.4.1"   // Replace with your ESP32 IP
    private val espPort = 12345          // Replace with your ESP32 port

    companion object {
        @Volatile
        var isIncidentLogOpen = false
    }


    private fun saveBitmapToFile(context: Context, bitmap: Bitmap, filename: String): File {
        val file = File(context.filesDir, filename)
        FileOutputStream(file).use { out ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, 90, out)
        }
        return file
    }

//    private fun listSavedImages(context: Context): List<File> {
//        return context.filesDir.listFiles { file -> file.extension in listOf("jpg", "jpeg") }?.toList() ?: emptyList()
//    }

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

//    private fun loadAndShowIncidentImages() {
//        val savedImages = listSavedImages(this)
//        synchronized(receivedImages) {
//            receivedImages.clear()
//            receivedImages.addAll(savedImages)
//        }
//
//        runOnUiThread {
//            recyclerView.adapter = ImagesAdapter(receivedImages) { imageFile ->
//                val intent = android.content.Intent(this@MainActivity, ImageViewerActivity::class.java).apply {
//                    putExtra("imagePath", imageFile.absolutePath)
//                }
//                startActivity(intent)
//            }
//        }
//    }
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_main)

        recyclerView = findViewById(R.id.recyclerView)
        recyclerView.layoutManager = GridLayoutManager(this, 3)

        val imageView: ImageView = findViewById(R.id.imageView)
        imageView.setImageResource(R.drawable.helemt)

        val connectButton: Button = findViewById(R.id.button)
        incidentLogButton = findViewById(R.id.button3)

        // Add views to toggle visibility with RecyclerView
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
//            if (recyclerView.isVisible) {
//                recyclerView.visibility = View.GONE
//                otherViews.forEach { it.visibility = View.VISIBLE }
//            } else {
//                recyclerView.visibility = View.VISIBLE
//                otherViews.forEach { it.visibility = View.GONE }
//                imageUpdater.loadImages()
//            }
            val intent = android.content.Intent(this, IncidentLogActivity::class.java)
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
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }
    }
}
