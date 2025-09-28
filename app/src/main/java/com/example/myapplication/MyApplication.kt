package com.example.myapplication

import android.app.Application
import dagger.hilt.android.HiltAndroidApp

@HiltAndroidApp
class MyApplication : Application() {
    // You can add Application-level setup here if needed
    override fun onCreate() {
        super.onCreate()
        // Example: Initialize logging library, etc.
    }
}
