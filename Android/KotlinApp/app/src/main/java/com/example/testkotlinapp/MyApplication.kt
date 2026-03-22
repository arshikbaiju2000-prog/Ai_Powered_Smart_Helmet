package com.example.testkotlinapp

import android.app.Application
import android.content.Context
import androidx.appcompat.app.AppCompatDelegate
import com.parse.Parse
import com.parse.ParseACL
import com.parse.ParseObject

class MyApplication : Application() {

    override fun onCreate() {
        super.onCreate()

        // Apply saved theme preference globally before any activity starts
        val appPrefs = getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
        val themeMode = appPrefs.getInt("theme_mode", AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
        AppCompatDelegate.setDefaultNightMode(themeMode)

        // Register Parse subclasses BEFORE initializing
        // Both MyApplication and DeviceLocation are in package com.example.testkotlinapp
        ParseObject.registerSubclass(DeviceLocation::class.java)

        Parse.initialize(
            Parse.Configuration.Builder(this)
                .applicationId("5Kmum8cjj4tbZprQvjzRK2MTcHniyk16IcC0pEsm")
                .clientKey("aRgD5vDG2ccnX9I31gEEt8nAL4WVLEmnXF0e9UPY")
                .server("https://parseapi.back4app.com/")
                .build()
        )

        val defaultACL = ParseACL().apply {
            setPublicReadAccess(true)
            setPublicWriteAccess(true)
        }
        ParseACL.setDefaultACL(defaultACL, true)
    }
}