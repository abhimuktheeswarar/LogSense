package com.msabhi.logsense.internal.ui

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val timeFormat = DateTimeFormatter.ofPattern("HH:mm:ss.SSS")
private val shortTimeFormat = DateTimeFormatter.ofPattern("ss.SSS")
private val hourMinuteFormat = DateTimeFormatter.ofPattern("HH:mm")
private val dateTimeFormat = DateTimeFormatter.ofPattern("MMM d, HH:mm:ss")
private val monthDayFormat = DateTimeFormatter.ofPattern("MMM d")

internal fun Long.asTime(): String =
    timeFormat.format(Instant.ofEpochMilli(this).atZone(ZoneId.systemDefault()))

/** Compact "ss.SSS" timestamp for the dense compact view. */
internal fun Long.asShortTime(): String =
    shortTimeFormat.format(Instant.ofEpochMilli(this).atZone(ZoneId.systemDefault()))

internal fun Long.asDateTime(): String =
    dateTimeFormat.format(Instant.ofEpochMilli(this).atZone(ZoneId.systemDefault()))

/** ISO-8601 instant (UTC), for machine-readable JSON export. */
internal fun Long.asIso(): String = Instant.ofEpochMilli(this).toString()

/** "HH:mm" — session start / range endpoints. */
internal fun Long.asHourMinute(): String =
    hourMinuteFormat.format(Instant.ofEpochMilli(this).atZone(ZoneId.systemDefault()))

/** "Today" / "Yesterday" / "MMM d" for a session's day. */
internal fun Long.asDayLabel(): String {
    val zone = ZoneId.systemDefault()
    val day = Instant.ofEpochMilli(this).atZone(zone).toLocalDate()
    return when (LocalDate.now(zone).toEpochDay() - day.toEpochDay()) {
        0L -> "Today"
        1L -> "Yesterday"
        else -> monthDayFormat.format(day)
    }
}
