package com.timelapse.camera

import android.app.Application
import com.timelapse.camera.util.LogBuffer

class TimeLapseApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        LogBuffer.initialize(applicationContext)
    }
}
