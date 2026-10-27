package kz.rudy.ar.ui.screens

import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.roundToLong

/** Fixed-point formatting without java.lang.String.format (commonMain). */
internal fun formatFixed(value: Float, decimals: Int): String {
    val factor = 10.0.pow(decimals)
    val scaled = (abs(value) * factor).roundToLong()
    val whole = scaled / factor.toLong()
    val frac = scaled % factor.toLong()
    val sign = if (value < 0) "-" else ""
    if (decimals == 0) return "$sign$whole"
    return "$sign$whole.${frac.toString().padStart(decimals, '0')}"
}

internal fun formatSeconds(ms: Long): String = formatFixed(ms / 1000f, 1) + " s"

internal fun formatUsd(usd: Double): String = "$" + formatFixed(usd.toFloat(), 3)

internal fun formatPercent(fraction: Double): String = formatFixed((fraction * 100).toFloat(), 0) + " %"
