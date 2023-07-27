package net.odiak.medaka.common

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import kotlin.time.Duration

fun Int.signed(): String {
    return if (this > 0) "+$this" else "$this"
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