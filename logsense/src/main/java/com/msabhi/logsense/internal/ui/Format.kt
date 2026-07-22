package com.msabhi.logsense.internal.ui

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val timeFormat = DateTimeFormatter.ofPattern("HH:mm:ss.SSS")
private val dateTimeFormat = DateTimeFormatter.ofPattern("MMM d, HH:mm:ss")

internal fun Long.asTime(): String =
    timeFormat.format(Instant.ofEpochMilli(this).atZone(ZoneId.systemDefault()))

internal fun Long.asDateTime(): String =
    dateTimeFormat.format(Instant.ofEpochMilli(this).atZone(ZoneId.systemDefault()))
