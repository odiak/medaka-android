package net.odiak.medaka.utils

import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

fun String.parseISODateTime(): LocalDateTime {
    return LocalDateTime.from(DateTimeFormatter.ISO_DATE_TIME.parse(this))
}