package com.example.testkotlinapp

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

class FindMyDeviceActivity : AppCompatActivity() {

    // ── Views ──────────────────────────────────────────────────────────────
    private lateinit var tvStatus: TextView
    private lateinit var tvDeviceId: TextView
    private lateinit var tvLatitude: TextView
    private lateinit var tvLongitude: TextView
    private lateinit var tvSpeed: TextView
    private lateinit var tvLastUpdated: TextView
    private lateinit var btnOpenMap: Button
    private lateinit var btnRefresh: Button
    private lateinit var progressBar: ProgressBar

    // ── State ──────────────────────────────────────────────────────────────
    private var currentLat = 0.0
    private var currentLng = 0.0

    // ── ThingSpeak config ──────────────────────────────────────────────────
    private val CHANNEL_ID   = "3287086"
    private val READ_API_KEY = "P6PHIQPT38Z55WZ2"
    // Fetches the latest 1 entry from the channel
    private val THINGSPEAK_URL
        get() = "https://api.thingspeak.com/channels/$CHANNEL_ID/feeds/last.json?api_key=$READ_API_KEY"

    // ── Auto refresh every 30s ─────────────────────────────────────────────
    private val handler = Handler(Looper.getMainLooper())
    private val refreshRunnable = object : Runnable {
        override fun run() {
            fetchLocation()
            handler.postDelayed(this, 30_000L)
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_find_my_device)

        supportActionBar?.title = "Find My Device"
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        bindViews()
        setupListeners()

        // Fetch immediately then every 30s
        fetchLocation()
        handler.postDelayed(refreshRunnable, 30_000L)
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacks(refreshRunnable)
    }

    override fun onSupportNavigateUp(): Boolean {
        onBackPressedDispatcher.onBackPressed()
        return true
    }

    // ── Bind views ─────────────────────────────────────────────────────────

    private fun bindViews() {
        tvStatus      = findViewById(R.id.tvStatus)
        tvDeviceId    = findViewById(R.id.tvDeviceId)
        tvLatitude    = findViewById(R.id.tvLatitude)
        tvLongitude   = findViewById(R.id.tvLongitude)
        tvSpeed       = findViewById(R.id.tvSpeed)
        tvLastUpdated = findViewById(R.id.tvLastUpdated)
        btnOpenMap    = findViewById(R.id.btnOpenMap)
        btnRefresh    = findViewById(R.id.btnRefresh)
        progressBar   = findViewById(R.id.progressBar)

        btnOpenMap.isEnabled = false
    }

    private fun setupListeners() {
        btnRefresh.setOnClickListener { fetchLocation() }
        btnOpenMap.setOnClickListener { openInGoogleMaps(currentLat, currentLng) }
    }

    // ── Fetch latest feed from ThingSpeak ──────────────────────────────────

    private fun fetchLocation() {
        showLoading(true)
        tvStatus.text = "Fetching location…"

        // Run network call on background thread
        Thread {
            try {
                val url        = URL(THINGSPEAK_URL)
                val connection = url.openConnection() as HttpURLConnection
                connection.requestMethod  = "GET"
                connection.connectTimeout = 10000
                connection.readTimeout    = 10000

                val responseCode = connection.responseCode
                if (responseCode != 200) {
                    runOnUiThread {
                        showLoading(false)
                        tvStatus.text = "⚠️ Server error: $responseCode"
                    }
                    return@Thread
                }

                val response = connection.inputStream.bufferedReader().readText()
                connection.disconnect()

                // Parse ThingSpeak JSON response
                // {
                //   "created_at": "2024-03-05T10:30:00Z",
                //   "field1": "HELMET_001",   ← deviceId
                //   "field2": "8.524100",     ← latitude
                //   "field3": "76.936600",    ← longitude
                //   "field4": "12.50"         ← speed
                // }
                val json      = JSONObject(response)
                val deviceId  = json.optString("field1", "—")
                val lat       = json.optString("field2", "0.0").toDoubleOrNull() ?: 0.0
                val lng       = json.optString("field3", "0.0").toDoubleOrNull() ?: 0.0
                val speed     = json.optString("field4", "0.0")
                val updatedAt = json.optString("created_at", "—")

                currentLat = lat
                currentLng = lng

                runOnUiThread {
                    showLoading(false)

                    if (lat == 0.0 && lng == 0.0) {
                        tvStatus.text        = "⏳ Waiting for GPS fix…"
                        btnOpenMap.isEnabled = false
                    } else {
                        tvStatus.text        = "📡 Live location ✓"
                        btnOpenMap.isEnabled = true
                    }

                    tvDeviceId.text    = "Device ID:    $deviceId"
                    tvLatitude.text    = "Latitude:     $lat°"
                    tvLongitude.text   = "Longitude:    $lng°"
                    tvSpeed.text       = "Speed:        $speed km/h"
                    tvLastUpdated.text = "Last updated: $updatedAt"
                }

            } catch (e: Exception) {
                runOnUiThread {
                    showLoading(false)
                    tvStatus.text = "⚠️ Error: ${e.message}"
                    Toast.makeText(this, e.message, Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }

    // ── Open in Google Maps ────────────────────────────────────────────────

    private fun openInGoogleMaps(lat: Double, lng: Double) {
        val uri    = Uri.parse("geo:$lat,$lng?q=$lat,$lng(Smart+Helmet+Location)")
        val intent = Intent(Intent.ACTION_VIEW, uri).apply {
            setPackage("com.google.android.apps.maps")
        }
        if (intent.resolveActivity(packageManager) != null) {
            startActivity(intent)
        } else {
            startActivity(Intent(Intent.ACTION_VIEW,
                Uri.parse("https://maps.google.com/?q=$lat,$lng")))
        }
    }

    // ── Loading ────────────────────────────────────────────────────────────

    private fun showLoading(show: Boolean) {
        progressBar.visibility = if (show) View.VISIBLE else View.GONE
        btnRefresh.isEnabled   = !show
    }
}