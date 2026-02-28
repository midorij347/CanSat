package com.example.myapplicationplp
import kotlin.math.*

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent

import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
// Compose 側
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background

import androidx.compose.foundation.layout.fillMaxSize

import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember

import androidx.compose.ui.Modifier

import androidx.compose.ui.geometry.Offset

import androidx.compose.ui.graphics.Color

import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch


import androidx.compose.ui.graphics.drawscope.DrawScope

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.drawscope.rotateRad
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat

import android.content.ContentValues
import android.content.Intent
import android.media.projection.MediaProjection
import android.provider.MediaStore
import android.util.Log

import androidx.camera.core.CameraSelector
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.Recorder
import androidx.camera.video.VideoCapture
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.MediaStoreOutputOptions
import androidx.camera.video.Recording
import androidx.camera.video.VideoRecordEvent
import androidx.camera.video.FallbackStrategy
import androidx.core.content.getSystemService
import androidx.lifecycle.lifecycleScope
import android.media.projection.MediaProjectionManager
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import android.provider.OpenableColumns


class SpotlightActivity : ComponentActivity() {

    private lateinit var imuManager: ImuTelemetryManager
    private lateinit var espManager: EspMotorTelemetryManager
    private lateinit var motorSender: MotorCommandSender
    private var dutyTestJob: Job? = null
    private lateinit var screenLogger: ScreenPatternLogger
    private lateinit var frontCamTelemetry: FrontCameraTelemetry
    private var screenCaptureTelemetry: ScreenCaptureTelemetry? = null
    private var mediaProjection: MediaProjection? = null
    private lateinit var usbRepo: UsbCdcRepo

    private val permissions = arrayOf(
        android.Manifest.permission.CAMERA,
        android.Manifest.permission.RECORD_AUDIO
    )

    private val launcher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        // 全部許可されたら開始
    }


    private val askScreenCapture =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == RESULT_OK && result.data != null) {

                // ★ Activity では MediaProjection を扱わない
                val intent = Intent(this, ScreenCaptureService::class.java).apply {
                    action = ScreenCaptureService.ACTION_START
                    putExtra(ScreenCaptureService.EXTRA_RESULT_CODE, result.resultCode)
                    putExtra(ScreenCaptureService.EXTRA_RESULT_DATA, result.data)
                }
                ContextCompat.startForegroundService(this, intent)

            } else {
                Log.d("ForeGround Error","error")
            }
        }



    private val askCamera = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            frontCamTelemetry.startRecording()
            startArSession()
            //startFrontCameraRecording()
        } else {
            finish()
        }
    }

    private var videoCapture: VideoCapture<Recorder>? = null
    private var recording: Recording? = null
    private val REQUIRED_PERMISSIONS = arrayOf(
        android.Manifest.permission.CAMERA,
        android.Manifest.permission.RECORD_AUDIO
    )

    private var session: com.google.ar.core.Session? = null


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val app = application as TelemetryApp
        val telemetry = app.telemetry
        val timeBase = app.timeBase
        val sensorManager = getSystemService(SENSOR_SERVICE) as SensorManager

        var usbRepo = UsbCdcRepo(context = this)

        val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        askScreenCapture.launch(mpm.createScreenCaptureIntent())

        // ★ ここで既に挿さっているデバイスを探して接続を開始
        val ok = usbRepo.findAndConnect()
        Log.d("USB", "findAndConnect -> $ok")

        imuManager = ImuTelemetryManager(sensorManager,telemetry,timeBase)
        espManager = EspMotorTelemetryManager(usbRepo, telemetry, timeBase,
            scope = lifecycleScope
        )
        motorSender = MotorCommandSender(usbRepo, telemetry, timeBase)
        screenLogger = ScreenPatternLogger(telemetry, timeBase)
        frontCamTelemetry = FrontCameraTelemetry(this,  timeBase,telemetry)

        launcher.launch(permissions)
        // 画面を常時点灯＆最大輝度に
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.attributes = window.attributes.apply {
            screenBrightness = 1.0f
        }

        // システムバーを隠す（戻すときはonStop等で解除）
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        setContent {

            val yawRad = rememberYawRadFromSensors()

            NorthFixedGrid(yawRad = yawRad)

        }
        imuManager.start()
        espManager.start()
        @SuppressLint("MissingPermission")
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            == PackageManager.PERMISSION_GRANTED) {
            frontCamTelemetry.startRecording()
            startArSession()
            //startFrontCameraRecording()
        } else {
            askCamera.launch(Manifest.permission.CAMERA)
        }

    }
    override fun onStart() {
        super.onStart()

        // デューティ比のテスト用：-1.0〜+1.0 を往復させる
        dutyTestJob = lifecycleScope.launch(Dispatchers.IO) {
            var duty = -1.0f
            var dir = 0.05f   // 1ステップの増減量（調整してOK）

            while (isActive) {
                // 左右同じデューティで回す（必要なら左右別でもOK）
                motorSender.sendMotorDuty(duty, duty)

                duty += dir
                if (duty > 1.0f) {
                    duty = 1.0f
                    dir = -dir      // 方向反転
                } else if (duty < -1.0f) {
                    duty = -1.0f
                    dir = -dir
                }/*
                var random = Math.random()
                random = random * 2 -1.0
                duty = random.toFloat()
                delay(200L)         // 200ms ごとに更新（ゆっくりめ）
                */
            }
        }
    }

    override fun onStop() {
        // テスト用ジョブを止める
        dutyTestJob?.cancel()
        dutyTestJob = null

        // 念のため停止コマンドを送る
        motorSender.sendMotorDuty(0f, 0f)

        // ★ 録画をここで止める
        frontCamTelemetry.stopRecording()
        screenCaptureTelemetry?.stop()
        try {
            recording?.stop()   // ← CameraXの録画も止めるならここで
        } catch (e: Exception) {
            e.printStackTrace()
        }
        recording = null

        super.onStop()
    }


    override fun onResume() {
        super.onResume()
        session?.resume()
    }

    override fun onPause() {
        super.onPause()
        session?.pause()
    }
    override fun onDestroy() {
        val app = application as TelemetryApp
        app.telemetry.flushToCloud()

        screenCaptureTelemetry?.stop()
        screenCaptureTelemetry?.release()
        screenCaptureTelemetry = null
        mediaProjection = null

        imuManager.stop()
        espManager.stop()
        frontCamTelemetry.stopRecording()

        try {
            recording?.stop()
        } catch (e: Exception) {
            e.printStackTrace()
        }
        recording = null

        super.onDestroy()
        val stopIntent = Intent(this, ScreenCaptureService::class.java).apply {
            action = ScreenCaptureService.ACTION_STOP
        }
        startService(stopIntent)
    }




    private fun startArSession() {
        try {
            val status = com.google.ar.core.ArCoreApk.getInstance()
                .requestInstall(this, /*userRequestedInstall=*/true)
            if (status == com.google.ar.core.ArCoreApk.InstallStatus.INSTALLED) {
                if (session == null) {
                    val s = com.google.ar.core.Session(this)

                    val config = com.google.ar.core.Config(s).apply {
                        updateMode = com.google.ar.core.Config.UpdateMode.LATEST_CAMERA_IMAGE
                    }
                    s.configure(config)
                    s.resume()       // ★ ここ重要

                    session = s
                } else {
                    session?.resume()
                }
            }
        } catch (e: Exception) {
            // 非対応端末などの例外ハンドリング
            e.printStackTrace()
        }
    }

    private fun decomposeRwc_ZYX(Rwc: FloatArray): Triple<Float,Float,Float> {
        val r00 = Rwc[0]; val r01 = Rwc[1]; val r02 = Rwc[2]
        val r10 = Rwc[3]; val r11 = Rwc[4]; val r12 = Rwc[5]
        val r20 = Rwc[6]; val r21 = Rwc[7]; val r22 = Rwc[8]
        val pitch = asin(-r20)
        val roll  = atan2(r21, r22)
        val yaw   = atan2(r10, r00)
        return Triple(yaw, pitch, roll)
    }

    private fun composeRwc_ZYX(yaw: Float, pitch: Float, roll: Float): FloatArray {
        val cy = cos(yaw);   val sy = sin(yaw)
        val cp = cos(pitch); val sp = sin(pitch)
        val cr = cos(roll);  val sr = sin(roll)
        // Rz * Ry * Rx
        return floatArrayOf(
            cy*cp,              cy*sp*sr - sy*cr,   cy*sp*cr + sy*sr,
            sy*cp,              sy*sp*sr + cy*cr,   sy*sp*cr - cy*sr,
            -sp,                cp*sr,              cp*cr
        )
    }

    // --- メイン：yaw を MAP で置換して Rcw を作る ---
    fun fetchPoseAndIntrinsicsCorrected(
        session: com.google.ar.core.Session,
        yawEstRad: Float,                         // MAP 推定の「北に対する yaw」
    ): Triple<FloatArray, FloatArray, Intrinsics>? {
        return null
    }
    @Composable
    fun ProjectedGrid(
        intr: Intrinsics,
        Rcw: FloatArray,     // 3x3 world->camera (row-major)
        tcw: FloatArray,     // 3x1
        gridStepMeters: Float = 0.5f, // 世界平面上の格子間隔 [m]
        extentMeters: Float = 10f,    // 表示半径（±）
        strokePx: Float = 2f
    ) {
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
        ) {
            val H = buildHomography(intr, Rcw, tcw)

            // X=const ライン群（北南方向の直線）
            drawGridFamily(
                H, fixIsX = true,
                step = gridStepMeters,
                extent = extentMeters,
                strokePx = strokePx * 1.5f,
                color = Color(1f,1f,1f,0.9f)
            )


            // Y=const ライン群（東西方向の直線）
            drawGridFamily(
                H, fixIsX = false,
                step = gridStepMeters,
                extent = extentMeters,
                strokePx = strokePx * 1.5f,
                color = Color(1f,1f,1f,0.9f)
            )

            // 原点十字（世界の原点：任意）
            drawGridAxis(H, axisLen = 1.0f)

            //test


        }
    }
    private fun DrawScope.drawGridFamily(
        H: FloatArray,
        fixIsX: Boolean,
        step: Float,
        extent: Float,
        strokePx: Float,
        color: Color
    ) {
        // 直線を画面に描くには、十分広い区間の2点を投影して結ぶ
        val tMin = -extent
        val tMax = +extent
        var c = (-extent / step).toInt() * step
        while (c <= extent + 1e-6f) {
            val p0 = if (fixIsX) projectXY(H, c, tMin) else projectXY(H, tMin, c)
            val p1 = if (fixIsX) projectXY(H, c, tMax) else projectXY(H, tMax, c)
            if (p0 != null && p1 != null) {
                drawLine(
                    color = color,
                    start = Offset(p0.first, p0.second),
                    end   = Offset(p1.first, p1.second),
                    strokeWidth = strokePx
                )
            }
            c += step
        }
    }
    private fun buildHomography(
        K: Intrinsics,
        Rcw: FloatArray,  // 3x3 row-major (world->camera)
        tcw: FloatArray   // 3x1
    ): FloatArray {       // 3x3 row-major
        // r1 = Rcw col0, r2 = col1
        val r11 = Rcw[0]; val r21 = Rcw[3]; val r31 = Rcw[6]
        val r12 = Rcw[1]; val r22 = Rcw[4]; val r32 = Rcw[7]
        val t1  = tcw[0]; val t2  = tcw[1]; val t3  = tcw[2]

        // B = [r1 r2 t]
        val b00=r11; val b01=r12; val b02=t1
        val b10=r21; val b11=r22; val b12=t2
        val b20=r31; val b21=r32; val b22=t3

        // H = K * B
        val fx=K.fx; val fy=K.fy; val cx=K.cx; val cy=K.cy
        val H = FloatArray(9)
        H[0] = fx*b00 + cx*b20; H[1] = fx*b01 + cx*b21; H[2] = fx*b02 + cx*b22
        H[3] = fy*b10 + cy*b20; H[4] = fy*b11 + cy*b21; H[5] = fy*b12 + cy*b22
        H[6] =      b20;        H[7] =      b21;        H[8] =      b22
        return H
    }
    private fun uploadVideoToS3(localUri: Uri, keyPrefix: String) {
        // 例: s3Uploader は TelemetryApp に持たせておく想定
        val app = application as TelemetryApp

        lifecycleScope.launch(Dispatchers.IO) {
            runCatching {
                val resolver = contentResolver

                // サイズ取得（任意だけどメタデータで付けたい場合）
                var size: Long? = null
                resolver.query(localUri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { c ->
                    val idx = c.getColumnIndex(OpenableColumns.SIZE)
                    if (idx >= 0 && c.moveToFirst()) {
                        size = c.getLong(idx)
                    }
                }

                resolver.openInputStream(localUri).use { input ->
                    if (input == null) {
                        Log.e("Spotlight", "openInputStream returned null for $localUri")
                        return@launch
                    }

                    // S3 上のキーを決める（例: front/20251203_123456.mp4）
                    val fileName = "${System.currentTimeMillis()}.mp4"
                    val key = "$keyPrefix/$fileName"

                    Log.d("Spotlight", "Start upload to S3: key=$key size=$size")

                    // ★ ここをあなたの S3 アップロード実装に差し替える
                    // 例: s3Uploader.uploadStream(bucketName, key, input, size)


                    Log.d("Spotlight", "Upload success: key=$key")
                }
            }.onFailure { e ->
                Log.e("Spotlight", "Upload to S3 failed for $localUri", e)
            }
        }
    }
    private fun startFrontCameraRecording() {
        if (recording != null) {
            // すでに録画中なら何もしない
            return
        }
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)

        cameraProviderFuture.addListener({
            val cameraProvider = try {
                cameraProviderFuture.get()
            } catch (e: Exception) {
                e.printStackTrace()
                return@addListener
            }

            // 録画用 Recorder と VideoCapture を作成
            val preferredQuality = Quality.FHD

            val recorder = Recorder.Builder()
                .setQualitySelector(
                    QualitySelector.from(
                        preferredQuality,
                        FallbackStrategy.higherQualityOrLowerThan(preferredQuality)
                    )
                )
                .build()



            val videoCapture = VideoCapture.withOutput(recorder)
            this.videoCapture = videoCapture

            // インカメラ（フロント）を指定
            val cameraSelector = CameraSelector.Builder()
                .requireLensFacing(CameraSelector.LENS_FACING_FRONT)
                .build()

            try {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(
                    this,       // ComponentActivity は LifecycleOwner
                    cameraSelector,
                    videoCapture
                )
            } catch (e: Exception) {
                e.printStackTrace()
                return@addListener
            }

            // 保存先（MediaStore / Movies/Spotlight 配下）
            val name = "front_${System.currentTimeMillis()}"
            val contentValues = ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, name)
                put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                put(
                    MediaStore.Video.Media.RELATIVE_PATH,
                    "Movies/Spotlight"
                )
            }

            val outputOptions = MediaStoreOutputOptions.Builder(
                contentResolver,
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI
            )
                .setContentValues(contentValues)
                .build()

            val vc = this.videoCapture ?: return@addListener

            // 音声はいらない前提 → withAudioEnabled() は呼ばない
            val rec = vc.output
                .prepareRecording(this, outputOptions)
                .start(ContextCompat.getMainExecutor(this)) { event ->
                    when (event) {
                        is VideoRecordEvent.Finalize -> {
                            val uri = event.outputResults.outputUri
                            if (!event.hasError()) {
                                Log.d("Spotlight", "Video saved: $uri")
                                // ★ ここで S3 にアップロード
                                uploadVideoToS3(uri, keyPrefix = "front")
                            } else {
                                Log.e("Spotlight", "Recording error: ${event.error}")
                            }
                        }
                        else -> {
                            // OnStart / OnStatus は必要に応じてログ
                        }
                    }
                }


            recording = rec

        }, ContextCompat.getMainExecutor(this))
    }

    // u = H [X,Y,1]^T  →  画面座標 (px, py)
    private fun projectXY(H: FloatArray, X: Float, Y: Float): Pair<Float,Float>? {
        val u0 = H[0]*X + H[1]*Y + H[2]
        val u1 = H[3]*X + H[4]*Y + H[5]
        val u2 = H[6]*X + H[7]*Y + H[8]
        if (abs(u2) < 1e-6f) return null
        return Pair(u0/u2, u1/u2)
    }
    private fun DrawScope.drawGridAxis(H: FloatArray, axisLen: Float) {
        // X軸（北：赤）
        val x0 = projectXY(H, 0f, 0f)
        val x1 = projectXY(H, axisLen, 0f)
        if (x0 != null && x1 != null) {
            drawLine(Color.Red, Offset(x0.first, x0.second), Offset(x1.first, x1.second), strokeWidth = 3f)
        }
        // Y軸（東：緑）
        val y1 = projectXY(H, 0f, axisLen)
        if (x0 != null && y1 != null) {
            drawLine(Color.Green, Offset(x0.first, x0.second), Offset(y1.first, y1.second), strokeWidth = 3f)
        }
    }
    @Composable
    fun NorthFixedGrid(
        yawRad: Float,              // 推定された「北に対する端末の yaw」
        gridStepPx: Float = 80f,
        lineWidthPx: Float = 2f
    ) {
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
        ) {
            val w = size.width
            val h = size.height
            val center = Offset(w / 2f, h / 2f)

            // 「グリッドは常に世界の北を向く」ように、キャンバスを -yaw 回転
            withTransform({
                rotateRad(-yawRad, pivot = center)
            }) {
                // 画面中心を原点にして格子を引く
                var x = center.x
                while (x < w) {
                    drawLine(
                        Color(1f, 1f, 1f, 0.9f),   // アルファを 0.2 → 0.9 に
                        Offset(x, 0f),
                        Offset(x, h),
                        strokeWidth = lineWidthPx * 1.5f  // ついでに太さも少しだけUP
                    )

                    x += gridStepPx
                }
                x = center.x - gridStepPx
                while (x > 0f) {
                    drawLine(
                        Color(1f, 1f, 1f, 0.9f),
                        Offset(x, 0f),
                        Offset(x, h),
                        strokeWidth = lineWidthPx * 1.5f
                    )
                    x -= gridStepPx
                }

                var y = center.y
                while (y < h) {
                    drawLine(
                        Color(1f, 1f, 1f, 0.9f),
                        Offset(0f, y),
                        Offset(w, y),
                        strokeWidth = lineWidthPx * 1.5f
                    )
                    y += gridStepPx
                }
                y = center.y - gridStepPx
                while (y > 0f) {
                    drawLine(
                        Color(1f, 1f, 1f, 0.9f),
                        Offset(0f, y),
                        Offset(w, y),
                        strokeWidth = lineWidthPx * 1.5f
                    )
                    y -= gridStepPx
                }

                // 中心十字（向きの確認用）
                drawLine(
                    Color.Cyan,
                    Offset(center.x - 20f, center.y),
                    Offset(center.x + 20f, center.y),
                    strokeWidth = 3f
                )
                drawLine(
                    Color.Cyan,
                    Offset(center.x, center.y - 20f),
                    Offset(center.x, center.y + 20f),
                    strokeWidth = 3f
                )
            }
        }
    }

    @Composable
    fun rememberYawRadFromSensors(): Float {
        val context = LocalContext.current
        val sensorManager = remember {
            context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        }

        val yawState = remember { mutableStateOf(0f) }
        val gravity = remember { FloatArray(3) }
        val geomag  = remember { FloatArray(3) }
        val R = remember { FloatArray(9) }
        val I = remember { FloatArray(9) }

        DisposableEffect(Unit) {
            val listener = object : SensorEventListener {
                override fun onSensorChanged(e: SensorEvent) {
                    when (e.sensor.type) {
                        Sensor.TYPE_ACCELEROMETER ->
                            System.arraycopy(e.values, 0, gravity, 0, 3)
                        Sensor.TYPE_MAGNETIC_FIELD ->
                            System.arraycopy(e.values, 0, geomag, 0, 3)
                    }
                    if (SensorManager.getRotationMatrix(R, I, gravity, geomag)) {
                        val orient = FloatArray(3)
                        SensorManager.getOrientation(R, orient)
                        // orient[0] = azimuth (北基準, -π..π)
                        yawState.value = orient[0]
                    }
                }
                override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
            }

            val acc = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
            val mag = sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)
            if (acc != null) sensorManager.registerListener(
                listener, acc, SensorManager.SENSOR_DELAY_GAME
            )
            if (mag != null) sensorManager.registerListener(
                listener, mag, SensorManager.SENSOR_DELAY_GAME
            )

            onDispose {
                sensorManager.unregisterListener(listener)
            }
        }

        return yawState.value
    }

}
