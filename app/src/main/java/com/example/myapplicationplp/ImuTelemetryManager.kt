package com.example.myapplicationplp

import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager

/**
 * IMU(加速度 + 磁気) から RPY を推定して TelemetryLogger に投げる。
 *
 * FusionController や ARCore とは独立した「計測専用」のリスナー。
 */
class ImuTelemetryManager(
    private val sensorManager: SensorManager,
    private val telemetry: TelemetryLogger,
    private val timeBase: TimeBase
) : SensorEventListener {

    private val acc = FloatArray(3)
    private val mag = FloatArray(3)
    private var hasAcc = false
    private var hasMag = false

    private val R = FloatArray(9)
    private val I = FloatArray(9)
    private val ori = FloatArray(3)

    fun start() {
        sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)?.also {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
        }
        sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)?.also {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
        }
    }

    fun stop() {
        sensorManager.unregisterListener(this)
    }

    override fun onSensorChanged(event: SensorEvent) {
        when (event.sensor.type) {
            Sensor.TYPE_ACCELEROMETER -> {
                for (i in 0..2) acc[i] = event.values[i]
                hasAcc = true
            }
            Sensor.TYPE_MAGNETIC_FIELD -> {
                for (i in 0..2) mag[i] = event.values[i]
                hasMag = true
            }
        }

        if (hasAcc && hasMag) {
            val ok = SensorManager.getRotationMatrix(R, I, acc, mag)
            if (ok) {
                SensorManager.getOrientation(R, ori)
                val yaw = ori[0]   // -π..π, 北基準
                val pitch = ori[1]
                val roll = ori[2]

                val ts = event.timestamp // 最後に来た方の timestamp

                telemetry.logImu(
                    sensorTsNs = ts,
                    ax = acc[0], ay = acc[1], az = acc[2],
                    roll = roll, pitch = pitch, yaw = yaw
                )
            } else {
                // 回転行列が取れないときは加速度だけでも流しておく
                if (event.sensor.type == Sensor.TYPE_ACCELEROMETER) {
                    telemetry.logImu(
                        sensorTsNs = event.timestamp,
                        ax = acc[0], ay = acc[1], az = acc[2],
                        roll = null, pitch = null, yaw = null
                    )
                }
            }
        } else if (event.sensor.type == Sensor.TYPE_ACCELEROMETER) {
            // まだ磁気が来てない場合: 加速度だけログ（簡易LPFも好きに入れてOK）
            telemetry.logImu(
                sensorTsNs = event.timestamp,
                ax = acc[0], ay = acc[1], az = acc[2],
                roll = null, pitch = null, yaw = null
            )
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        // 無し
    }
}
