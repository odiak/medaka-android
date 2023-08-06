package net.odiak.medaka.common

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import kotlin.time.Duration
import java.time.Duration as JavaDuration

fun Int.signed(): String {
    return when {
        this > 0 -> "+$this"
        this == 0 -> "±0"
        else -> "$this"
    }
}

fun String.parseISODateTime(): LocalDateTime {
    return LocalDateTime.parse(this, DateTimeFormatter.ISO_DATE_TIME)
}

fun periodicFlow(interval: Duration): Flow<Unit> = flow {
    emit(Unit)
    delay(interval)
    while (true) {
        delay(interval)
        emit(Unit)
    }
}

fun LocalDateTime.relativeTextTo(now: LocalDateTime): String {
    val diff = JavaDuration.between(this, now)
    val hours = diff.toHours()
    val minutes = diff.toMinutes()
    val seconds = diff.seconds
    return when {
        seconds == 0L -> "now"
        hours > 0 -> "${hours}h ago"
        minutes > 0 -> "${minutes}m ago"
        else -> "${seconds}s ago"
    }
}