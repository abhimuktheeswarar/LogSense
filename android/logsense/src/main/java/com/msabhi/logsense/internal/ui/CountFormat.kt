package com.msabhi.logsense.internal.ui

/** "6.7k"-style compact count used in the top-bar pill and the capture notification. */
internal fun formatCount(n: Int): String {
    if (n < 1000) return n.toString()
    val tenths = n / 100 // e.g. 6712 -> 67
    val whole = tenths / 10
    val frac = tenths % 10
    return if (frac == 0) "${whole}k" else "$whole.${frac}k"
}
