package net.odiak.medaka.common

import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

fun Int.signed(): String {
    return if (this > 0) "+$this" else "$this"
}

fun String.parseISODateTime(): LocalDateTime {
    return LocalDateTime.parse(this, DateTimeFormatter.ISO_DATE_TIME)
}