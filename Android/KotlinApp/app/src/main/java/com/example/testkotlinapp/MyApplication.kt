package com.example.testkotlinapp

import android.app.Application
import com.example.testkotlinapp.models.DeviceLocation
import com.parse.Parse
import com.parse.ParseACL
import com.parse.ParseObject

class MyApplication : Application() {

    override fun onCreate() {
        super.onCreate()

        // Register Parse subclasses BEFORE initializing
        ParseObject.registerSubclass(DeviceLocation::class.java)

        Parse.initialize(
            Parse.Configuration.Builder(this)
                .applicationId("5Kmum8cjj4tbZprQvjzRK2MTcHniyk16IcC0pEsm")    // ← replace
                .clientKey("aRgD5vDG2ccnX9I31gEEt8nAL4WVLEmnXF0e9UPY")     // ← replace
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