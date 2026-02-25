package com.example.myapplicationplp

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat

class ScreenCaptureService : Service() {

    companion object {
        const val CHANNEL_ID = "screen_capture_channel"
        const val NOTIFICATION_ID = 1001

        const val EXTRA_RESULT_CODE = "extra_result_code"
        const val EXTRA_RESULT_DATA = "extra_result_data"

        const val ACTION_START = "ScreenCaptureService.START"
        const val ACTION_STOP = "ScreenCaptureService.STOP"
    }

    private var mediaProjection: MediaProjection? = null
    private var screenTelemetry: ScreenCaptureTelemetry? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startCaptureFromIntent(intent)
            ACTION_STOP  -> stopCapture()
        }
        // 落ちても OS が再起動してくれなくてOKなら NOT_STICKY でもいい
        return START_NOT_STICKY
    }

    private fun startCaptureFromIntent(intent: Intent) {
        // すでに動いてたら一旦止める
        stopCaptureInternal()

        val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
        val resultData = intent.getParcelableExtra<Intent>(EXTRA_RESULT_DATA)
            ?: return

        // ★ 必ず startForeground してから MediaProjection を使う
        startForeground(NOTIFICATION_ID, createNotification())

        val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        // ★ ここで初めて getMediaProjection を呼ぶ（Service 内）
        mediaProjection = mpm.getMediaProjection(resultCode, resultData)



        val app = application as TelemetryApp
        val timeBase = app.timeBase
        val telemetry = app.telemetry

        val mp = mediaProjection ?: return
        screenTelemetry = ScreenCaptureTelemetry(
            context = this,
            telemetry = telemetry,
            timeBase = timeBase,
            mediaProjection = mp
        )
        Log.d("ScreenCaptureTelemetry","呼ばれてるよ。")
        screenTelemetry?.start()
    }

    private fun stopCapture() {
        stopCaptureInternal()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun stopCaptureInternal() {
        try {
            screenTelemetry?.stop()
            screenTelemetry?.release()
        } catch (_: Throwable) {
        }
        screenTelemetry = null

        try {
            mediaProjection?.stop()
        } catch (_: Throwable) {
        }
        mediaProjection = null
    }

    override fun onDestroy() {
        stopCaptureInternal()
        super.onDestroy()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val ch = NotificationChannel(
                CHANNEL_ID,
                "Screen capture",
                NotificationManager.IMPORTANCE_LOW
            )
            nm.createNotificationChannel(ch)
        }
    }

    private fun createNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("画面を録画中")
            .setContentText("構造化光グリッドのスクリーンキャプチャ")
            .setSmallIcon(android.R.drawable.presence_video_online)
            .setOngoing(true)
            .build()
    }
}
