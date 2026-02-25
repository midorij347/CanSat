package com.example.myapplicationplp

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File

object S3Uploader {
    private val client = OkHttpClient()

    fun uploadVideo(presignedUrl: String, file: File): Boolean {
        val mediaType = "video/mp4".toMediaType()
        val body = file.readBytes().toRequestBody(mediaType)

        val req = Request.Builder()
            .url(presignedUrl)
            .put(body)
            .build()

        client.newCall(req).execute().use { res ->
            return res.isSuccessful
        }
    }
}
