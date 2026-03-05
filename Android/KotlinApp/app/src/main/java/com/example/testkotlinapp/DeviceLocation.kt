package com.example.testkotlinapp.models

import com.parse.ParseClassName
import com.parse.ParseGeoPoint
import com.parse.ParseObject

@ParseClassName("DeviceLocation")
class DeviceLocation : ParseObject() {

    companion object {
        const val KEY_DEVICE_ID = "deviceId"
        const val KEY_LATITUDE  = "latitude"
        const val KEY_LONGITUDE = "longitude"
        const val KEY_LOCATION  = "location"
        const val KEY_SPEED     = "speed"
    }

    var deviceId: String?
        get() = getString(KEY_DEVICE_ID)
        set(value) { value?.let { put(KEY_DEVICE_ID, it) } }

    var latitude: Double
        get() = getDouble(KEY_LATITUDE)
        set(value) { put(KEY_LATITUDE, value) }

    var longitude: Double
        get() = getDouble(KEY_LONGITUDE)
        set(value) { put(KEY_LONGITUDE, value) }

    var speed: Double
        get() = getDouble(KEY_SPEED)
        set(value) { put(KEY_SPEED, value) }

    fun setLocation(lat: Double, lng: Double) {
        put(KEY_LOCATION, ParseGeoPoint(lat, lng))
        put(KEY_LATITUDE, lat)
        put(KEY_LONGITUDE, lng)
    }
}