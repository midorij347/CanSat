package com.example.myapplicationplp

import android.content.Context
import org.json.JSONObject
import java.io.BufferedWriter
import java.io.File
import java.io.OutputStreamWriter
import android.os.SystemClock
import kotlinx.coroutines.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okio.Buffer
import okio.GzipSink
import okio.buffer
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.io.IOException
/**
 * すべてのテレメトリを 1 本の NDJSON ファイルに書き出すロガー。
 *
 * 1 行 1 JSON オブジェクト。
 *
 * type:
 *  - imu
 *  - motor_pulse
 *  - motor_duty
 *  - screen
 *  - video_start
 *  - video_frame_feature
 */
class TelemetryLogger(
    context: Context,
    private val timeBase: TimeBase,
    sessionId: String = System.currentTimeMillis().toString()
) {

    private val file: File =
        File(context.filesDir, "telemetry_$sessionId.ndjson")

    private val writer: BufferedWriter =
        BufferedWriter(OutputStreamWriter(file.outputStream(), Charsets.UTF_8))

    @Synchronized
    fun close() {
        writer.flush()
        writer.close()
    }

    @Synchronized
    private fun log(obj: JSONObject) {
        // 1行分の NDJSON 文字列を作成
        val line = obj.toString()

        // ローカルファイルに書き出し
        writer.append(line)
        writer.newLine()
        writer.flush()  // 必要なら頻度を下げても良い

        // メモリバッファに追加
        buffer.add(line)

        val now = SystemClock.elapsedRealtime()
        // 条件を満たしたら非同期で送信
        if (buffer.size >= BATCH_SIZE || now - lastFlushAt >= FLUSH_INTERVAL_MS) {
            // バッファをコピーしてクリア
            val batch = ArrayList<String>(buffer)
            buffer.clear()
            lastFlushAt = now

            ioScope.launch {
                sendBatch(batch)
            }
        }
    }

    // -------- IMU: 加速度 + RPY --------

    fun logImu(
        sensorTsNs: Long,
        ax: Float, ay: Float, az: Float,
        roll: Float?, pitch: Float?, yaw: Float?
    ) {
        val o = JSONObject().apply {
            put("type", "imu")
            put("t_ns", sensorTsNs)
            put("t_ms_est", timeBase.sensorNsToWallMs(sensorTsNs))
            put("ax", ax); put("ay", ay); put("az", az)
            roll?.let { put("roll", it) }
            pitch?.let { put("pitch", it) }
            yaw?.let { put("yaw", it) }
        }
        log(o)
    }

    // -------- モータ：パルス（エンコーダ） --------

    fun logMotorPulse(
        tNs: Long,
        leftPulse: Int,
        rightPulse: Int
    ) {
        val o = JSONObject().apply {
            put("type", "motor_pulse")
            put("t_ns", tNs)
            put("t_ms_est", timeBase.monoNsToWallMs(tNs))
            put("left", leftPulse)
            put("right", rightPulse)
        }
        log(o)
    }

    // -------- モータ：Duty 指令値 --------

    fun logMotorDuty(
        tNs: Long,
        leftDuty: Float,
        rightDuty: Float
    ) {
        val o = JSONObject().apply {
            put("type", "motor_duty")
            put("t_ns", tNs)
            put("t_ms_est", timeBase.monoNsToWallMs(tNs))
            put("left", leftDuty)
            put("right", rightDuty)
        }
        log(o)
    }

    // -------- スクリーンの状態（どのパターンを出しているか） --------

    fun logScreenPattern(
        tNs: Long,
        patternId: String
    ) {
        val o = JSONObject().apply {
            put("type", "screen")
            put("t_ns", tNs)
            put("t_ms_est", timeBase.monoNsToWallMs(tNs))
            put("pattern", patternId)
        }
        log(o)
    }

    // -------- ビデオ録画：開始 / 完了 --------

    /**
     * 録画開始を記録。
     * role: "front_cam" / "screen_rec" など識別用。
     * uri : まだ分からない場合は "pending:xxx" としておき、後で完了ログを追加。
     */
    fun logVideoStart(
        tNs: Long,
        role: String,
        uri: String
    ) {
        val o = JSONObject().apply {
            put("type", "video_start")
            put("role", role)
            put("t_ns", tNs)
            put("t_ms_est", timeBase.monoNsToWallMs(tNs))
            put("uri", uri)
        }
        log(o)
    }
    fun logVideoEnd(
        tNs: Long,
        role: String,
        uri: String
    ) {
        val o = JSONObject().apply {
            put("type", "video_end")
            put("role", role)
            put("t_ns", tNs)
            put("t_ms_est", timeBase.monoNsToWallMs(tNs))
            put("uri", uri)
        }
        log(o)
    }


    /**
     * フレーム単位の解析結果。
     *
     * feature: 例えば
     *   { "brightness_mean": 0.42, "edge_energy": 123.0 }
     * のようなオブジェクトを想定。
     */
    fun logVideoFrameFeature(
        tNs: Long,
        role: String,
        feature: JSONObject
    ) {
        val base = JSONObject(feature.toString()) // コピー
        base.put("type", "video_frame_feature")
        base.put("role", role)
        base.put("t_ns", tNs)
        base.put("t_ms_est", timeBase.monoNsToWallMs(tNs))
        log(base)
    }
    /**
     * NDJSON の行リストを 1 バッチとして gzip 圧縮し、
     * API Gateway に POST する。
     */
    private fun sendBatch(lines: List<String>) {
        if (lines.isEmpty()) return

        // 1つの NDJSON 文字列にまとめる（行ごとに \n）
        val ndjson = buildString(lines.size * 80) {
            for (line in lines) {
                append(line)
                append('\n')
            }
        }

        try {
            // gzip 圧縮
            val gzBuffer = Buffer()
            val gzipSink = GzipSink(gzBuffer).buffer()
            gzipSink.use { sink ->
                sink.writeUtf8(ndjson)
            }
            val gzBytes = gzBuffer.readByteArray()

            val mediaType = "application/x-ndjson".toMediaType()
            val body = gzBytes.toRequestBody(mediaType)

            val request = Request.Builder()
                .url(API_URL)
                .post(body)
                // ingest.py 側で gzip 済みと判断してもらうためのヘッダ
                .addHeader("Content-Encoding", "gzip")
                // どの端末から来たか識別するためのヘッダ（お好みで）
                .addHeader("X-Device-Id", DEVICE_ID)
                .build()

            ok.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) {
                    throw IOException("HTTP ${resp.code} ${resp.message}")
                }
                // 成功時は特に何もしない（S3側に保存される）
            }
        } catch (e: Exception) {
            // 失敗した場合はバッファに戻して次回再送してもよい
            synchronized(this) {
                // 先頭に戻すことで次の送信バッチにまた乗る
                buffer.addAll(0, lines)
            }
            // 必要なら Log.e(...) などでログ
            // Log.e("TelemetryLogger", "sendBatch failed", e)
        }
    }

    // ================================
    //  ここからがクラウドアップロード部分
    // ================================

    // ★ MainActivity.kt と同じ設定値
    private val API_URL = "https://hawa80igwf.execute-api.ap-northeast-1.amazonaws.com/prod"  // ←差し替え
    private val DEVICE_ID = "android-" + UUID.randomUUID().toString().take(8)
    private val BATCH_SIZE = 50               // これ以上貯まったら送信
    private val FLUSH_INTERVAL_MS = 3000L     // この時間経過したら送信

    // 送信用のコルーチンスコープと HTTP クライアント
    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val ok = OkHttpClient.Builder()
        .callTimeout(15, TimeUnit.SECONDS)
        .build()

    // NDJSON 行のバッファ

    private val buffer = ArrayList<String>(BATCH_SIZE * 2)
    private var lastFlushAt = SystemClock.elapsedRealtime()

    /**
     * 明示的に「今ある分をすぐ送りたい」ときに呼ぶ。
     * （バッファが空なら何もしない）
     */
    fun flushToCloud() {
        val batch: List<String>

        // バッファ内容を取り出してクリアする部分だけ同期化
        synchronized(this) {
            if (buffer.isEmpty()) {
                return
            }
            batch = ArrayList(buffer)
            buffer.clear()
            lastFlushAt = SystemClock.elapsedRealtime()
        }

        ioScope.launch {
            sendBatch(batch)
        }
    }



    fun getFile(): File = file
}
