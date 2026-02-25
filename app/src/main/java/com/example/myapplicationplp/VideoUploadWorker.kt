
package com.example.myapplicationplp

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.work.Worker
import androidx.work.WorkerParameters
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.File
import java.io.IOException

class VideoUploadWorker(
    appContext: Context,
    params: WorkerParameters
) : Worker(appContext, params) {

    companion object {
        private const val TAG = "VideoUploadWorker"

        // ★ Telemetry 側から渡しているキー
        const val KEY_URI = "uri"          // content:// or file://
        const val KEY_ROLE = "role"
        const val KEY_FILENAME = "filename"

        // ★ あなたの API Gateway エンドポイント
        private const val SIGN_URL_ENDPOINT =
            "https://c27m0xmux0.execute-api.ap-northeast-1.amazonaws.com/getPresignedUri"
    }

    private val client = OkHttpClient()

    override fun doWork(): Result {
        val role = inputData.getString(KEY_ROLE)
        val filename = inputData.getString(KEY_FILENAME)
        val uriStr = inputData.getString(KEY_URI)

        Log.d(TAG, "doWork role=$role filename=$filename uri=$uriStr")

        if (role.isNullOrBlank() || filename.isNullOrBlank()) {
            Log.e(TAG, "Missing role or filename")
            return Result.failure()
        }

        // 1) 動画データを読み出す（uri 優先）
        val bytes = try {
            readVideoBytes(uriStr, filename)
        } catch (e: Exception) {
            Log.e(TAG, "readVideoBytes failed", e)
            return Result.failure()
        }

        return try {
            // 2) presigned URL を Lambda からもらう
            val presignedUrl = requestPresignedUrl(role, filename)
            Log.d(TAG, "presignedUrl = $presignedUrl")

            // 3) S3 に PUT
            uploadBytesToS3(presignedUrl, bytes)

            Log.d(TAG, "Upload success: role=$role filename=$filename")
            Result.success()
        } catch (e: Exception) {
            Log.e(TAG, "Upload failed", e)
            Result.retry()
        }
    }

    /**
     * uri があればそれを使って読み込む。
     * なければ getExternalFilesDir(...)/Movies/Spotlight/filename を見る。
     */
    private fun readVideoBytes(uriStr: String?, filename: String): ByteArray {
        if (!uriStr.isNullOrBlank()) {
            val uri = Uri.parse(uriStr)

            if ("file".equals(uri.scheme, ignoreCase = true)) {
                val path = uri.path
                if (path != null) {
                    val f = File(path)
                    Log.d(TAG, "File from uri path = $path exists=${f.exists()} length=${f.length()}")
                }
            }

            applicationContext.contentResolver.openInputStream(uri).use { input ->
                if (input != null) {
                    Log.d(TAG, "Reading bytes from uri=$uri")
                    return input.readBytes()
                } else {
                    Log.w(TAG, "openInputStream(uri) returned null, fallback to File")
                }
            }
        }

        // fallback: getExternalFilesDir(...)/Spotlight/filename を見る
        val moviesDir =
            applicationContext.getExternalFilesDir(android.os.Environment.DIRECTORY_MOVIES)
        val spotlightDir = File(moviesDir, "Spotlight")
        val file = File(spotlightDir, filename)
        Log.d(TAG, "Fallback file=${file.absolutePath} exists=${file.exists()} length=${file.length()}")
        if (!file.exists()) {
            throw IOException("File not found at ${file.absolutePath}")
        }
        return file.readBytes()
    }


    /**
     * Lambda(API Gateway) に role / filename を送り、
     * {"upload_url": "..."} を受け取る。
     */
    private fun requestPresignedUrl(role: String, filename: String): String {
        val json = JSONObject().apply {
            put("role", role)
            put("filename", filename)
        }

        val mediaType = "application/json; charset=utf-8".toMediaType()
        val body = json.toString().toRequestBody(mediaType)

        val req = Request.Builder()
            .url(SIGN_URL_ENDPOINT)
            .post(body)
            .build()
        Log.d("json","$req")

        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) {
                throw IOException("sign url failed: ${resp.code}")
            }
            val bodyStr = resp.body?.string() ?: throw IOException("empty body from sign url")
            val obj = JSONObject(bodyStr)
            return obj.getString("upload_url")
        }
    }

    /**
     * 実際に presigned URL に対して PUT する。
     */
    private fun uploadBytesToS3(presignedUrl: String, bytes: ByteArray) {
        val mediaType = "video/mp4".toMediaType()
        val body = bytes.toRequestBody(mediaType)

        val req = Request.Builder()
            .url(presignedUrl)
            .put(body)
            .build()

        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) {
                throw IOException("PUT failed: ${resp.code}")
            }
        }
    }
}