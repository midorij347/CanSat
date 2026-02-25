package com.example.myapplicationplp

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

object AwsApiClient {
    private val client = OkHttpClient()

    // presigned URL を取得する API Gateway の URL
    private const val API_URL =
        "https://c277m0xmu0.execute-api.ap-northeast-1.amazonaws.com/getPresignedUri"

    fun getPresignedUrl(role: String, filename: String): String? {
        val json = JSONObject().apply {
            put("role", role)
            put("filename", filename)
        }

        val body = json.toString()
            .toRequestBody("application/json".toMediaType())

        val req = Request.Builder()
            .url(API_URL)
            .post(body)
            .build()

        client.newCall(req).execute().use { res ->
            if (!res.isSuccessful) return null

            val text = res.body?.string() ?: return null
            val obj = JSONObject(text)
            return obj.getString("upload_url")
        }
    }
}