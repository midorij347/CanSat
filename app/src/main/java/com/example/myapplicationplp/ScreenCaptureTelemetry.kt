package com.example.myapplicationplp

import android.content.ContentValues
import android.content.ContentValues.TAG
import android.content.Context
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import java.io.File
import android.os.Handler
import android.os.Looper


/**
 * スクリーン録画を行い、その開始・完了を TelemetryLogger に送るクラス。
 *
 * - MediaProjection: Activity 側で権限取得済みのものを渡す
 * - 保存先: Movies/Spotlight/screen_*.mp4 （MediaStore）
 * - テレメトリ:
 *   - 開始: type="video_start", role="screen_grid", uri="pending:screen_<timestamp>"
 *   - 完了: type="video_start", role="screen_grid_done", uri="<content://...>"
 */


class ScreenCaptureTelemetry(
    private val context: Context,
    private val telemetry: TelemetryLogger,
    private val timeBase: TimeBase,
    private val mediaProjection: MediaProjection
) {

    private var recorder: MediaRecorder? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var currentUri: Uri? = null
    private var isRecording: Boolean = false
    private var fileName: String? = null
    private var startNs: Long? = null
    private var projectionCallback: MediaProjection.Callback? = null

    fun start() {
        if (isRecording) {
            Log.d("ScreenCaptureTelemetry", "Already recording, ignore start().")
            return
        }

        val metrics = context.resources.displayMetrics
        val width = metrics.widthPixels
        val height = metrics.heightPixels
        val densityDpi = metrics.densityDpi

        // ★ ファイル名を決めて MediaStore に空ファイルを作る
        val name = "screen_${System.currentTimeMillis()}.mp4"
        fileName = name

        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, name)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/Spotlight")
        }

        val resolver = context.contentResolver
        val uri = resolver.insert(
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
            values
        ) ?: run {
            Log.e("ScreenCaptureTelemetry", "Failed to insert MediaStore row")
            return
        }
        currentUri = uri

        val pfd = resolver.openFileDescriptor(uri, "w") ?: run {
            Log.e("ScreenCaptureTelemetry", "Failed to open PFD for uri=$uri")
            return
        }

        // ★ フロントカメラと同じ場所に保存する
        val moviesDir = context.getExternalFilesDir(Environment.DIRECTORY_MOVIES)
        val spotlightDir = File(moviesDir, "Spotlight").also { it.mkdirs() }
        fileName = name
        val outFile = File(spotlightDir, name)

        // ★ テレメトリ: 開始ログ
        val startNsLocal = timeBase.nowMonoNs()
        startNs = startNsLocal
        telemetry.logVideoStart(
            tNs = startNsLocal,
            role = "screen_grid",
            //uri = "pending:$name"
            uri = outFile.absolutePath
        )

        val rec = MediaRecorder()
        recorder = rec

        try {
            rec.setVideoSource(MediaRecorder.VideoSource.SURFACE)
            rec.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            rec.setVideoEncoder(MediaRecorder.VideoEncoder.H264)
            rec.setVideoFrameRate(30)
            rec.setVideoSize(width, height)
            rec.setVideoEncodingBitRate(8_000_000)
            rec.setOutputFile(pfd.fileDescriptor)
            //rec.setOutputFile(outFile.absolutePath)
            rec.prepare()

            val surface = rec.surface

            if (projectionCallback == null) {
                projectionCallback = object : MediaProjection.Callback() {
                    override fun onStop() {
                        Log.d(TAG, "MediaProjection onStop() – stop capture")
                        // OS側から投げられた stop に追従して録画を止める
                        stop()
                    }
                }
                mediaProjection.registerCallback(
                    projectionCallback!!,
                    Handler(Looper.getMainLooper())
                )
            }

            virtualDisplay = mediaProjection.createVirtualDisplay(
                "SpotlightScreenCapture",
                width,
                height,
                densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                surface,
                null,
                null
            )

            rec.start()
            isRecording = true
            Log.d("ScreenCaptureTelemetry", "Screen recording started: uri=$uri")

        } catch (e: Exception) {
            Log.e("ScreenCaptureTelemetry", "start() failed", e)
            try {
                rec.reset()
                rec.release()
            } catch (_: Exception) {}
            recorder = null
            virtualDisplay?.release()
            virtualDisplay = null
            isRecording = false
        }
    }

    fun stop() {
        if (!isRecording) return

        val rec = recorder
        val vd = virtualDisplay
        val uri = currentUri
        val startNsLocal = startNs

        isRecording = false
        recorder = null
        virtualDisplay = null
        currentUri = null
        startNs = null

        try {
            rec?.stop()
        } catch (e: Exception) {
            Log.e("ScreenCaptureTelemetry", "stop() error in MediaRecorder.stop()", e)
        } finally {
            try {
                rec?.reset()
                rec?.release()
            } catch (_: Exception) {}
        }

        try {
            vd?.release()
        } catch (_: Exception) {}

        // ★ テレメトリ: 完了ログ
        val doneNs = timeBase.nowMonoNs()
        val uriStr = uri?.toString() ?: "unknown"
        telemetry.logVideoStart(
            tNs = doneNs,
            role = "screen_grid_done",
            uri = uriStr
        )

        Log.d("ScreenCaptureTelemetry", "Screen recording stopped: uri=$uriStr")

        // ★ Worker で S3 アップロード依頼
        if (uri != null && uriStr != "unknown") {
            val filename = fileName ?: "screen_${System.currentTimeMillis()}.mp4"

            val data = workDataOf(
                VideoUploadWorker.KEY_URI to uriStr,
                VideoUploadWorker.KEY_ROLE to "screen",      // front_cam に合わせて "screen"
                VideoUploadWorker.KEY_FILENAME to filename
            )

            val req = OneTimeWorkRequestBuilder<VideoUploadWorker>()
                .setInputData(data)
                .build()

            WorkManager.getInstance(context).enqueue(req)
            Log.d("ScreenCaptureTelemetry", "Enqueued VideoUploadWorker for $uriStr -> $filename")
        } else {
            Log.e("ScreenCaptureTelemetry", "Skip VideoUploadWorker: uri is null or unknown")
        }
    }

    fun release() {
        if (isRecording) stop()
        try {
            mediaProjection.stop()
        } catch (_: Exception) {}
    }
}