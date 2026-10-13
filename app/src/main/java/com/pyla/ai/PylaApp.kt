package com.pyla.ai

import android.app.Application
import android.content.Context
import com.pyla.ai.engine.PylaLog as Log
import com.pyla.ai.diagnostics.DiagnosticLog
import org.opencv.android.OpenCVLoader

class PylaApp : Application() {

    override fun onCreate() {
        super.onCreate()
        instance = this
        DiagnosticLog.event("app.start", "process_start")
        Log.i("Pyla", "=== PylaAI starting ===")
        try {
            val ok = OpenCVLoader.initLocal()
            Log.i("Pyla", "OpenCV initLocal: $ok")
        } catch (t: Throwable) {
            Log.e(TAG, "OpenCV init failed", t)
        }
    }

    companion object {
        private const val TAG = "PylaApp"
        @Volatile private var instance: PylaApp? = null
        fun get(): PylaApp = instance!!
        fun ctx(): Context = instance!!.applicationContext
    }
}