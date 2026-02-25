package com.example.myapplicationplp

/**
 * スクリーンに表示しているパターンIDを TelemetryLogger に送る。
 *
 * 例えば:
 *  - "north_fixed_grid"
 *  - "dense_grid"
 *  - "checker_1"
 * など、任意の文字列でOK。
 */
class ScreenPatternLogger(
    private val telemetry: TelemetryLogger,
    private val timeBase: TimeBase
) {

    private var currentPattern: String? = null

    /**
     * パターンIDが変わったときだけログを吐く。
     */
    fun setPattern(patternId: String) {
        if (patternId == currentPattern) return
        currentPattern = patternId

        val tNs = timeBase.nowMonoNs()
        telemetry.logScreenPattern(tNs, patternId)
    }
}
