package com.commandcode.chat.domain

import java.math.BigDecimal
import java.math.RoundingMode

private const val BYTES_PER_MB = 1_000_000L
private const val BYTES_PER_GB = 1_000_000_000L
private val BYTES_PER_MB_BD = BigDecimal(BYTES_PER_MB)
private val BYTES_PER_GB_BD = BigDecimal(BYTES_PER_GB)

fun formatStorageBytes(bytes: Long): String {
    require(bytes >= 0) { "bytes must be non-negative" }
    if (bytes == 0L) return "0 MB"
    if (bytes < 10_000L) return "<0.01 MB"
    val exact = BigDecimal(bytes)
    // MB rounding exists only to choose the display unit.
    val megabytes = exact.divide(BYTES_PER_MB_BD, 2, RoundingMode.HALF_UP)
    if (megabytes < BigDecimal("1000.00")) return twoDecimalPlain(megabytes) + " MB"
    // GB digits are the true two-decimal rounding of the original byte value,
    // never digits of the MB-rounded figure.
    val gigabytes = exact.divide(BYTES_PER_GB_BD, 2, RoundingMode.HALF_UP)
    return twoDecimalPlain(gigabytes) + " GB"
}

private fun twoDecimalPlain(value: BigDecimal): String =
    value.setScale(2, RoundingMode.HALF_UP).toPlainString()
