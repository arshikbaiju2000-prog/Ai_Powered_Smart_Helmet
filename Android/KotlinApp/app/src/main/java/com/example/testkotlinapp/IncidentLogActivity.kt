package com.example.testkotlinapp

import android.os.Bundle
import android.widget.Button
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView

class IncidentLogActivity : AppCompatActivity() {

    private lateinit var recyclerView: RecyclerView
    private lateinit var backButton: Button
    private lateinit var imageUpdater: ImageUpdater


    override fun onStart() {
        super.onStart()
        MainActivity.isIncidentLogOpen = true
    }

    override fun onStop() {
        super.onStop()
        MainActivity.isIncidentLogOpen = false
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_incident_log)

        recyclerView = findViewById(R.id.recyclerViewIncidentLog)
        recyclerView.layoutManager = GridLayoutManager(this, 3)


        imageUpdater = ImageUpdater(this, recyclerView)
        imageUpdater.loadImages()
    }
}
