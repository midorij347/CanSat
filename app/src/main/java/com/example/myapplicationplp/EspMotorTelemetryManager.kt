package com.example.myapplicationplp

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * ESP32-S3 からのモータエンコーダ PULSE を受信し TelemetryLogger に流す。
 *
 * ESP 側の送信フォーマット例:
 *   printf("PULSE,%d,%d\n", leftPulse, rightPulse);
 */
class EspMotorTelemetryManager(
    private val usb: UsbCdcRepo,
    private val telemetry: TelemetryLogger,
    private val timeBase: TimeBase,
    private val scope: CoroutineScope
) {

    private var job: Job? = null

    fun start() {
        if (job != null) return

        job = scope.launch(Dispatchers.IO) {
            val buf = ByteArray(64)
            val sb = StringBuilder()

            while (isActive) {
                val n = usb.read(buf, 100)  // 100ms timeout, -1: timeout
                if (n <= 0) continue
                sb.append(String(buf, 0, n, Charsets.US_ASCII))

                var idx = sb.indexOf("\n")
                while (idx >= 0) {
                    val line = sb.substring(0, idx).trim()
                    sb.delete(0, idx + 1)
                    handleLine(line)
                    idx = sb.indexOf("\n")
                }
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
    }

    private fun handleLine(line: String) {
        android.util.Log.d("ESP_RAW", "line=[$line]")
        // "PULSE,123,120" のような形式
        val parts = line.split(",")
        if (parts.size < 3) return
        if (parts[0] != "PULSE") return

        val left = parts[1].toIntOrNull() ?: return
        val right = parts[2].toIntOrNull() ?: return

        val tNs = timeBase.nowMonoNs()
        telemetry.logMotorPulse(tNs, left, right)
    }
}
