package com.example.myapplicationplp

import android.app.Application

/**
 * アプリ共通の TelemetryLogger / TimeBase を保持する Application。
 *
 * AndroidManifest.xml に
 *   android:name=".TelemetryApp"
 * を指定すること。
 */
class TelemetryApp : Application() {

    lateinit var timeBase: TimeBase
        private set

    lateinit var telemetry: TelemetryLogger
        private set

    override fun onCreate() {
        super.onCreate()

        timeBase = TimeBase()
        telemetry = TelemetryLogger(
            context = this,
            timeBase = timeBase
        )
    }

    override fun onTerminate() {
        super.onTerminate()
        telemetry.close()
    }
}
