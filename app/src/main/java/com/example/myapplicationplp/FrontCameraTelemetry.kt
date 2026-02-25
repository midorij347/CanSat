package com.example.myapplicationplp

import android.annotation.SuppressLint
import android.content.Context
import android.net.Uri
import android.util.Log
import android.view.Surface
import android.view.ViewGroup
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.FileOutputOptions
import androidx.camera.video.PendingRecording
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.Recorder
import androidx.camera.video.VideoRecordEvent
import androidx.core.content.ContextCompat
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import java.util.concurrent.Executor
import androidx.camera.view.PreviewView


class FrontCameraTelemetry(
    private val context: Context,
    private val timeBase: TimeBase,
    private val telemetry: TelemetryLogger
) {
    private var cameraProvider: ProcessCameraProvider? = null
    private var videoCapture: VideoCapture<Recorder>? = null
    private var recording: Recording? = null

    private val executor: Executor by lazy {
        ContextCompat.getMainExecutor(context)
    }

    private var startNs: Long? = null
    private var currentFilename: String? = null

    // -------------------------------
    // ① 録画開始
    // -------------------------------
    @SuppressLint("MissingPermission")
    fun startRecording() {
        Log.d("FrontCam", "startRecording()")

        val providerFuture = ProcessCameraProvider.getInstance(context)
        providerFuture.addListener({
            cameraProvider = providerFuture.get()

            val recorder = Recorder.Builder().build()
            videoCapture = VideoCapture.withOutput(recorder)

            val selector = CameraSelector.DEFAULT_FRONT_CAMERA

            try {
                cameraProvider!!.unbindAll()
                cameraProvider!!.bindToLifecycle(
                    context as androidx.lifecycle.LifecycleOwner,
                    selector,
                    videoCapture   // ← preview を渡さず、videoCapture だけ
                )
            } catch (e: Exception) {
                Log.e("FrontCam", "Camera binding failed", e)
                return@addListener
            }


            // ファイル名は時刻ベース
            // 例：startRecording() の中

// ファイル名
            val filename = "front_${System.currentTimeMillis()}.mp4"

// ★ Movies/Spotlight 配下に保存する
            val moviesDir = context.getExternalFilesDir(android.os.Environment.DIRECTORY_MOVIES)
            val spotlightDir = java.io.File(moviesDir, "Spotlight").apply { mkdirs() }

            val file = java.io.File(spotlightDir, filename)

// ここで outputOptions を作る
            val outputOptions = FileOutputOptions.Builder(file).build()

// 後で Worker に渡すために、どこかに覚えておく
            startNs = timeBase.nowMonoNs()
// もし currentFilename みたいな変数があれば：
            currentFilename = filename

// Telemetry も合わせておく
            telemetry.logVideoStart(
                tNs = startNs!!,
                role = "front_cam",
                uri = file.absolutePath      // ログ上でも確認しやすい
            )


            val pending: PendingRecording = videoCapture!!.output
                .prepareRecording(context, outputOptions)

            recording = pending.start(executor) { event ->
                when (event) {

                    is VideoRecordEvent.Finalize -> {
                        onFinalize(event.outputResults.outputUri)
                    }
                }
            }

        }, executor)
    }

    // -------------------------------
    // ② 録画停止（本質：停止依頼だけ）
    // -------------------------------
    fun stopRecording() {
        Log.d("FrontCam", "stopRecording()")
        recording?.stop()
        recording = null
    }

    // -------------------------------
    // ③ Finalize（動画が完成 → ログ + アップロード依頼）
    // -------------------------------
    private fun onFinalize(uri: Uri) {
        Log.d("FrontCam", "Finalize: $uri")

        val tNs = startNs ?: return
        val filename = currentFilename ?: run {
            Log.e("FrontCam", "onFinalize: currentFilename is null")
            return
        }
        // --- Telemetry log ---
        telemetry.logVideoEnd(
            tNs = tNs,
            role = "front_cam",
            uri = uri.toString()
        )

        // --- WorkManager へアップロード依頼 ---
        val work = OneTimeWorkRequestBuilder<VideoUploadWorker>()
            .setInputData(
                workDataOf(
                    VideoUploadWorker.KEY_URI to uri.toString(),
                    VideoUploadWorker.KEY_ROLE to "front_cam",
                    VideoUploadWorker.KEY_FILENAME to filename
                )
            )
            .build()

        WorkManager.getInstance(context)
            .enqueue(work)
    }

}
