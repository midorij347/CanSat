package com.example.myapplicationplp

/**
 * アプリ側でモータ Duty を決めて ESP に送るときのヘルパ。
 *
 * プロトコル例:
 *   "DUTY,<leftDuty>,<rightDuty>\n"
 */
class MotorCommandSender(
    private val usb: UsbCdcRepo,
    private val telemetry: TelemetryLogger,
    private val timeBase: TimeBase
) {

    /**
     * Duty 指令を出す。
     *
     * leftDuty, rightDuty : -1.0f..+1.0f を想定（ESP 側で clamp）。
     */
    fun sendMotorDuty(leftDuty: Float, rightDuty: Float) {
        val tNs = timeBase.nowMonoNs()

        // 1. ログ
        telemetry.logMotorDuty(tNs, leftDuty, rightDuty)

        // 2. ESP へ送信
        val cmd = "DUTY,${leftDuty},${rightDuty}\n"
        usb.write(cmd.toByteArray(Charsets.US_ASCII))
    }
}
