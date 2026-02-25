package com.example.myapplicationplp

import android.os.SystemClock

/**
 * アプリ起動時の「時間ベース」。
 *
 * - baseElapsedNs : elapsedRealtimeNanos() の基準
 * - baseWallMs    : そのときの Unix time(ms)
 *
 * センサの timestamp(ns) は「起動からのns」なので、
 * これと差分を取ることで「だいたいの Unix ms」に変換できる。
 */
data class TimeBase(
    val baseElapsedNs: Long = SystemClock.elapsedRealtimeNanos(),
    val baseWallMs: Long = System.currentTimeMillis()
) {
    /** 今の「単調増加 ns」 */
    fun nowMonoNs(): Long = SystemClock.elapsedRealtimeNanos()

    /** センサ timestamp(ns) → 推定 Unix ms に変換 */
    fun sensorNsToWallMs(sensorNs: Long): Long {
        return baseWallMs + (sensorNs - baseElapsedNs) / 1_000_000L
    }

    /** 単調 ns → 推定 Unix ms に変換 */
    fun monoNsToWallMs(monoNs: Long): Long {
        return baseWallMs + (monoNs - baseElapsedNs) / 1_000_000L
    }
}
